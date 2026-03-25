# InfiniteChat — RealTimeCommunication (RTC) 模块复盘

## 一、模块定位

RTC 模块是整个 IM 系统的**消息推送引擎**：维护客户端 WebSocket 长连接，接收上游服务（Messaging、Auth）通过 RocketMQ 投递的消息，精准推送到对应用户的 Channel。它本身**不产生消息、不存储消息**，只负责"最后一公里"的实时投递。

```
                    其他微服务 (Messaging / Auth)
                              │
                    RocketMQ (IM_CHAT / IM_NOTIFY)
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│             RealTimeCommunication (RTC) 模块                     │
│                                                                  │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
│  │  Netty      │  │  RocketMQ    │  │  Redis                   │ │
│  │  WebSocket  │  │  Listener    │  │  路由 + Pub/Sub 踢人     │ │
│  │  Server     │  │  消费 → 推送 │  │  im:route:{userId}       │ │
│  │  (9101)     │  │              │  │                          │ │
│  └────────────┘  └──────────────┘  └──────────────────────────┘ │
│                                                                  │
│  HTTP 端口: 8083 (供内部微服务调用 Push 接口)                      │
│  WS 端口:   9101 (供客户端 WebSocket 接入)                        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、整体架构

### 2.1 双端口双协议

| 端口 | 协议 | 用途 | 调用方 |
|------|------|------|--------|
| 8083 | HTTP (Spring MVC) | PushController：接收其他微服务的推送请求 | Messaging、Auth 等内部服务 |
| 9101 | WebSocket (Netty) | 客户端长连接入口，维持心跳、接收推送 | 移动端 / Web 端 |

两个端口在同一个 JVM 里共存：Spring Boot 启动 Tomcat 监听 8083，同时 `@PostConstruct` 在独立线程中启动 Netty 监听 9101。

### 2.2 核心组件关系

```
┌──────────────────────────────────────────────────────────────────────┐
│                           RTC 模块内部                               │
│                                                                      │
│  ┌──────────────────────────────────────┐                           │
│  │         Netty Server (9101)          │                           │
│  │                                      │                           │
│  │  Pipeline:                           │                           │
│  │  ┌─────────────────────────────────┐ │                           │
│  │  │ IdleStateHandler (5分钟读空闲)  │ │                           │
│  │  ├─────────────────────────────────┤ │                           │
│  │  │ HttpServerCodec                 │ │  HTTP 升级 WebSocket      │
│  │  ├─────────────────────────────────┤ │                           │
│  │  │ ChunkedWriteHandler             │ │                           │
│  │  ├─────────────────────────────────┤ │                           │
│  │  │ HttpObjectAggregator (64KB)     │ │                           │
│  │  ├─────────────────────────────────┤ │                           │
│  │  │ WebSocketTokenAuthHandler       │◀── JWT 鉴权 (仅握手时)     │
│  │  ├─────────────────────────────────┤ │                           │
│  │  │ WebSocketServerProtocolHandler  │ │  Netty 原生 WS 协议处理  │
│  │  ├─────────────────────────────────┤ │                           │
│  │  │ MessageInboundHandler           │◀── 业务逻辑 (心跳/ACK/登出) │
│  │  │ (businessGroup 线程池)          │ │                           │
│  │  └─────────────────────────────────┘ │                           │
│  └──────────────────────────────────────┘                           │
│                     │                                                │
│                     ▼                                                │
│  ┌──────────────────────────────────────┐                           │
│  │       NettySessionManager            │                           │
│  │  ConcurrentMap<userId, Channel>      │  ← 本节点的用户连接映射   │
│  └──────────────────────────────────────┘                           │
│                     ▲                                                │
│                     │ 查 Channel → writeAndFlush                    │
│  ┌──────────────────┴───────────────────┐                           │
│  │       RocketMQ Listeners             │                           │
│  │                                      │                           │
│  │  NettyPushMessageListener (IM_CHAT)  │  ← 聊天消息推送           │
│  │  NotifyPushMessageListener(IM_NOTIFY)│  ← 系统通知推送           │
│  └──────────────────────────────────────┘                           │
│                                                                      │
│  ┌──────────────────────────────────────┐                           │
│  │  UserKickoutMessageListener          │                           │
│  │  (Redis Pub/Sub: userLogout)         │  ← 跨节点踢人             │
│  └──────────────────────────────────────┘                           │
│                                                                      │
│  ┌──────────────────────────────────────┐                           │
│  │  PushController (HTTP 8083)          │                           │
│  │  + NettyMessageService               │  ← 内部微服务调用入口     │
│  │  (查路由 → 投递 RocketMQ)            │                           │
│  └──────────────────────────────────────┘                           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 三、中间件一览

