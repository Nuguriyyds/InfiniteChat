# InfiniteChat — Messaging 模块复盘

## 一、模块定位

Messaging 是整个 IM 系统的**业务中枢**：负责消息的发送决策、序号分配、路由投递、异步落库、离线补齐，以及完整的红包子系统（发红包、抢红包、过期退款）。它**不持有 WebSocket 连接**，所有实时推送都通过 RocketMQ 委托给 RTC 模块。

```
客户端
  │  HTTP (通过 Gateway)
  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Messaging Service (8084)                      │
│                                                                  │
│  ┌───────────────┐  ┌────────────┐  ┌─────────────────────────┐│
│  │ 消息引擎       │  │ 红包系统    │  │ 会话管理               ││
│  │ (发送/seq/同步) │  │ (发/抢/过期)│  │ (单聊/群聊/加退群)     ││
│  └───────┬───────┘  └─────┬──────┘  └─────────────────────────┘│
│          │                │                                      │
│          ▼                ▼                                      │
│  ┌──────────────────────────────────────┐                       │
│  │         RocketMQ 生产者               │                       │
│  │  IM_CHAT / IM_NOTIFY / AI_AGENT_REQ  │                       │
│  │  RED_PACKET_EXPIRE / IM_RED_PACKET   │                       │
│  └──────────────────────────────────────┘                       │
│                                                                  │
│  ┌──────────────────────────────────────┐                       │
│  │         RocketMQ 消费者               │                       │
│  │  MessageStoreListener (异步落库)      │                       │
│  │  RedPacketReceiveListener (抢红包落库)│                       │
│  │  RedPacketExpireListener (过期退款)   │                       │
│  │  RedPacketTxListener (事务消息回查)   │                       │
│  └──────────────────────────────────────┘                       │
│                                                                  │
│  ┌──────────────────────────────────────┐                       │
│  │         定时补偿任务                   │                       │
│  │  MsgFailoverRetryTask (MQ 失败重投)   │                       │
│  │  RedPacketExpireTask (过期兜底扫描)    │                       │
│  └──────────────────────────────────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、中间件一览

```
┌──────────────────────────────────────────────────────────────┐
│                         Redis                                 │
│                                                               │
│  im:seq:{sessionId}                 ← 会话级消息序号 (INCR)   │
│  im:msg:dedup:{sessionId}:{cMsgId}  ← 幂等去重 (TTL 5min)   │
│  im:route:{userId}                  ← 用户路由 (读: 决策Tag) │
│  im:cache:user:{userId}             ← 用户信息缓存           │
│  im:cache:friend:{a}:{b}            ← 好友关系缓存           │
│  im:cache:session:{sessionId}       ← 会话信息缓存           │
│  im:cache:session:members:{sId}     ← 群成员列表缓存         │
│  red_packet:count:{rpId}            ← 红包剩余个数           │
│  red_packet:amounts:{rpId}          ← 预计算金额 List        │
│  red_packet:received:{rpId}         ← 已领取用户 Set         │
│  red_packet:pending:{rpId}          ← 待落库金额 Hash        │
│  im:task:msg-failover-retry         ← 补偿任务分布式锁       │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                       RocketMQ                                │
│                                                               │
│  IM_CHAT:{nodeId/OFFLINE}       ← 聊天消息 (顺序消息)        │
│  IM_NOTIFY:{nodeId/OFFLINE}     ← 系统通知                   │
│  AI_AGENT_REQUEST:{tag}         ← AI 助手请求                │
│  IM_RED_PACKET_RECEIVE          ← 抢红包落库 (事务消息)      │
│  RED_PACKET_EXPIRE              ← 红包过期 (延迟消息, 24h)   │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────┐  ┌─────────────────┐  ┌─────────────┐
│ MySQL (InfiniteChat)  │  │  Nacos          │  │  Redisson   │
│                       │  │  服务注册       │  │  分布式锁    │
│ im_message            │  │                 │  │  (补偿任务) │
│ im_session            │  └─────────────────┘  └─────────────┘
│ im_user_session       │
│ im_msg_failover       │
│ im_red_packet         │
│ im_red_packet_receive │
│ im_user_balance       │
│ im_balance_log        │
│ im_user / im_friend   │
└───────────────────────┘
```

---

## 三、API 接口总览

### 3.1 消息接口 (`/api/message`)

| 接口 | 方法 | 功能 |
|------|------|------|
| `/send` | POST | 发送消息（单聊/群聊），返回 messageId + seq |
| `/maxSeq` | GET | 查询会话当前最大 seq |
| `/sync` | GET | 按 seq 范围拉取历史消息（离线补齐） |
| `/ack` | POST | 客户端上报已确认的最大 seq |
| `/sessions/ackSeqMap` | GET | 获取用户所有会话的 lastAckSeq |

### 3.2 红包接口 (`/api/message/redPacket`)

| 接口 | 方法 | 功能 |
|------|------|------|
| `/send` | POST | 发红包（扣款+建红包+预计算金额+发消息+延迟过期） |
| `/receive` | POST | 抢红包（事务消息+Lua原子扣减） |
| `/detail` | GET | 查看红包详情（领取记录分页） |

### 3.3 会话接口 (`/api/message/session`)

| 接口 | 方法 | 功能 |
|------|------|------|
| `/createSingle` | POST | 创建单聊会话 |
| `/joinGroup` | POST | 加入群聊 |
| `/leaveGroup` | POST | 退出群聊 |
| `/kickMember` | POST | 踢出群成员（权限校验） |

---

## 四、消息引擎核心流程

### 4.1 发送消息全链路

```
客户端
  │  POST /api/message/send
  │  { sessionId, sessionType, receiveUserId, type, body, clientMsgId }
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 阶段零：幂等去重                                                 │
│                                                                  │
│  Redis SETNX "im:msg:dedup:{sessionId}:{clientMsgId}" TTL=5min  │
│  → 首次: true → 继续                                            │
│  → 重复: false → 直接返回成功假象，让客户端停止重试              │
│                                                                  │
│  异常时删除去重 key，避免客户端被永久死锁                        │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 阶段一：校验                                                     │
│                                                                  │
│  单聊: 发送者状态 + 接收者状态 + 双向好友关系                    │
│  群聊: 发送者是否在群成员列表内                                  │
│  (全部走 Cache-Aside 缓存，24~48h 随机过期防雪崩)               │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 阶段二：发号                                                     │
│                                                                  │
│  Redis INCR "im:seq:{sessionId}" → 得到全局递增的 seq            │
│  雪花算法生成 messageId (msg_{snowflakeId})                      │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 阶段三：MQ 投递                                                  │
│                                                                  │
│  ┌────────────────────────────────────────────────┐              │
│  │ 单聊                                          │              │
│  │  1. Redis GET im:route:{receiveUserId}         │              │
│  │     → nodeId (如 NODE_1) 或 null → "OFFLINE"   │              │
│  │  2. 封装 GatewayPushPacket {[receiveUserId],   │              │
│  │     wsPayload}                                 │              │
│  │  3. asyncSendOrderly("IM_CHAT:NODE_1",         │              │
│  │     packet, hashKey=sessionId)                 │              │
│  └────────────────────────────────────────────────┘              │
│                                                                  │
│  ┌────────────────────────────────────────────────┐              │
│  │ 群聊 (扩散写)                                  │              │
│  │  1. 从缓存拿群成员列表，去掉发送者              │              │
│  │  2. Redis MGET 批量查路由 → O(1) 网络 IO       │              │
│  │  3. 按节点分组: Map<nodeId, List<userId>>       │              │
│  │  4. 每组封装一个 GatewayPushPacket              │              │
│  │  5. 按组投递 MQ，同 sessionId 保证有序          │              │
│  │  6. 离线用户统一投递到 IM_CHAT:OFFLINE          │              │
│  └────────────────────────────────────────────────┘              │
│                                                                  │
│  MQ 失败回调: 写入 im_msg_failover 本地消息表 (兜底)            │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 阶段四：等待响应                                                  │
│                                                                  │
│  CompletableFuture.get(3s) 等待 MQ 回调                         │
│  → 成功/超时: 返回 {messageId, clientMsgId, seq, status=1}      │
│  (超时仍返回成功，因为消息已持久化到 MQ/兜底表，不会丢)          │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│ 阶段五：AI 助手判断 (异步)                                       │
│                                                                  │
│  单聊: receiveUserId == 10000 (AI Bot) → 触发                   │
│  群聊: 消息内容包含 "@AI助手" → 触发                             │
│  → 投递到 AI_AGENT_REQUEST Topic                                 │
└──────────────────────────────────────────────────────────────────┘
```

### 4.2 消息异步落库

```
RocketMQ IM_CHAT:*
    │  selectorExpression = "*" (消费所有 Tag，包括各节点和 OFFLINE)
    │  consumerGroup = "im-message-store-group"
    │
    ▼
