# InfiniteChat — OfflineService 模块复盘

## 一、模块定位

OfflineService 是整个 IM 系统的**离线兜底层**：当用户不在线时，消息和通知无法通过 WebSocket 实时推送，这些"推不出去的数据"会被投递到 RocketMQ 的 `OFFLINE` Tag，由 OfflineService 消费并存储。用户下次上线时，客户端从这里拉取错过的消息。

```
Messaging / RTC 推送失败
        │
        │  IM_CHAT:OFFLINE / IM_NOTIFY:OFFLINE
        ▼
┌──────────────────────────────────────────────────────────────┐
│                  OfflineService (8087)                        │
│                                                              │
│  ┌─────────────────┐  ┌──────────────┐  ┌────────────────┐ │
│  │ 离线消息存储     │  │ 未读数管理    │  │ 离线通知存储   │ │
│  │ Redis + MySQL   │  │ Redis + MySQL │  │ Redis List    │ │
│  │ 双写热冷分层    │  │ Hash + DB    │  │ 轻量 7 天     │ │
│  └─────────────────┘  └──────────────┘  └────────────────┘ │
│                                                              │
│  ┌─────────────────┐  ┌──────────────────────────────────┐  │
│  │ 拉取接口        │  │ 定时清理任务                      │  │
│  │ Redis优先       │  │ 每天凌晨2点                       │  │
│  │ MySQL 降级      │  │ 过期7天 + 已读3天                 │  │
│  └─────────────────┘  └──────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## 二、整体架构

### 2.1 数据流入（写路径）

```
              Messaging                          RTC (推送失败回流)
                 │                                      │
       IM_CHAT:OFFLINE                          IM_CHAT:OFFLINE
       IM_NOTIFY:OFFLINE                       IM_NOTIFY:OFFLINE
                 │                                      │
                 └──────────────┬────────────────────────┘
                                │
                                ▼
                ┌──────────────────────────────┐
                │  OfflineMessageListener       │  ← 聊天消息
                │  topic=IM_CHAT, tag=OFFLINE   │
                ├──────────────────────────────┤
                │  OfflineNotifyListener        │  ← 系统通知
                │  topic=IM_NOTIFY, tag=OFFLINE │
                └──────────────┬───────────────┘
                               │
                 ┌─────────────┼─────────────┐
                 │             │             │
                 ▼             ▼             ▼
           Redis ZSet     MySQL 表     Redis Hash
           (离线消息)   (离线消息)    (未读计数)
```

### 2.2 数据流出（读路径）

```
客户端上线
  │
  │  POST /api/offline/pull
  │  GET  /api/offline/unread
  │  POST /api/offline/notifications
  │
  ▼
┌──────────────────────────────────────────┐
│ 离线消息拉取: Redis ZSet 优先            │
│   命中 → 返回                            │
│   未命中 → 降级 MySQL 按 seq 游标查询    │
│                                          │
│ 未读数: Redis Hash 优先                  │
│   命中 → 返回                            │
│   未命中 → 降级 MySQL 查询并回填 Redis   │
│                                          │
│ 离线通知: Redis List → LRANGE + DELETE   │
└──────────────────────────────────────────┘
```

---

## 三、中间件一览

```
┌──────────────────────────────────────────────────────────────────┐
│                            Redis                                  │
│                                                                   │
│  离线消息 (热数据, TTL 3天):                                      │
│    im:offline:{userId}:{sessionId}  ← ZSet (score=seq, val=JSON) │
│    im:offline:sessions:{userId}     ← Set (有离线消息的会话集合)  │
│                                                                   │
│  未读计数:                                                        │
│    im:unread:{userId}               ← Hash (field=sessionId,     │
│                                       value=count)               │
│                                                                   │
│  离线通知 (TTL 7天):                                              │
│    im:offline:notify:{userId}       ← List (wsPayload JSON)      │
│    最多 200 条/用户 (LTRIM 裁剪)                                  │
│                                                                   │
│  拉取限流:                                                        │
│    im:pull:limit:{userId}           ← INCR (10次/秒)             │
│                                                                   │
│  定时任务分布式锁:                                                │
│    im:task:offline-message-clean    ← SETNX (TTL 10min)          │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                           MySQL                                   │
│                                                                   │
│  offline_message 表 (冷数据):                                     │
│    id, message_id, receiver_id, session_id, seq,                 │
│    message_type, content, sender_id, sender_name, sender_avatar, │
│    created_at, expire_at, status (0=未读, 1=已读)                │
│                                                                   │
│  unread_count 表:                                                 │
│    user_id, session_id, unread_count,                            │
│    last_message_id, last_message_time, updated_at                │
│    (联合主键: user_id + session_id)                              │
└──────────────────────────────────────────────────────────────────┘