```
┌─────────────────────────────────────────────────────────┐
│                    Redis (端口 6399)                      │
│                                                          │
│  im:route:{userId}   ← 节点 ID (TTL 20分钟, 心跳续期)   │
│  userLogout (channel) ← Pub/Sub 跨节点踢人              │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│              RocketMQ (NameServer 9876)                   │
│                                                          │
│  Topic: IM_CHAT                                          │
│    Tag = NODE_1 / NODE_2 / ...  → 聊天消息定向推送       │
│    Tag = OFFLINE                → 离线消息回流            │
│                                                          │
│  Topic: IM_NOTIFY                                        │
│    Tag = NODE_1 / NODE_2 / ...  → 系统通知定向推送       │
│    Tag = OFFLINE                → 离线通知回流            │
└─────────────────────────────────────────────────────────┘

┌──────────────────────────────┐
│     Nacos (端口 18375)        │
│                              │
│  注册:                        │
│    RealTimeCommunicationSvc   │
│    HTTP 8083 (供服务发现)     │
└──────────────────────────────┘
```

### Redis 在 RTC 模块中的用法

| Key / Channel | 数据结构 | 写入方 | 读取方 | 用途 |
|---------------|----------|--------|--------|------|
| `im:route:{userId}` | String (节点 ID，如 `NODE_1`) | 握手时 Lua 原子写入 | NettyMessageService 查路由 | 全局路由表：知道用户连在哪个节点 |
| `userLogout` | Pub/Sub Channel | Auth 登出 / RTC 踢人 | UserKickoutMessageListener | 跨节点踢断 WebSocket 连接 |

### RocketMQ Topic 设计

```
                    Messaging Service
                         │
                    IM_CHAT:NODE_1  ──────▶  RTC 节点 1 (NettyPushMessageListener)
                    IM_CHAT:NODE_2  ──────▶  RTC 节点 2 (NettyPushMessageListener)
                    IM_CHAT:OFFLINE ──────▶  OfflineService (离线存储)
                         │
                    ─────┴─────
                         │
                    IM_NOTIFY:NODE_1 ─────▶  RTC 节点 1 (NotifyPushMessageListener)
                    IM_NOTIFY:NODE_2 ─────▶  RTC 节点 2 (NotifyPushMessageListener)
                    IM_NOTIFY:OFFLINE ────▶  OfflineService (离线存储)
```

核心思想：**Tag 路由**。每个 RTC 节点只消费属于自己的 Tag（`selectorExpression = "${im.node.id}"`），避免消息广播到所有节点。Messaging 发消息时先查 Redis 路由得到目标节点 ID，再作为 Tag 投递到 RocketMQ。

---

## 四、核心流程详解

### 4.1 WebSocket 连接建立

```
客户端
  │  ws://nettyHost:9101/api/v1/chat/message?token=eyJ...
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ 1. IdleStateHandler                                          │
│    注册 5 分钟读空闲检测                                      │
│                                                              │
│ 2. HttpServerCodec → HttpObjectAggregator                    │
│    解析 HTTP 升级请求                                         │
│                                                              │
│ 3. WebSocketTokenAuthHandler                                 │
│    ┌────────────────────────────────────────────────────────┐│
│    │ 从 URL 参数提取 token                                  ││
│    │ JwtUtil.parse(token) → HS512 验签                      ││
│    │ 鉴权通过 → channel.attr(USER_ID_KEY, userId)           ││
│    │ 剥离 token 参数，还原纯净 WS 路径                       ││
│    │ 鉴权失败 → 401 + close                                 ││
│    └────────────────────────────────────────────────────────┘│
│                                                              │
│ 4. WebSocketServerProtocolHandler                            │
│    完成 HTTP → WebSocket 协议升级                             │
│    触发 HandshakeComplete 事件                               │
│                                                              │
│ 5. MessageInboundHandler.userEventTriggered(HandshakeComplete)│
│    ┌────────────────────────────────────────────────────────┐│
│    │ Lua 原子路由注册:                                       ││
│    │   local oldNode = redis.call('GET', routeKey)          ││
│    │   redis.call('SET', routeKey, newNodeId, 'EX', 1200)   ││
│    │   return oldNode                                       ││
│    │                                                        ││
│    │ 异地登录踢人:                                           ││
│    │   oldNode != localNode → Redis Pub/Sub 踢旧节点        ││
│    │   oldChannel != newChannel (同节点) → oldChannel.close()││
│    │                                                        ││
│    │ sessionManager.add(userId, channel)                     ││
│    └────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────┘
```

