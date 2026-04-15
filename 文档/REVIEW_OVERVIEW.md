# InfiniteChat — 全局架构复盘

## 一、系统全景

InfiniteChat 是一个基于微服务架构的即时通信系统，包含 7 个服务、5 种中间件，覆盖了 IM 系统的核心链路：认证、网关、联系人、消息引擎、实时推送、离线兜底、朋友圈。

```
                              ┌─────────────────────┐
                              │      客户端 App      │
                              └──────┬───────┬──────┘
                              HTTP REST │     │ WebSocket
                                       │     │
                    ┌──────────────────▼─────▼──────────────────┐
                    │              Gateway (8080)                │
                    │  RateLimitFilter → AuthorizeFilter         │
                    │  IP限流 → JWT校验 → 登出时间戳比对         │
                    │  路由分发 → 各微服务                       │
                    └──┬────┬────┬────┬────┬────┬───────────────┘
                       │    │    │    │    │    │
          ┌────────────┘    │    │    │    │    └────────────┐
          │                 │    │    │    │                 │
          ▼                 ▼    │    ▼    ▼                 ▼
   ┌────────────┐  ┌────────┐   │ ┌──────────┐  ┌────────────────┐
   │ Auth(8081) │  │Contact │   │ │ Offline  │  │  Moment(8085)  │
   │ 认证服务   │  │(8086)  │   │ │ (8087)   │  │  朋友圈        │
   │ 注册/登录  │  │联系人  │   │ │ 离线消息 │  └────────────────┘
   │ JWT签发    │  │好友关系│   │ │ 未读数   │
   └─────┬──────┘  └───┬────┘   │ └────┬─────┘
         │             │        │      │
         │  MQ:        │ Feign  │      │ MQ:OFFLINE
         │  USER_EVENT │   │    │      │
         │  :REGISTER  │   │    │      │
         └──────┬──────┘   │    │      │
                │          ▼    │      │
                │   ┌───────────▼──────▼──────────────────────┐
                └──▶│          Messaging (8082)                │
                    │  消息引擎 · 红包 · 会话管理               │
                    │  雪花ID · 消息可靠性 · 事务消息           │
                    └─────────────────┬────────────────────────┘
                                     │
                          MQ: IM_CHAT / IM_NOTIFY
                          按路由节点Tag投递
                                     │
                                     ▼
                    ┌──────────────────────────────────────────┐
                    │        RTC — RealTimeCommunication        │
                    │  Netty WebSocket (9090) + HTTP (8083)     │
                    │  长连接管理 · 消息推送 · 心跳 · 踢人      │
                    └──────────────────────────────────────────┘
```

---

## 二、请求生命周期：一条消息从发出到接收

这是理解整个系统最重要的一张图——一条消息的完整旅程，穿越 5 个服务、3 种中间件。