┌────────────────────┐  ┌─────────────────┐
│ RocketMQ           │  │  Nacos          │
│ IM_CHAT:OFFLINE    │  │  服务注册       │
│ IM_NOTIFY:OFFLINE  │  └─────────────────┘
└────────────────────┘
```

---

## 四、API 接口总览

| 接口 | 方法 | 功能 |
|------|------|------|
| `/api/offline/pull` | POST | 拉取离线消息（支持单会话/全会话） |
| `/api/offline/unread` | GET | 获取所有会话的未读数 |
| `/api/offline/read` | POST | 标记某会话已读 |
| `/api/offline/clear/{sessionId}` | POST | 清空某会话未读数 |
| `/api/offline/notifications` | POST | 拉取离线通知（一次性取出+删除） |

---

## 五、核心流程详解

### 5.1 离线消息存储（写入）

```
OfflineMessageListener.onMessage()
    │
    │  反序列化 GatewayPushPacket → 取 wsPayload + targetUserIds
    │
    │  for each receiverId:
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ storeOfflineMessage(receiverId, messageJson)                     │
│                                                                  │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ 1. 热存储: Redis ZSet                                       │ │
│ │                                                              │ │
│ │    ZADD im:offline:{userId}:{sessionId} score=seq val=JSON   │ │
│ │    EXPIRE 3 天                                               │ │
│ │                                                              │ │
│ │    SADD im:offline:sessions:{userId} sessionId               │ │
│ │    EXPIRE 3 天                                               │ │
│ │                                                              │ │
│ │    ZSet 用 seq 做 score → 天然有序                           │ │
│ │    sessions Set → 记录"哪些会话有离线消息"                   │ │
│ └──────────────────────────────────────────────────────────────┘ │
│                                                                  │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ 2. 冷存储: MySQL offline_message 表                         │ │
│ │                                                              │ │
│ │    INSERT offline_message                                    │ │
│ │    (receiver_id, session_id, seq, message_type, content,     │ │
│ │     sender_id, sender_name, sender_avatar,                   │ │
│ │     created_at, expire_at = now + 7天, status = 0)          │ │
│ └──────────────────────────────────────────────────────────────┘ │
│                                                                  │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ 3. 未读数 +1                                                │ │
│ │                                                              │ │
│ │    Redis: HINCRBY im:unread:{userId} sessionId 1             │ │
│ │    MySQL: INSERT ... ON DUPLICATE KEY UPDATE                 │ │
│ │            unread_count = unread_count + 1                   │ │
│ └──────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

面试要点：
- **为什么双写 Redis + MySQL？** Redis 是热数据层（3 天），拉取速度快（ZSet 按 score 范围查询 O(logN+M)）。MySQL 是冷数据兜底（7 天），防止 Redis 数据过期后消息丢失。3 天内的拉取走 Redis，超过 3 天走 MySQL。
- **为什么用 ZSet 而不是 List？** ZSet 的 score 是 seq，天然有序，支持范围查询（`ZRANGEBYSCORE`）。客户端可以传 `lastSeq` 作为游标，精准拉取"从哪条开始"的增量消息。List 只支持头尾操作，不支持按 score 范围查询。
- **sessions Set 的作用？** 快速知道"这个用户在哪些会话有离线消息"，避免遍历所有会话。拉取全会话离线消息时，先从 Set 拿会话列表，再逐个查 ZSet。

### 5.2 离线通知存储