面试要点：
- **为什么路由注册用 Lua 脚本？** 需要原子地"读取旧路由 + 写入新路由"。如果分成 GET + SET 两步，两个节点可能同时读到 null，都认为自己是第一个注册的，丢失了异地登录检测。
- **为什么鉴权只在握手时做？** WebSocket 一旦升级成功，后续所有帧都在同一个 TCP 连接上，不需要每帧都验签。`userId` 通过 `channel.attr()` 绑定，后续读取零开销。

### 4.2 心跳保活机制

```
                5分钟无数据
客户端 ◄─────────────────────── Netty IdleStateHandler
  │                                    │
  │                              触发 IdleStateEvent
  │                                    │
  │                                    ▼
  │                          MessageInboundHandler
  │                          .userEventTriggered()
  │                                    │
  │                          IDLE_COUNTER++ (Channel Attribute)
  │                                    │
  │                          ┌─────────┴─────────┐
  │                          │                    │
  │                    counter < 3           counter >= 3
  │                          │                    │
  │                  发送 SERVER_PING        强制断开连接
  │  ◄───── {"type":5, "data":"SERVER_PING"}      ctx.close()
  │                                               │
  │  ────▶ {"type":5, "data":"..."}               │
  │  (客户端回复心跳)                               │
  │                                               │
  │  → counter 重置为 0                            │
  │  → Redis 路由 TTL 续期 20 分钟                 │
```

三次容忍机制：
- 第 1 次空闲（5 分钟）：发 SERVER_PING，counter=1
- 第 2 次空闲（10 分钟）：发 SERVER_PING，counter=2
- 第 3 次空闲（15 分钟）：判定客户端已死，强制断开

任何一次客户端发来消息（心跳、ACK、任何帧），counter 都会重置为 0，同时 `redis.expire("im:route:{userId}", 20min)` 续期路由。

### 4.3 聊天消息推送链路

```
Messaging Service
  │
  │  查 Redis: im:route:{receiverId} → NODE_1
  │
  │  RocketMQ.send("IM_CHAT:NODE_1", GatewayPushPacket{
  │      targetUserIds: [receiverId],
  │      wsPayload: '{"type":2,"data":{...消息体...}}'
  │  })
  │
  ▼
┌──────────────────────────────────────────────────────────┐
│ RTC 节点 1: NettyPushMessageListener                     │
│                                                          │
│  1. 反序列化 GatewayPushPacket                           │
│  2. 遍历 targetUserIds                                   │
│     ┌────────────────────────────────────┐               │
│     │ for each userId:                   │               │
│     │   channel = sessionManager.get()   │               │
│     │   if (channel != null && active)   │               │
│     │     channel.writeAndFlush(payload) │  ← 推送成功   │
│     │   else                             │               │
│     │     failedUserIds.add(userId)      │  ← 推送失败   │
│     └────────────────────────────────────┘               │
│                                                          │
│  3. 失败的用户 → 回流到 IM_CHAT:OFFLINE                  │
│     (由 OfflineService 消费，写入离线消息存储)            │
└──────────────────────────────────────────────────────────┘
```