```
用户A (发送者)                                              用户B (接收者)
    │                                                           ▲
    │ HTTP POST                                                 │ WebSocket Push
    │ /api/message/send                                         │
    ▼                                                           │
┌─────────┐                                                     │
│ Gateway │  ① IP限流 (Redisson RateLimiter)                    │
│         │  ② JWT校验 (HS512签名 + 登出时间戳)                  │
│         │  ③ 注入 X-User-Id 到 Header                         │
│         │  ④ 路由到 Messaging                                  │
└────┬────┘                                                     │
     │                                                          │
     ▼                                                          │
┌──────────┐                                                    │
│Messaging │  ⑤ 幂等校验 (clientMsgId 去重, Redis SETNX)        │
│          │  ⑥ 生成雪花ID (messageId) + 递增seq                 │
│          │  ⑦ 查路由: Redis im:route:{B} → 得到 RTC_NODE_1    │
│          │  ⑧ RocketMQ asyncSendOrderly                       │
│          │     topic=IM_CHAT, tag=NODE_1                      │
│          │     hashKey=sessionId (保序)                        │
│          │  ⑨ 异步持久化: MQ → MessageStoreListener → MySQL   │
│          │  ⑩ 返回 ACK 给用户A                                 │
└────┬─────┘                                                    │
     │ RocketMQ                                                 │
     ▼                                                          │
┌──────────┐                                                    │
│   RTC    │  ⑪ NODE_1 消费 IM_CHAT:NODE_1                     │
│ NODE_1   │  ⑫ 查本地 Channel: B 是否在线                      │
│          │     ├─ 在线 → ⑬ WebSocket 推送 ─────────────────▶ │
│          │     └─ 不在线 → ⑭ 回流 MQ                          │
│          │        topic=IM_CHAT, tag=OFFLINE                  │
└────┬─────┘                                                    │
     │ (不在线时)                                                │
     ▼                                                          │
┌──────────┐                                                    │
│ Offline  │  ⑮ 消费 IM_CHAT:OFFLINE                           │
│          │  ⑯ Redis ZSet 热存储 (3天)                         │
│          │  ⑰ MySQL 冷存储 (7天)                              │
│          │  ⑱ 未读数 HINCRBY +1                               │
│          │  ──────────────────────────────                     │
│          │  用户B 上线后:                                      │
│          │  ⑲ 拉未读数 → 拉离线消息 → 标记已读                │
└──────────┘
```

### 关键决策点总结

| 步骤 | 设计决策 | 为什么 |
|------|----------|--------|
| ①② | 限流在鉴权之前 | 防止无效/恶意请求消耗 JWT 解析资源 |
| ⑤ | clientMsgId 幂等 | 客户端重试不会产生重复消息 |
| ⑥ | 雪花 ID + 会话级 seq | 全局唯一 + 会话内有序 |
| ⑧ | MQ 有序消息 (hashKey=sessionId) | 同一会话的消息在同一队列，保证消费顺序 |
| ⑨ | 异步持久化 | 发送链路不等 MySQL 写入，降低延迟 |
| ⑫⑭ | 推送失败回流 OFFLINE | 消息不丢失，离线用户上线后可拉取 |
| ⑯⑰ | 热冷分层 | Redis 极速拉取 + MySQL 长期兜底 |

---

## 三、中间件全景

### 3.1 Redis — 系统的"瑞士军刀"