```
OfflineNotifyListener.onMessage()
    │
    │  反序列化 GatewayPushPacket → 取 wsPayload + targetUserIds
    │
    │  for each userId:
    ▼
┌──────────────────────────────────────────────────────────────┐
│ Redis List (轻量存储，不落 MySQL)                            │
│                                                              │
│ RPUSH  im:offline:notify:{userId}  wsPayload                │
│ LTRIM  im:offline:notify:{userId}  -200  -1  ← 最多保留200条│
│ EXPIRE im:offline:notify:{userId}  7天                       │
└──────────────────────────────────────────────────────────────┘
```

面试要点：
- **通知为什么不落 MySQL？** 系统通知（好友申请、新会话、朋友圈更新）是轻量级、可丢失的辅助信息，不像聊天消息那样必须持久化。Redis List + TTL 7 天足够，简化架构。
- **LTRIM -200 -1 是什么意思？** 保留列表的最后 200 条（最新的 200 条）。防止某用户长期不上线，通知堆积占用大量内存。

### 5.3 离线消息拉取

```
客户端
  │  POST /api/offline/pull
  │  {
  │    sessionId: "single_1_2",      ← 单会话模式 (可选)
  │    lastSeq: 50,                  ← 游标
  │    sessionSeqMap: {...},         ← 全会话模式 (可选)
  │    pageSize: 50
  │  }
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. 限流检查                                                      │
│    Redis INCR im:pull:limit:{userId} + EXPIRE 1s                 │
│    > 10 → 拒绝: "拉取过于频繁"                                   │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. 分发到对应拉取策略                                            │
│                                                                  │
│  sessionId != null → 单会话拉取                                  │
│  sessionId == null → 全会话拉取                                  │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼ (以单会话为例)
┌──────────────────────────────────────────────────────────────────┐
│ 3. Redis 热数据优先                                              │
│                                                                  │
│    ZRANGEBYSCORE im:offline:{userId}:{sessionId}                 │
│                  min=lastSeq  max=+inf  LIMIT 0 pageSize         │
│                                                                  │
│    → 命中 → 直接返回                                             │
│    → 空   → 降级 MySQL                                           │
└──────────────────────────────────────────────────────────────────┘
    │ Redis 为空
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 4. MySQL 冷数据降级                                              │
│                                                                  │
│    SELECT * FROM offline_message                                 │
│    WHERE receiver_id = ? AND session_id = ?                      │
│      AND seq > lastSeq AND status = 0                            │
│    ORDER BY seq ASC                                              │
│    LIMIT pageSize                                                │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
返回: { messages: [...], hasMore: true/false, total, currentTime }
```

### 5.4 未读数管理

```
┌──────────────────────────────────────────────────────────────────┐
│                     未读数读写模型                                │
│                                                                  │
│  写入 (消息到达时):                                              │
│    Redis: HINCRBY im:unread:{userId} {sessionId} 1              │
│    MySQL: INSERT ... ON DUPLICATE KEY UPDATE                     │
│           unread_count = unread_count + 1                        │
│                                                                  │
│  读取 (客户端上线时):                                            │
│    Redis Hash → 命中 → 返回 {sessionId: count, ...}             │
│                  ↓ 未命中                                        │
│    MySQL SELECT → 回填 Redis → 返回                              │
│                                                                  │
│  标记已读:                                                       │
│    Redis: HDEL im:unread:{userId} {sessionId}                   │
│    MySQL: UPDATE unread_count SET unread_count = 0               │
│                                                                  │
│  清空会话未读:                                                   │
│    同标记已读逻辑                                                │
└──────────────────────────────────────────────────────────────────┘
```

面试要点：
- **为什么用 Hash 而不是 String？** 一个用户可能同时在多个会话有未读消息。Hash 的 field 是 sessionId，value 是未读数，一个 key 存所有会话的未读数，比 N 个 String key 节省内存、减少 Redis 命令数。
- **ON DUPLICATE KEY UPDATE 是什么？** MySQL 的 upsert 语法。首次写入时 INSERT，后续写入时 UPDATE。`unread_count = unread_count + 1` 利用行锁保证原子递增，不需要先 SELECT 再 UPDATE。

### 5.5 离线通知拉取