面试要点：
- **为什么不直接让 Messaging 推送到 Netty Channel？** 因为 Messaging 和 RTC 是不同的微服务、不同的 JVM。Messaging 不持有 WebSocket 连接，只能通过 MQ 间接投递。
- **为什么推送失败要回流到 OFFLINE Tag？** 用户可能在消息投递的瞬间刚好断开连接（路由信息有 TTL 延迟）。回流到 OFFLINE 让 OfflineService 兜底存储，用户下次上线时拉取。
- **`sharedFrame.retain()` 是什么？** Netty 的引用计数机制。一个 `TextWebSocketFrame` 默认 refCnt=1，`writeAndFlush` 后会 release。如果要发给多个 Channel，必须 `retain()` 增加引用，最后手动 `release()` 一次。

### 4.4 系统通知推送链路

```
其他微服务
  │  POST /api/v1/chat/push/newSession/12345
  │  Body: NewSessionNotification{...}
  │
  ▼
PushController
  │
  ▼
NettyMessageService.sendPush()
  │
  │  1. Redis GET im:route:{userId}
  │     → 得到目标节点 ID (如 NODE_2)
  │     → 若不存在，Tag 设为 "OFFLINE"
  │
  │  2. RocketMQ.asyncSend("IM_NOTIFY:NODE_2", GatewayPushPacket)
  │
  ▼
RTC 节点 2: NotifyPushMessageListener
  │  (逻辑同 NettyPushMessageListener)
  │  找 Channel → 推送 → 失败回流 OFFLINE
```

注意：聊天消息（IM_CHAT）和系统通知（IM_NOTIFY）走**不同的 Topic**，消费者组也不同，互不干扰。这样即使聊天消息量暴增导致消费积压，也不会阻塞好友申请、新会话等关键通知。

### 4.5 异地登录踢人

```
场景：用户在手机 A 已连接 NODE_1，又在手机 B 发起连接到 NODE_2

手机 B                   NODE_2                    Redis                    NODE_1                手机 A
  │  ws://...?token=xxx    │                         │                        │                     │
  │───────────────────────▶│                         │                        │                     │
  │                        │                         │                        │                     │
  │                        │  Lua ROUTE_SWAP_SCRIPT  │                        │                     │
  │                        │  GET im:route:{userId}  │                        │                     │
  │                        │  → 返回 "NODE_1"        │                        │                     │
  │                        │  SET im:route:{userId}  │                        │                     │
  │                        │  = "NODE_2" EX 1200     │                        │                     │
  │                        │◀────────────────────────│                        │                     │
  │                        │                         │                        │                     │
  │                        │  oldNode=NODE_1 ≠ NODE_2│                        │                     │
  │                        │  → Pub/Sub 踢人:         │                        │                     │
  │                        │    "NODE_1:userId"       │                        │                     │
  │                        │─────────────────────────▶│  userLogout channel   │                     │
  │                        │                         │───────────────────────▶│                     │
  │                        │                         │                        │                     │
  │                        │                         │  UserKickoutListener:  │                     │
  │                        │                         │  目标=NODE_1, 是本节点  │                     │
  │                        │                         │  channel.close()       │                     │
  │                        │                         │                        │────── FIN ─────────▶│
  │                        │                         │                        │              连接断开│
  │◀───── 连接建立成功 ────│                         │                        │                     │
```

三种踢人场景：

| 场景 | 检测方式 | 处理 |
|------|---------|------|
| **跨节点** | Lua 返回的 oldNodeId ≠ localNodeId | Redis Pub/Sub 发送 `"oldNodeId:userId"`，目标节点的 Listener 关闭 Channel |
| **同节点** | `sessionManager.getChannel(userId)` 返回旧 Channel | 直接 `oldChannel.close()` |
| **Auth 主动登出** | Auth 的 Lua 脚本 publish `"userLogout"` | 所有节点的 Listener 收到，匹配节点 ID 后关闭 Channel |

### 4.6 连接断开与路由清理

```
连接断开 (客户端关闭 / 心跳超时 / 被踢)
  │
  ▼
MessageInboundHandler.channelInactive()
  │
  ▼
NettySessionManager.remove(channel)
  │
  │  1. 从 channel.attr() 取出 userId
  │
  │  2. CAS 删除: userChannelMap.remove(userId, channel)
  │     → 只有 map 里存的还是这个 channel 实例才删
  │     → 如果已被新连接覆盖，跳过 (防误杀)
  │
  │  3. 若本地删除成功:
  │     Redis GET im:route:{userId}
  │     → 仍是本节点 → DEL (路由已失效)
  │     → 已被其他节点接管 → 跳过 (防误删新路由)
```