Redis 在系统中扮演了至少 8 种角色，是使用最重、覆盖最广的中间件。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Redis 使用全景                                    │
│                                                                             │
│  ┌─ 认证 / 网关 ────────────────────────────────────────────────────────┐  │
│  │  jwt:logout:{userId}            String    登出时间戳                  │  │
│  │  refresh_token:{userId}         String    RefreshToken               │  │
│  │  rate_limit:ip:{ip}             Redisson  RateLimiter 限流           │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌─ 消息引擎 (Messaging) ──────────────────────────────────────────────┐  │
│  │  im:route:{userId}             String    用户→RTC节点路由            │  │
│  │  im:seq:{sessionId}            String    会话级递增序列号            │  │
│  │  im:dedup:{clientMsgId}        String    消息幂等去重 (SETNX)       │  │
│  │  red_packet:{id}               Hash      红包金额/状态              │  │
│  │  red_packet:amounts:{id}       List      预拆分金额队列             │  │
│  │  red_packet:received:{id}      Set       已抢用户集合               │  │
│  │  im:cache:user:{userId}        String    用户信息缓存               │  │
│  │  im:cache:friends:{userId}     String    好友列表缓存               │  │
│  │  im:cache:session:{sessionId}  String    会话信息缓存               │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌─ 联系人 (Contact) ─────────────────────────────────────────────────┐   │
│  │  contact:list:{userId}[:{type}] String   联系人列表缓存             │   │
│  │  lock:contact:{min}:{max}       RLock    好友关系分布式锁           │   │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌─ RTC ──────────────────────────────────────────────────────────────┐   │
│  │  im:route:{userId}             String    写入: 连接时注册路由       │   │
│  │  im:offline:sessions           Set       离线用户集合               │   │
│  │  im:kickout:{userId}           Pub/Sub   多节点踢人广播            │   │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌─ 离线服务 (Offline) ──────────────────────────────────────────────┐   │
│  │  im:offline:{userId}:{session} ZSet      离线消息 (score=seq)       │   │
│  │  im:offline:sessions:{userId}  Set       有离线消息的会话集合       │   │
│  │  im:unread:{userId}            Hash      未读计数 (field=sessionId) │   │
│  │  im:offline:notify:{userId}    List      离线通知                   │   │
│  │  im:pull:limit:{userId}        String    拉取限流 (10次/秒)        │   │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌─ 朋友圈 (Moment) ────────────────────────────────────────────────┐   │
│  │  moment:like:{momentId}        Set       点赞用户集合              │   │
│  │  moment:like:count:{momentId}  String    点赞计数器                │   │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 RocketMQ — 异步解耦的核心

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        RocketMQ Topic / Tag 全景                         │
│                                                                          │
│  Topic: IM_CHAT (聊天消息)                                               │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Tag = NODE_x     Messaging → RTC(NODE_x)     有序消息          │   │
│  │                   按路由节点分发，同会话保序                      │   │
│  │                                                                  │   │
│  │  Tag = OFFLINE    RTC → Offline              推送失败回流        │   │
│  │                   用户不在线时存储离线消息                        │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  Topic: IM_NOTIFY (系统通知)                                             │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Tag = NODE_x     Messaging → RTC(NODE_x)     通知推送          │   │
│  │  Tag = OFFLINE    RTC → Offline              离线通知            │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  Topic: IM_STORE (消息持久化)                                            │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Messaging → Messaging(MessageStoreListener)                     │   │
│  │  异步写 MySQL，发送链路不等待持久化                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  Topic: IM_RED_PACKET (红包)                                             │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  事务消息: 发红包 (扣余额 + 创建红包原子性)                      │   │
│  │  普通消息: 抢红包到账                                             │   │
│  │  延迟消息: 红包过期退款 (24h)                                     │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  Topic: USER_EVENT (用户事件)                                            │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Tag = REGISTER   Auth → Contact                                │   │
│  │                   注册后自动创建 AI 助手                          │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.3 MySQL — 数据真相源

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        MySQL 表结构全景                                   │
│                                                                          │
│  Auth 库:                                                                │
│    user               用户表 (账号, 密码BCrypt, 头像, 状态)              │
│                                                                          │
│  Messaging 库:                                                           │
│    im_message         消息主表 (messageId, sessionId, seq, content...)   │
│    im_session         会话表 (单聊/群聊)                                 │
│    im_msg_failover    消息投递失败本地表 (可靠性兜底)                     │
│    red_packet         红包表                                             │
│    red_packet_receive 红包领取记录                                        │
│    user_balance       用户余额                                           │
│    balance_log        余额变动流水                                        │
│                                                                          │
│  Contact 库:                                                             │
│    contact            好友关系 (双向记录, status: 正常/删除/拉黑)        │
│    contact_request    好友申请                                           │
│    ai_assistant       AI 助手配置                                        │
│                                                                          │
│  Offline 库:                                                             │
│    offline_message    离线消息冷存储 (7天, 按 expire_at 清理)            │
│    unread_count       未读计数持久化 (Redis 兜底)                        │
│                                                                          │
│  Moment 库:                                                              │
│    moment             朋友圈动态                                         │
│    moment_like        点赞记录 (唯一索引 moment_id+user_id)              │
│    moment_comment     评论 (支持 reply_to_user_id 回复)                  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.4 Nacos + Netty

```
Nacos:  所有 7 个服务注册发现, Gateway 路由, Feign 服务间调用
Netty:  RTC 模块的 WebSocket 服务器 (端口 9090)
        Boss(1) + Worker(CPU核数) + Business(200) 线程模型
        Pipeline: IdleState → HTTP → WS握手 → Token鉴权 → 业务Handler
```