MessageStoreListener.onMessage()
    │
    │  1. 反序列化 GatewayPushPacket → 取 wsPayload
    │  2. 反序列化 AppMessage → 提取字段
    │  3. 构建 Message 实体 → INSERT im_message
    │
    │  幂等保障:
    │  → DuplicateKeyException (messageId 唯一索引) → 跳过
    │  → 失败抛异常 → MQ 重试 (max-reconsume-times=3)
    │
    ▼
MySQL im_message 表
```

面试要点：
- **为什么消息落库是异步的？** 如果同步落库，MySQL 写入延迟（~5ms）会阻塞消息发送的 HTTP 响应。异步落库让发送接口只等 MQ 投递成功（~1ms），MySQL 写入在消费者侧异步完成。
- **落库消费者为什么用 `*` 匹配所有 Tag？** 消息无论发给哪个节点（NODE_1、NODE_2、OFFLINE），都需要持久化到 MySQL。所以落库消费者订阅所有 Tag。
- **和 RTC 节点的消费者不冲突吗？** 不冲突。它们的 `consumerGroup` 不同（`im-message-store-group` vs `netty-push-group-NODE_1`），RocketMQ 会各自独立投递一份。

### 4.3 离线消息补齐

```
客户端 (重连/换设备)
    │
    │  1. GET /sessions/ackSeqMap
    │     → { "single_1_2": 50, "group_abc": 120, ... }
    │
    │  2. GET /maxSeq?sessionId=single_1_2
    │     → 73
    │
    │  3. GET /sync?sessionId=single_1_2&beginSeq=50&endSeq=73
    │     → [{seq:51, ...}, {seq:52, ...}, ..., {seq:73, ...}]
    │     (缺失的 seq 会返回 tombstone 标记)
    │
    │  4. 渲染完毕后: POST /ack?sessionId=single_1_2&seq=73
    │     → 更新 im_user_session.last_ack_seq = 73
    │
    ▼