面试要点：
- **CAS 删除是防什么？** 防止"旧连接断开"误删"新连接的映射"。场景：用户断线重连很快，新连接先 `add` 覆盖了 map，紧接着旧连接的 `channelInactive` 触发。如果无脑 `remove(userId)`，会删掉新连接。`remove(userId, channel)` 保证只删自己。
- **路由清理为什么要二次检查节点 ID？** 同理防误删。旧连接断开时，新连接可能已经在另一个节点注册了新路由。如果无脑 `DEL im:route`，会删掉新节点的有效路由。

---

## 五、Netty 线程模型

```
┌─────────────────────────────────────────────────────────────────┐
│                    Netty 线程池架构                               │
│                                                                  │
│  BossGroup (1 thread)                                           │
│  └── 职责: 接受新的 TCP 连接 (accept)                            │
│                                                                  │
│  WorkerGroup (CPU 核心数 threads)                                │
│  └── 职责: 处理 I/O 读写 (read/write)                           │
│      - HTTP 编解码                                               │
│      - WebSocket 协议处理                                        │
│      - JWT 鉴权 (仅握手时，CPU 开销小)                           │
│      - IdleStateHandler 空闲检测                                 │
│                                                                  │
│  BusinessGroup (CPU 核心数 × 2 threads)                          │
│  └── 职责: 执行 MessageInboundHandler 的业务逻辑                 │
│      - JSON 解析                                                 │
│      - Redis 操作 (路由续期、路由注册)                            │
│      - 心跳/ACK/登出处理                                         │
│                                                                  │
│  设计意图:                                                       │
│  Worker 线程只做 I/O，不被 Redis 阻塞调用卡住                    │
│  业务逻辑隔离到 BusinessGroup，即使 Redis 延迟也不影响 I/O 吞吐   │
└─────────────────────────────────────────────────────────────────┘
```

面试要点：
- **为什么 MessageInboundHandler 要放在 businessGroup 上？** 它里面有 Redis 调用（`expire` 续期、Lua 脚本注册路由），这些是网络 I/O 操作。如果放在 WorkerGroup 上执行，一旦 Redis 延迟，会阻塞该 Worker 线程上所有连接的 I/O 读写。隔离到 businessGroup 后，Worker 始终只做 TCP 读写，不受业务影响。
- **BossGroup 为什么只要 1 个线程？** Boss 只负责 accept 新连接，单端口场景下一个线程足够。连接建立后立刻交给 WorkerGroup 处理。

---

## 六、Netty Pipeline 详解

```
入站方向 (客户端 → 服务端):

TCP 字节流
    │
    ▼
IdleStateHandler
    │  计时器：5 分钟内没有读事件 → 触发 IdleStateEvent
    │  (不修改数据，只触发事件)
    │
    ▼
HttpServerCodec
    │  将 TCP 字节解码为 HTTP 请求对象
    │  (仅在 WebSocket 握手阶段使用)
    │
    ▼
ChunkedWriteHandler
    │  支持大文件分块写入 (WebSocket 场景下基本不触发)
    │
    ▼
HttpObjectAggregator (64KB)
    │  将 HTTP 分块消息聚合为完整的 FullHttpRequest
    │
    ▼
WebSocketTokenAuthHandler (@Sharable, 单例)
    │  拦截 FullHttpRequest (握手请求)
    │  从 URL 参数提取 token → JWT 验签
    │  成功: channel.attr 绑定 userId，放行
    │  失败: 401 → close
    │
    ▼
WebSocketServerProtocolHandler ("/api/v1/chat/message")
    │  自动完成 HTTP → WS 协议升级
    │  握手成功后触发 HandshakeComplete 事件
    │  后续帧自动解码为 WebSocketFrame
    │
    ▼
MessageInboundHandler (@Sharable, 单例, businessGroup)
    │  处理 TextWebSocketFrame
    │  处理 HandshakeComplete (路由注册)
    │  处理 IdleStateEvent (心跳检测)
    │  处理 channelInactive (连接断开)
```