---

## 四、六大架构模式

整个系统反复使用了以下设计模式，面试时可以跨模块举例说明。

### 4.1 异步解耦 (RocketMQ)

```
同步链路 (用户等待):  发消息 → 生成ID → 查路由 → 投MQ → 返回ACK
                     ↑ 整条链路不碰MySQL ↑

异步链路 (用户无感):  MQ → 持久化MySQL     (IM_STORE)
                     MQ → 推送WebSocket  (IM_CHAT:NODE_x)
                     MQ → 存离线消息      (IM_CHAT:OFFLINE)
```

核心思想：**发送链路极致轻量**，把所有重操作（MySQL 写入、网络推送、离线存储）异步化。用户感知的发送延迟 = 生成 ID + 投 MQ ≈ 个位数毫秒。

### 4.2 热冷分层存储

```
         热 (Redis)                    冷 (MySQL)
┌────────────────────────┐    ┌─────────────────────────┐
│ 离线消息 ZSet (3天)    │    │ offline_message (7天)   │
│ 未读计数 Hash          │    │ unread_count            │
│ 点赞集合 Set (24h)     │    │ moment_like             │
│ 联系人列表 String (1h) │    │ contact                 │
│ 用户/会话缓存          │    │ user / im_session       │
└────────────────────────┘    └─────────────────────────┘
        读写极速                   数据不丢失
     适合高频操作               适合长期存储
```

通用策略：Redis 优先读 → miss 降级 MySQL → 回填 Redis。写入时双写或 Cache-Aside 失效。

### 4.3 消息可靠性三层保障

```
第一层: 客户端幂等
  clientMsgId (UUID) → Redis SETNX → 重复消息直接丢弃

第二层: 服务端本地消息表
  MQ 投递失败 → 写入 im_msg_failover → 定时任务重试

第三层: 消费端幂等
  MySQL 唯一索引 (message_id) → INSERT 冲突则跳过
```

### 4.4 分布式锁

```
场景                              锁 Key                           中间件
同意好友 (双向插入)               lock:contact:{min}:{max}         Redisson RLock
红包过期/退款                     lock:red_packet:expire           Redis SETNX
消息投递失败重试                  lock:msg:failover:retry          Redis SETNX
离线消息清理                      im:task:offline-message-clean    Redis SETNX
```

### 4.5 事件驱动

```
用户注册 ──MQ──▶ 创建AI助手 (Contact)
发消息   ──MQ──▶ 异步存MySQL (Messaging)
         ──MQ──▶ 推送WebSocket (RTC)
         ──MQ──▶ 存离线消息 (Offline)
推送失败 ──MQ──▶ 回流离线 (RTC → Offline)
发红包   ──MQ事务──▶ 扣余额+创建红包 (原子性)
抢红包   ──MQ──▶ 余额到账
```

### 4.6 Feign 降级容错

```
Contact ──Feign──▶ Messaging (创建会话)     失败 → 打日志, 不影响好友关系
Contact ──Feign──▶ RTC (推送通知)           失败 → 打日志, 不影响好友关系
Moment  ──Feign──▶ RTC (推送点赞/评论通知)  失败 → 打日志, 不影响点赞/评论
```

原则：**核心操作 (好友关系/点赞) 不因附属操作 (通知推送) 的失败而回滚**。

---

## 五、跨模块数据流全景

### 5.1 用户注册 → 登录 → 加好友 → 发消息 → 对方收到