```
客户端
  │  POST /api/offline/notifications
  │
  ▼
Redis LRANGE im:offline:notify:{userId} 0 -1   ← 取出全部
Redis DELETE im:offline:notify:{userId}          ← 一次性清空
    │
    ▼
返回 List<String>  (每条是完整的 wsPayload JSON)
```

一次性取出 + 删除，语义简单明确：通知"看过就扔"，不需要游标和分页。

---

## 六、热冷分层存储架构

```
时间线:
  消息到达 ──────── 3天 ─────── 7天 ──────── ∞
     │                │           │
     │   Redis ZSet   │  MySQL    │  已清理
     │   (热数据)     │  (冷数据) │
     │                │           │
     ▼                ▼           ▼
  拉取走 Redis    Redis 已过期    MySQL 已过期
  O(logN) 极速    降级走 MySQL    数据不可恢复
                  按 seq 游标查

存储对比:
┌──────────────┬──────────────┬───────────────────┐
│              │ Redis ZSet   │ MySQL             │
├──────────────┼──────────────┼───────────────────┤
│ 保留时长     │ 3 天         │ 7 天 (expire_at)  │
│ 查询方式     │ ZRANGEBYSCORE│ WHERE seq > ? ASC │
│ 查询复杂度   │ O(logN+M)   │ 走索引 O(logN)    │
│ 适合场景     │ 刚离线不久   │ 长时间未登录      │
│ 数据丢失风险 │ Redis 重启   │ 几乎无            │
└──────────────┴──────────────┴───────────────────┘
```

---

## 七、定时清理任务

```
OfflineMessageCleanTask
    │
    │  @Scheduled cron = "0 0 2 * * ?"  (每天凌晨 2 点)
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. 分布式锁                                                     │
│    Redis SETNX im:task:offline-message-clean TTL=10min           │
│    → 未抢到锁: 跳过 (其他实例在执行)                             │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. 清理过期消息 (expire_at < now - 7天)                         │
│    LIMIT 1000 分批删除                                           │
│    每批删完 sleep 1s → 避免长时间占用 DB 连接                    │
│    循环直到无更多数据                                            │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 3. 清理已读消息 (status=1 AND created_at < now - 3天)           │
│    同样分批删除 + sleep                                          │
│    已读消息保留时间更短 (3 天 vs 7 天)                           │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
释放分布式锁
```

面试要点：
- **为什么分批删除而不是一次 DELETE WHERE？** 大批量 DELETE 会长时间持有行锁，阻塞其他查询。分批 1000 条 + sleep 1s 是数据库友好的"涓滴删除"模式。
- **为什么已读消息保留更短？** 已读消息用户已经看过了，3 天后再来拉取的概率极低。未读消息保留 7 天，给用户更长的"回来查看"窗口。

---

## 八、完整数据流全景

### 8.1 消息从发送到离线存储

```
用户A                Messaging           RocketMQ          RTC NODE_1         OfflineService
  │  发送消息给B       │                    │                  │                    │
  │──────────────────▶│                    │                  │                    │
  │                   │  查路由: B→NODE_1   │                  │                    │
  │                   │──IM_CHAT:NODE_1───▶│                  │                    │
  │                   │                    │──推送给B────────▶│                    │
  │                   │                    │                  │  B 不在线!          │
  │                   │                    │                  │  推送失败           │
  │                   │                    │                  │──IM_CHAT:OFFLINE──▶│
  │                   │                    │                  │                    │
  │                   │                    │                  │              存 Redis ZSet
  │                   │                    │                  │              存 MySQL
  │                   │                    │                  │              未读数 +1
```

### 8.2 用户上线拉取离线消息