下次重连时 beginSeq 从 73 开始
```

**seq 机制设计要点**：
- `im:seq:{sessionId}` 是 Redis INCR，单调递增，会话内全局有序
- 每条消息唯一对应一个 seq，客户端靠 seq 排序和去重
- `lastAckSeq` 记录客户端已确认到哪条消息，服务端不需要猜客户端看到了多少
- sync 接口限制单次最多拉 500 条，防止大范围查询打爆 MySQL

---

## 五、消息可靠性三层保障

```
┌─────────────────────────────────────────────────────────────┐
│                     可靠性保障体系                            │
│                                                              │
│  第一层: 幂等去重 (Exactly-Once 语义)                        │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ Redis SETNX "im:msg:dedup:{session}:{clientMsgId}"     ││
│  │ TTL = 5 分钟                                           ││
│  │ 客户端重试时，重复消息直接返回成功假象                  ││
│  └─────────────────────────────────────────────────────────┘│
│                                                              │
│  第二层: MQ 失败兜底 (本地消息表)                            │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ asyncSend 的 onException 回调:                         ││
│  │   → INSERT im_msg_failover (status=0, payload=JSON)    ││
│  │   → 仍然返回客户端成功 (消息已持久化到兜底表)          ││
│  │                                                        ││
│  │ MsgFailoverRetryTask (每 10 秒):                       ││
│  │   → Redisson 分布式锁 (单实例执行)                     ││
│  │   → 查 status=0 的记录 (limit 500)                     ││
│  │   → 重新查路由 + 投递 MQ                               ││
│  │   → 成功: status=1，失败: retryCount++                 ││
│  │   → retryCount >= 5: status=-1 (死信)                  ││
│  │   → 批处理超时 8 秒: 剩余留到下一轮                    ││
│  └─────────────────────────────────────────────────────────┘│
│                                                              │
│  第三层: 消费端幂等 (MessageStoreListener)                   │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ messageId 唯一索引 → DuplicateKeyException → 跳过      ││
│  │ 保证 MQ 重试/补偿重投不会重复落库                      ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## 六、群聊扩散写优化