面试常问：`@ChannelHandler.Sharable` 意味着什么？
> 标记 Handler 是无状态的，可以被多个 Channel 的 Pipeline 共享同一个实例。因为 `WebSocketTokenAuthHandler` 和 `MessageInboundHandler` 都是 Spring Bean（单例），所有连接共用一个实例，所以必须是 `@Sharable` 的。它们的状态（userId、idleCounter）都存在 `channel.attr()` 上，而不是 Handler 的成员变量里。

---

## 七、关键数据结构

### 7.1 消息协议

客户端和服务端之间通过 JSON 帧通信，统一结构：

```json
{
  "type": 5,        // 消息类型码
  "data": { ... }   // 具体数据，类型随 type 变化
}
```

### 7.2 客户端 → 服务端（上行消息）

| type | 枚举 | data 结构 | 用途 |
|------|------|-----------|------|
| 1 | ACK | `AckData { messageId }` | 确认收到推送 |
| 2 | LOG_OUT | 任意 | 主动断开连接 |
| 5 | HEART_BEAT | 任意 | 心跳保活 |

### 7.3 服务端 → 客户端（下行推送）

| type | 枚举 | data 结构 | 来源 |
|------|------|-----------|------|
| 1 | NEW_SESSION | `NewSessionNotification` | PushController |
| 2 | MESSAGE | 聊天消息体 | Messaging → IM_CHAT |
| 3 | MOMENT | `MomentNotification` | PushController |
| 4 | FRIEND_APPLICATION | `FriendApplicationNotification` | PushController |

### 7.4 MQ 传输载体

```java
GatewayPushPacket {
    List<Long> targetUserIds;  // 推送目标用户列表
    String wsPayload;          // 已序列化的 JSON（直接发给前端的最终形态）
}
```

设计亮点：`wsPayload` 在发送端（Messaging）就已经序列化好，RTC 节点拿到后不需要反序列化再序列化，直接 `writeAndFlush` 透传，减少 CPU 开销。

---

## 八、多节点部署架构

```
                         Messaging Service
                              │
                    ┌─────────┼─────────┐
                    │         │         │
              查路由 →     查路由 →   查路由 →
              NODE_1      NODE_2     OFFLINE
                    │         │         │
                    ▼         ▼         ▼
              IM_CHAT:    IM_CHAT:   IM_CHAT:
              NODE_1      NODE_2    OFFLINE
                    │         │         │
                    ▼         ▼         ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  RTC NODE_1  │ │  RTC NODE_2  │ │OfflineService│
│              │ │              │ │              │
│ consumerGroup│ │ consumerGroup│ │ consumerGroup│
│ netty-push-  │ │ netty-push-  │ │ offline-     │
│ group-NODE_1 │ │ group-NODE_2 │ │ group        │
│              │ │              │ │              │
│ selector:    │ │ selector:    │ │ selector:    │
│ NODE_1       │ │ NODE_2       │ │ OFFLINE      │
└──────────────┘ └──────────────┘ └──────────────┘
```

关键设计：
- **每个节点有独立的 consumerGroup**（`netty-push-group-${im.node.id}`），配合 `selectorExpression` Tag 过滤，保证每条消息只被目标节点消费。
- **im.node.id** 是部署时的核心配置，不同实例必须配置不同的值（NODE_1、NODE_2...），否则会导致消息路由错乱。
- 用户路由不存在时，Tag 设为 `OFFLINE`，由独立的 OfflineService 兜底。

---

## 九、优雅停机流程

```
JVM SIGTERM
    │
    ▼
@PreDestroy NettyServer.destroy()
    │
    │  1. Spring Cloud 自动从 Nacos 注销
    │     → 新连接不再被分配到本节点
    │
    │  2. sleep(5s)
    │     → 等待负载均衡器感知节点下线
    │
    │  3. 遍历所有在线用户
    │     → 发送 {"type":2, "data":"服务器维护中，请稍后重连"}
    │     → 客户端收到后触发重连逻辑
    │
    │  4. sleep(3s)
    │     → 等待消息发送完成
    │
    │  5. shutdownGracefully()
    │     → bossGroup: 停止接受新连接
    │     → workerGroup: 处理完队列中的 I/O 事件后关闭
    │     → businessGroup: 处理完队列中的业务逻辑后关闭
    │
    ▼
JVM 退出
```