```
用户B (上线)         Gateway            OfflineService               Redis           MySQL
  │                    │                      │                        │               │
  │  GET /unread       │                      │                        │               │
  │──────────────────▶│─────────────────────▶│                        │               │
  │                    │                      │  HGETALL im:unread:B   │               │
  │                    │                      │───────────────────────▶│               │
  │                    │                      │◀──────────────────────│               │
  │◀────────────────────────────────────────│                        │               │
  │  {"single_1_2": 5, "group_abc": 12}      │                        │               │
  │                    │                      │                        │               │
  │  POST /pull        │                      │                        │               │
  │  {sessionId:"single_1_2", lastSeq:50}    │                        │               │
  │──────────────────▶│─────────────────────▶│                        │               │
  │                    │                      │ ZRANGEBYSCORE          │               │
  │                    │                      │ im:offline:B:single_1_2│               │
  │                    │                      │ 50 +inf LIMIT 50      │               │
  │                    │                      │───────────────────────▶│               │
  │                    │                      │◀──────────────────────│  (5条消息)    │
  │◀────────────────────────────────────────│                        │               │
  │  {messages: [...], hasMore: false}        │                        │               │
  │                    │                      │                        │               │
  │  POST /read        │                      │                        │               │
  │  {sessionId:"single_1_2"}                │                        │               │
  │──────────────────▶│─────────────────────▶│                        │               │
  │                    │                      │  HDEL im:unread:B      │               │
  │                    │                      │       single_1_2       │               │
  │                    │                      │───────────────────────▶│               │
  │                    │                      │  UPDATE unread_count   │               │
  │                    │                      │  SET unread_count=0────────────────────▶│
```

---

## 九、面试高频问题与回答思路

### Q1: 离线消息为什么要热冷分层？直接全存 MySQL 不行吗？

> 全存 MySQL 技术上可行，但性能不够。用户上线拉离线消息是高频操作，MySQL 按 seq 范围查询即使走索引也需要磁盘 IO。Redis ZSet 按 score 范围查询是纯内存操作，延迟是微秒级。大多数用户的离线时间不超过几小时，3 天的 Redis 热数据已经覆盖了 99% 的拉取场景。只有极少数长时间未登录的用户才会降级到 MySQL。

### Q2: 未读数为什么同时写 Redis 和 MySQL？

> Redis 是热路径，客户端打开 App 就要看到未读数小红点，必须极快。MySQL 是持久化兜底——Redis 重启后未读数会丢失，此时从 MySQL 回填。写入时 Redis `HINCRBY` + MySQL `ON DUPLICATE KEY UPDATE` 双写，读取时 Redis 优先、MySQL 降级回填。

### Q3: 离线通知为什么不落 MySQL？

> 系统通知（好友申请、新会话）是辅助信息，不像聊天消息那样是核心数据。丢了可以重新触发（比如重新打开好友列表），不值得为它增加 MySQL 写入开销。Redis List + TTL 7 天 + 最多 200 条的限制，足够轻量且不占过多内存。

### Q4: 拉取接口为什么要限流？

> 防止恶意客户端或 Bug 导致的高频轮询。比如客户端死循环调 `/pull`，每秒几百次，会打爆 Redis 和 MySQL。限流 10 次/秒是合理的——正常用户上线时拉一次，切会话时拉一次，不可能达到 10 次/秒。

### Q5: 清理任务为什么要分布式锁？

> OfflineService 可能多实例部署。如果两个实例同时跑清理任务，会重复扫描和删除，浪费数据库资源。Redis `SETNX` + TTL 10 分钟的简易锁保证同一时刻只有一个实例在执行。TTL 是兜底——万一持锁实例宕机，10 分钟后锁自动释放，其他实例下一轮可以接手。

### Q6: ZSet 的 score 为什么用 seq 而不是时间戳？

> seq 是会话级单调递增的序列号，保证了消息的全局顺序。时间戳在高并发下可能重复（同一毫秒发多条消息），导致 ZSet 里的消息排序不确定。用 seq 做 score，拉取时传 `lastSeq` 作为游标，语义精确："给我 seq 大于 50 的所有消息"。

---

## 十、技术栈速查

| 技术 | 用途 |
|------|------|
| Spring Boot 2.6.13 | 基础框架 |
| RocketMQ 2.2.3 | 消费离线消息和通知 (OFFLINE Tag) |
| Redis | ZSet 热存储、Hash 未读数、List 离线通知、限流、分布式锁 |
| MySQL + MyBatis-Plus 3.5.2 | 冷存储、未读数持久化 |
| Nacos | 服务注册发现 |
| Fastjson 1.2.76 | JSON 序列化 |
| HikariCP | 数据库连接池 (max 30) |