```
群成员: [A, B, C, D, E]   发送者: A
路由:   B→NODE_1, C→NODE_1, D→NODE_2, E→OFFLINE

原始做法 (5 次 MQ):
  MQ → NODE_1 (for B)
  MQ → NODE_1 (for C)
  MQ → NODE_2 (for D)
  MQ → OFFLINE (for E)

优化后 (3 次 MQ):
  ┌───────────────────────────────────────────────────┐
  │ 1. MGET 批量查路由 (1 次 Redis 往返)              │
  │    ["im:route:B", "im:route:C", ...]              │
  │    → ["NODE_1", "NODE_1", "NODE_2", null]         │
  │                                                    │
  │ 2. 按节点分组                                      │
  │    NODE_1 → [B, C]                                 │
  │    NODE_2 → [D]                                    │
  │    OFFLINE → [E]                                   │
  │                                                    │
  │ 3. 每组打包一个 GatewayPushPacket                  │
  │    IM_CHAT:NODE_1 → {targetUserIds:[B,C], payload} │
  │    IM_CHAT:NODE_2 → {targetUserIds:[D], payload}   │
  │    IM_CHAT:OFFLINE → {targetUserIds:[E], payload}  │
  └───────────────────────────────────────────────────┘

优化效果:
  Redis: N 次 GET → 1 次 MGET
  MQ:    N 次投递 → M 次投递 (M = 存活节点数 + 1)
```

---

## 七、红包系统全链路

### 7.1 发红包

```
客户端
  │  POST /api/message/redPacket/send
  │  { sessionId, sessionType, body: { totalAmount, totalCount, redPacketType } }
  │
  ▼
@Transactional (单个 MySQL 事务)
┌──────────────────────────────────────────────────────────────┐
│ 1. 参数校验 (最小0.01, 单个最大200, 类型1普通/2拼手气)       │
│                                                              │
│ 2. 原子扣款                                                  │
│    UPDATE user_balance                                       │
│    SET balance = balance - #{amount}                         │
│    WHERE user_id = #{id} AND balance >= #{amount}            │
│    → 行锁 + 条件扣减，余额不足返回 0 行                     │
│                                                              │
│ 3. 创建红包记录 (im_red_packet)                              │
│                                                              │
│ 4. 写余额变更日志 (im_balance_log)                           │
└──────────────────────────────────────────────────────────────┘
    │
    ▼ 事务提交后
┌──────────────────────────────────────────────────────────────┐
│ 5. 复用 MessageService.sendMessage() 发送红包消息到 IM 通道  │
│    (type=5, body={redPacketId, wrapperText})                 │
│                                                              │
│ 6. 预计算金额 → Redis                                       │
│    普通红包: 平均分配 (最后一个拿余数)                       │
│    拼手气红包: 二倍均值法 + shuffle                          │
│    → RPUSH red_packet:amounts:{rpId} [金额列表]              │
│    → SET red_packet:count:{rpId} = totalCount                │
│                                                              │
│ 7. 延迟消息: 24 小时后投递 RED_PACKET_EXPIRE                │
│    → 到期触发过期退款                                        │
└──────────────────────────────────────────────────────────────┘
```

### 7.2 抢红包（事务消息 + Lua 原子操作）