```
时间轴 ──────────────────────────────────────────────────────────────────▶

① 注册
   Auth: 写MySQL user表 → 签发JWT → MQ(USER_EVENT:REGISTER)
   Contact: 消费MQ → 创建AI助手 → 写 ai_assistant + contact

② 登录
   Auth: BCrypt验密 → 签发AccessToken(2h) + RefreshToken(7d)
   Auth: 分配RTC节点 (一致性哈希) → Redis im:route:{userId}
   Gateway: 后续请求 JWT校验 + 登出时间戳比对

③ 加好友
   Contact: A申请 → 写contact_request → Feign→RTC推送给B
   Contact: B同意 → Redisson锁 → 双向写contact → 清缓存
            → Feign→Messaging创建单聊会话
            → Feign→RTC推送新会话通知

④ 发消息
   Messaging: 幂等校验 → 雪花ID → seq → MQ(IM_CHAT:NODE_x)
   Messaging: 异步 MQ(IM_STORE) → MySQL持久化

⑤ 对方在线 → 收到
   RTC: 消费IM_CHAT:NODE_x → 查本地Channel → WebSocket推送

⑤' 对方不在线 → 离线存储
   RTC: 推送失败 → MQ(IM_CHAT:OFFLINE)
   Offline: 消费 → Redis ZSet + MySQL + 未读数+1
   对方上线 → 拉未读数 → 拉离线消息 → 标记已读
```

### 5.2 红包：从发到抢到过期

```
① 发红包
   Messaging: RocketMQ 事务消息
     半消息 → executeLocalTransaction:
       扣余额 (MySQL行锁) + 创建红包 + 预拆分金额写Redis List
     → COMMIT
   Messaging: 投IM_CHAT消息 (红包类型) → RTC推送给群/好友

② 抢红包
   Messaging: Redis Lua脚本 (原子操作):
     SISMEMBER检查是否已抢 → LPOP弹金额 → SADD记录已抢
   → MQ → 余额到账 → 写领取记录

③ 过期退款 (24h)
   Messaging: 延迟消息 → RedPacketExpireListener:
     分布式锁 → 读Redis剩余金额 → 退回发送者余额
```

---

## 六、Netty 与 WebSocket 长连接

RTC 是整个系统唯一的长连接入口，也是面试的绝对高频考点。