---

## 十、面试高频问题与回答思路

### Q1: 你的 IM 消息是怎么推送到客户端的？

> 分三步。第一步，Messaging 发完消息后查 Redis 路由（`im:route:{userId}`）得到目标用户连在哪个 Netty 节点（如 NODE_1），把消息投到 RocketMQ 的 `IM_CHAT:NODE_1`。第二步，该节点的 `NettyPushMessageListener` 消费消息，从本地的 `ConcurrentMap<userId, Channel>` 里找到用户的 WebSocket Channel。第三步，`channel.writeAndFlush` 把消息推下去。推送失败的回流到 `IM_CHAT:OFFLINE` 由离线服务兜底。

### Q2: 如果用户在两个设备同时登录，怎么处理？

> 系统只支持单设备在线。WebSocket 握手成功后，Netty 用 Lua 脚本原子地"读旧路由 + 写新路由"。如果旧路由指向另一个节点，通过 Redis Pub/Sub 发踢人消息给旧节点，旧节点关闭那个 Channel。如果旧连接在同一个节点，直接 `oldChannel.close()`。整个过程对新连接无感知。

### Q3: Netty 的心跳机制怎么设计的？

> `IdleStateHandler` 检测 5 分钟读空闲，触发后服务端主动发 `SERVER_PING`。客户端回复后 counter 重置。连续 3 次无响应（累计 15 分钟）判定客户端已死，强制断开。同时每次收到任何客户端消息，都会 `redis.expire` 续期路由 TTL（20 分钟），保证路由不会比连接先过期。

### Q4: 为什么聊天消息和系统通知走不同的 MQ Topic？

> 隔离故障域。聊天消息量远大于系统通知（好友申请、新会话等），如果共享一个 Topic，高峰期聊天消息积压会导致系统通知延迟——用户可能几分钟收不到好友申请。分开 Topic 后，两者各自消费、各自积压、互不影响。

### Q5: 连接断开时路由清理的 CAS 逻辑是怎么回事？

> `NettySessionManager.remove(channel)` 用 `ConcurrentMap.remove(userId, channel)` 做 CAS 删除：只有 map 里存的还是这个 channel 实例才删。场景是快速断线重连：新连接先覆盖了 map，旧连接的 `channelInactive` 紧随其后触发。如果无脑 `remove(userId)`，会把新连接的映射误删。CAS 保证旧连接只能删自己。路由清理也一样——先检查 Redis 里的路由是否仍指向本节点，避免删掉已被新节点接管的路由。

### Q6: MessageInboundHandler 为什么加 `@Sharable`？

> 因为它是 Spring 管理的单例 Bean，所有 Channel 的 Pipeline 共享同一个实例。Netty 要求共享 Handler 必须标注 `@Sharable`，否则添加到第二个 Pipeline 时会抛异常。Handler 本身不存储连接级别的状态——userId 和 idleCounter 都存在 `channel.attr()` 上，每个连接独立，互不干扰。

### Q7: 为什么 Netty 要在独立线程中启动？

> `serverBootstrap.bind(port).sync().channel().closeFuture().sync()` 会阻塞当前线程直到服务器关闭。如果在 Spring 的 `@PostConstruct` 主线程中执行，Spring 上下文初始化会被永远阻塞——后续的 Bean 不会创建，HTTP 端口 8083 不会启动，RocketMQ Listener 不会注册。开独立线程让 Netty 阻塞在自己的线程上，Spring 主线程继续完成初始化。

---

## 十一、技术栈速查

| 技术 | 用途 |
|------|------|
| Netty 4.x (`netty-all`) | WebSocket 服务器，长连接管理 |
| RocketMQ | 跨服务消息投递（IM_CHAT / IM_NOTIFY） |
| Redis | 用户路由表 (`im:route`)、Pub/Sub 踢人 |
| Nacos | 服务注册发现（HTTP 8083 端口） |
| JJWT 0.9.1 | WebSocket 握手时 JWT 鉴权 |
| Hutool | JSON 工具、URL 解析 |
| Fastjson | MQ 消息序列化/反序列化 |
| Spring Boot 2.6.13 | 基础框架、HTTP 接口 |