```
客户端
  │  POST /api/message/redPacket/receive
  │  { redPacketId, userId }
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ 快速拦截: Redis GET red_packet:count:{rpId}                  │
│ → count <= 0 → 直接返回"已抢完"                              │
└──────────────┬───────────────────────────────────────────────┘
               │ count > 0
               ▼
┌──────────────────────────────────────────────────────────────┐
│ RocketMQ 事务消息: sendMessageInTransaction("IM_RED_PACKET") │
│                                                              │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ executeLocalTransaction (半消息发送后立即执行)            │ │
│ │                                                          │ │
│ │ 四合一 Lua 脚本 (原子操作):                              │ │
│ │ ┌──────────────────────────────────────────────────────┐ │ │
│ │ │ KEYS: [receivedSet, countKey, amountsKey, pendingKey]│ │ │
│ │ │ ARGV: [userId]                                      │ │ │
│ │ │                                                      │ │ │
│ │ │ 1. SISMEMBER receivedSet userId                      │ │ │
│ │ │    → 1: 已领过 → return "-1"                         │ │ │
│ │ │                                                      │ │ │
│ │ │ 2. GET countKey                                      │ │ │
│ │ │    → nil 或 0: 已抢完 → return "0"                   │ │ │
│ │ │                                                      │ │ │
│ │ │ 3. LPOP amountsKey                                   │ │ │
│ │ │    → 弹出预计算的金额                                │ │ │
│ │ │                                                      │ │ │
│ │ │ 4. DECR countKey                                     │ │ │
│ │ │ 5. SADD receivedSet userId                           │ │ │
│ │ │ 6. HSET pendingKey userId amount                     │ │ │
│ │ │    → return amount (抢到的金额)                      │ │ │
│ │ └──────────────────────────────────────────────────────┘ │ │
│ │                                                          │ │
│ │ Lua 结果:                                                │ │
│ │   "-1" → ROLLBACK, Future.complete(-1)                   │ │
│ │   "0"  → ROLLBACK, Future.complete(0)                    │ │
│ │   金额 → COMMIT,   Future.complete(amount)               │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                              │
│ checkLocalTransaction (Broker 回查):                         │
│   查 Pending Hash 是否存在该用户                             │
│   → 存在: COMMIT (Lua 成功但半消息未确认)                    │
│   → 不存在: ROLLBACK                                        │
└──────────────────────────────────────────────────────────────┘
    │
    │ COMMIT 的消息被 RedPacketReceiveListener 消费:
    ▼
┌──────────────────────────────────────────────────────────────┐
│ RedPacketReceiveListener.onMessage()                         │
│                                                              │
│ 1. Redis HGET pending:{rpId} userId → 拿到金额              │
│                                                              │
│ 2. @Transactional doSaveReceiveRecord():                     │
│    → DB 幂等查重 (countByRedPacketAndUser)                   │
│    → 行锁扣减红包库存 (deductRedPacketRowLock)               │
│    → 插入领取记录 (唯一索引兜底)                             │
│    → 原子加余额 (addBalanceAtomically)                       │
│    → 写资金流水                                              │
│                                                              │
│ 3. 事务提交后: HDEL pending:{rpId} userId                    │
└──────────────────────────────────────────────────────────────┘
```

面试要点：
- **为什么用事务消息而不是普通消息？** 抢红包的核心是"Lua 扣 Redis 库存"和"MQ 投递落库消息"必须保持一致。如果 Lua 成功但 MQ 失败，Redis 已扣但 DB 没落库；如果 MQ 成功但 Lua 失败，DB 会多落一条。事务消息保证：Lua 成功 → COMMIT → 消费者落库；Lua 失败 → ROLLBACK → 消息丢弃。
- **Pending Hash 的作用？** 它是 Lua 和落库消费者之间的桥梁。Lua 把金额写入 Pending Hash，消费者从 Pending Hash 读取金额后落库，落库成功后清除。Broker 回查时也靠 Pending Hash 判断本地事务是否成功。
- **为什么金额是预计算的？** 如果在 Lua 脚本里用二倍均值法即时计算，Lua 的 `math.random` 在 Redis Cluster 下行为不确定。预计算后存入 List，Lua 只做 `LPOP`，确定性 100%。

### 7.3 红包过期

```
双重保障:

1. 延迟消息 (主路径):
   发红包时 → syncSendDeliverTimeMills("RED_PACKET_EXPIRE", 24h)
   → RedPacketExpireListener 消费
   → casUpdateStatus(UNCLAIMED → EXPIRED)
   → 退款剩余金额 + 写流水

2. 定时扫描 (兜底):
   RedPacketExpireTask (每小时)
   → Redis SETNX 抢锁 (单实例执行)
   → 查 MySQL: status=UNCLAIMED AND createdAt < now()-24h
   → LIMIT 200 批处理
   → 复用 handleExpiredRedPacket()

CAS 幂等: casUpdateStatus 是 UPDATE ... WHERE status=0
   → 两条路径即使同时到达，只有一个能成功，另一个返回 0 行，跳过
```

---

## 八、Cache-Aside 缓存策略

```
读路径:
  Redis GET → 命中 → 返回
                 ↓ 未命中
  MySQL SELECT → 写回 Redis (TTL = 24~48h 随机，防雪崩)
                              → 返回

写路径 (以群成员变更为例):
  MySQL UPDATE/INSERT
  → Redis DEL 缓存 key (主动失效)
  → 下次读时自动回填

应用场景:
  用户信息:    im:cache:user:{userId}
  好友关系:    im:cache:friend:{a}:{b}
  会话信息:    im:cache:session:{sessionId}
  群成员列表:  im:cache:session:members:{sessionId}
```

面试要点：
- **为什么 TTL 随机？** 防止大量缓存同一时刻过期（缓存雪崩），24+random(24) 让过期时间分散在 24~48 小时内。
- **为什么群成员变更时主动删缓存？** 群成员列表变化频率低但一致性要求高（加群后必须立刻能收到群消息），所以写时主动失效，读时回填。