```
┌──────────────────────────────────────────────────────────────────┐
│                     RTC Netty Pipeline                            │
│                                                                  │
│ Boss EventLoopGroup (1 线程) ← 接收新连接                        │
│   │                                                              │
│   ▼                                                              │
│ Worker EventLoopGroup (CPU核数) ← IO 读写                       │
│   │                                                              │
│   ▼ Channel Pipeline:                                            │
│   ┌────────────────────────────────────────────────────────────┐ │
│   │ IdleStateHandler (读空闲90s)                               │ │
│   │   → 90s无心跳 → 触发userEventTriggered → 关闭连接          │ │
│   │                                                            │ │
│   │ HttpServerCodec + HttpObjectAggregator                     │ │
│   │   → HTTP 解码 (用于 WS 握手)                               │ │
│   │                                                            │ │
│   │ WebSocketTokenAuthHandler (自定义, 一次性)                  │ │
│   │   → URL参数提取token → JWT校验 → 写Channel.attr            │ │
│   │   → Redis注册路由 im:route:{userId} = 当前节点             │ │
│   │   → 广播踢人 (如果有旧连接)                                │ │
│   │   → 认证完成后 remove(this), 不再执行                      │ │
│   │                                                            │ │
│   │ WebSocketServerProtocolHandler                             │ │
│   │   → WS 握手升级                                           │ │
│   │                                                            │ │
│   │ MessageInboundHandler → Business EventLoopGroup (200线程)  │ │
│   │   → 心跳: 收到ping → 回pong                               │ │
│   │   → 消息: 转发给业务处理                                   │ │
│   └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│ 连接断开 → channelInactive:                                      │
│   清除 Redis 路由 → 标记离线                                     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 七、服务间通信方式总结

```
┌───────────────┬───────────────────────┬──────────────────────────────┐
│   通信方式     │     使用场景          │       特点                    │
├───────────────┼───────────────────────┼──────────────────────────────┤
│ RocketMQ      │ 消息投递/持久化/离线  │ 异步解耦, 有序, 事务消息     │
│ (异步)        │ 红包, 用户注册事件    │ 高吞吐, 削峰填谷             │
├───────────────┼───────────────────────┼──────────────────────────────┤
│ Feign         │ 创建会话, 推送通知    │ 同步调用, 有降级兜底         │
│ (同步)        │ 朋友圈通知            │ 实现简单, 适合轻量调用       │
├───────────────┼───────────────────────┼──────────────────────────────┤
│ Redis Pub/Sub │ 多节点踢人广播        │ 广播语义, fire-and-forget    │
│ (广播)        │                       │ 不持久化, 不保证送达         │
├───────────────┼───────────────────────┼──────────────────────────────┤
│ WebSocket     │ 客户端-RTC 长连接     │ 全双工, 低延迟实时推送       │
│ (长连接)      │ 消息/通知/心跳        │ Netty 实现                   │
└───────────────┴───────────────────────┴──────────────────────────────┘
```

---

## 八、安全与限流

```
┌──────────────────────────────────────────────────────────────────┐
│                         安全防线                                  │
│                                                                  │
│  第1层: Gateway RateLimitFilter (order=-2)                       │
│    → IP维度限流, 所有请求先过限流                                │
│    → 防止 DDoS / 暴力破解                                       │
│                                                                  │
│  第2层: Gateway AuthorizeFilter (order=-1)                       │
│    → JWT 签名校验 (HS512)                                       │
│    → 登出时间戳校验: token.iat < logoutTime → 拒绝              │
│    → 白名单: 注册/登录/刷新Token 免鉴权                         │
│                                                                  │
│  第3层: RTC WebSocket Token鉴权                                  │
│    → WS连接时 URL 参数携带 token                                │
│    → JWT 校验 → Channel 绑定 userId                             │
│                                                                  │
│  第4层: 业务级幂等                                               │
│    → clientMsgId 消息去重 (Messaging)                            │
│    → Redis Set 点赞去重 (Moment)                                 │
│    → MySQL 唯一索引兜底 (全局)                                   │
│                                                                  │
│  第5层: Offline 拉取限流                                         │
│    → 10次/秒/用户, 防止客户端轮询风暴                            │
└──────────────────────────────────────────────────────────────────┘
```

---

## 九、高频面试题 Top 10（全局视角）

### Q1: 请用一句话概括你的 IM 项目架构。

> 基于 Spring Cloud + RocketMQ + Netty 的微服务 IM 系统。消息通过 RocketMQ 按路由节点有序投递到 Netty WebSocket 层推送，推送失败回流离线服务做热冷分层存储，整体实现了消息从发送到接收的端到端可靠投递。

### Q2: 为什么消息发送不直接写 MySQL？

> 消息发送是高频、延迟敏感的操作。如果同步写 MySQL，用户等待时间 = 序列号生成 + MySQL 写入 + MQ 投递 ≈ 几十毫秒。通过 MQ 异步持久化，发送链路只做 ID 生成 + MQ 投递 ≈ 个位数毫秒。持久化在后台异步完成，即使有短暂延迟也不影响用户体验。

### Q3: 消息怎么保证不丢不重？

> 三层保障：客户端用 clientMsgId (UUID) + Redis SETNX 做发送端幂等；服务端有本地消息表 (im_msg_failover)，MQ 投递失败会写入本地表并定时重试；消费端通过 MySQL 唯一索引保证幂等。整体是"至少一次投递 + 消费端去重"的经典模式。

### Q4: 你的系统怎么处理用户不在线的情况？

> RTC 节点推送消息时发现用户 Channel 不存在，就把消息回流到 RocketMQ 的 OFFLINE tag。OfflineService 消费后双写 Redis ZSet (热, 3天) 和 MySQL (冷, 7天)，同时未读计数 +1。用户上线后先拉未读数显示小红点，再按需拉取各会话的离线消息。

### Q5: 多个 RTC 节点怎么知道用户在哪个节点？

> 用户通过 WebSocket 连接到某个 RTC 节点时，该节点在 Redis 写入路由信息 `im:route:{userId} = NODE_x`。Messaging 发消息时查这个 key，得到目标节点后用 MQ tag 定向投递。断连时清除路由。如果用户换设备登录，新连接会覆盖路由并通过 Redis Pub/Sub 广播踢掉旧连接。

### Q6: JWT 登出后旧 Token 怎么失效？

> 登出时在 Redis 记录 `jwt:logout:{userId} = 登出时间戳`。Gateway 鉴权时比较 token 的签发时间 (iat) 和登出时间：`iat < logoutTime` 则拒绝。这比逐个 token 拉黑更节省内存——一个 key 就能让该用户登出前的所有 token 失效。

### Q7: 红包怎么保证不超发？

> 发红包时预拆分金额写入 Redis List。抢红包时通过 Lua 脚本原子操作：检查是否已抢 (SISMEMBER) → 弹出金额 (LPOP) → 记录已抢 (SADD)。三步在一个 Lua 脚本中执行，Redis 单线程保证原子性。数据库层面通过事务消息保证扣余额和创建红包的一致性。

### Q8: 为什么选 RocketMQ 而不是 Kafka？

> RocketMQ 支持有序消息（同一会话的消息保序）、事务消息（红包扣款原子性）、延迟消息（红包过期退款）、Tag 路由（按 RTC 节点分发）。Kafka 在事务消息和延迟消息上支持较弱，且 Tag 路由需要自己实现。IM 场景对这些特性需求很强，RocketMQ 是更自然的选择。

### Q9: 系统的读写比是怎样的？哪些地方是热点？

> IM 系统是典型的"写多读少但读延迟敏感"。热点包括：消息发送（写 MQ）、在线推送（写 WebSocket）、离线拉取（读 Redis ZSet）、点赞（写 Redis Set）。通过 MQ 异步削峰、Redis 热数据加速、MySQL 冷数据兜底的组合，在写吞吐和读延迟之间取得平衡。

### Q10: 如果让你重新设计，你会改什么？

> 几个可以优化的点：(1) 消息存储可以引入时序数据库或分库分表，当前单表 im_message 在海量数据下会成为瓶颈；(2) 好友动态流可以对大 V 用户做推拉结合（小粉丝量推模型，大粉丝量拉模型）；(3) RTC 节点分配可以引入 ZooKeeper 临时节点做更可靠的存活检测，取代 Redis 路由的手动清理；(4) 雪花 ID 的 workerId 分配在 K8s 环境下应该改为注册中心动态分配。

---

## 十、技术栈速查

| 技术 | 版本 | 角色 |
|------|------|------|
| Spring Boot | 2.6.13 | 全局基础框架 |
| Spring Cloud Gateway | — | API 网关, 限流, 鉴权 |
| Spring Cloud OpenFeign | — | 服务间同步调用 + 降级 |
| Nacos | — | 服务注册发现 + 配置中心 |
| RocketMQ | 2.2.3 | 异步消息: 有序/事务/延迟 |
| Netty | — | WebSocket 长连接服务器 |
| Redis | — | 缓存/限流/路由/锁/离线存储 |
| Redisson | — | 分布式锁 / RateLimiter |
| MySQL | — | 数据持久化 (真相源) |
| MyBatis-Plus | 3.5.2 | ORM |
| JWT (jjwt) | — | 认证 Token (HS512) |
| MinIO | — | 对象存储 (头像等) |

---

## 十一、端口与服务名

| 服务 | 端口 | Nacos 服务名 |
|------|------|-------------|
| Gateway | 8080 | Gateway |
| AuthenticationService | 8081 | AuthenticationService |
| Messaging | 8082 | MessagingService |
| RTC (HTTP) | 8083 | RealTimeCommunicationService |
| RTC (WebSocket) | 9090 | — |
| Moment | 8085 | moment-service |
| Contact | 8086 | ContactService |
| OfflineService | 8087 | OfflineService |