---

## 九、面试高频问题与回答思路

### Q1: 你的消息发送是同步还是异步？怎么保证不丢？

> 发送接口对客户端是"假同步"：HTTP 请求在 `CompletableFuture.get(3s)` 处等待 MQ 投递结果。MQ 投递成功立刻返回，不等 MySQL 落库。如果 MQ 投递失败，走 `onException` 回调写入本地消息表（`im_msg_failover`），仍然返回客户端成功——因为消息已持久化到兜底表。后台每 10 秒有补偿任务扫描兜底表重投 MQ。三层保障：客户端幂等去重 → MQ 失败本地兜底 → 消费端唯一索引。

### Q2: 群聊消息怎么发的？100 人群发 100 次 MQ？

> 不是。我做了扩散写优化：先用 Redis `MGET` 一次性批量查出所有群成员的路由，然后按 Netty 节点分组。假设 100 人分布在 3 个节点上，我只发 3+1 次 MQ（3 个节点各一次 + 1 次 OFFLINE）。每条 MQ 消息的 `targetUserIds` 是一个列表，Netty 消费者拿到后遍历推送。把 N 次 MQ 投递压缩到 M 次（M = 节点数），网络 IO 从 O(N) 降到 O(1)+O(M)。

### Q3: 抢红包的并发安全怎么保证？

> 分两层。第一层是 Redis Lua 脚本，原子完成"防重（SISMEMBER）+ 扣库存（DECR）+ 弹金额（LPOP）+ 写暂存（HSET）"四个操作，Redis 单线程保证串行。第二层是 RocketMQ 事务消息 + MySQL 行锁落库，保证 Redis 扣减和 DB 数据最终一致。金额是发红包时预计算好存到 Redis List 的，Lua 只做 LPOP，没有运行时随机数问题。

### Q4: 如果 Lua 扣了库存但 MQ 投递失败怎么办？

> 用的是 RocketMQ 事务消息。流程是：先发半消息（MQ 暂存不投递）→ 执行 Lua 脚本 → Lua 成功返回 COMMIT（MQ 正式投递）→ Lua 失败返回 ROLLBACK（MQ 丢弃半消息）。如果 COMMIT/ROLLBACK 信号丢了，Broker 会回查 `checkLocalTransaction`，此时检查 Redis Pending Hash 是否存在该用户的记录来决定补发 COMMIT 还是 ROLLBACK。

### Q5: 消息的有序性怎么保证？

> `asyncSendOrderly` + `hashKey=sessionId`。RocketMQ 的顺序消息机制会把同一个 hashKey 的消息路由到同一个 Queue，Queue 内的消息严格 FIFO。同一个会话的所有消息共用一个 sessionId 作为 hashKey，所以同会话内消息有序。不同会话之间不需要有序。

### Q6: 本地消息表的补偿任务怎么防重复执行？

> 两层防护。第一层，Redisson 分布式锁 `im:task:msg-failover-retry`，多实例部署时只有一个实例能拿到锁执行。第二层，每条兜底记录有 `retryCount`，超过 5 次标记为死信（status=-1）不再重试。还有批处理超时保护——单轮最多处理 8 秒，超时就放下剩余记录留给下一轮。

### Q7: 红包金额的二倍均值法是什么？

> 每次从剩余金额里随机取一个 `[0.01, 剩余均值×2]` 范围内的值。比如剩 10 元 5 个人，均值是 2 元，第一个人最多抢到 4 元。这样既保证每个人都能抢到 ≥ 0.01 元，又保证期望值等于均值（公平性）。金额列表生成后做一次 `shuffle` 打乱顺序，让先抢和后抢没有系统性偏差。最后一个人拿 `remaining`（剩余总额），处理精度累积误差。

---

## 十、技术栈速查

| 技术 | 用途 |
|------|------|
| Spring Boot 2.6.13 | 基础框架 |
| RocketMQ | 消息投递、事务消息、延迟消息 |
| Redis | seq 发号、幂等去重、缓存、红包库存、Lua 脚本 |
| Redisson | 分布式锁（补偿任务） |
| MySQL + MyBatis-Plus | 消息落库、红包、会话、余额 |
| Nacos | 服务注册发现 |
| 雪花算法 | messageId、红包 ID 等分布式 ID |
| Fastjson + Hutool | JSON 序列化、工具库 |
