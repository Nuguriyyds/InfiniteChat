# InfiniteChat 项目复习题库

> 题库按章节组织。老师按顺序逐题提问，结合代码路径讲解。  
> 难度分为：`基础` / `进阶` / `深挖`

---

## 第 1 章：项目全景与架构设计

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 1-01 | InfiniteChat 是什么项目，核心要解决什么问题？ | 基础 | 项目定位 | 分布式 IM；覆盖认证、消息收发、实时推送、离线消息、红包、联系人等完整链路 | `RESUME.md` |
| 1-02 | 这个项目为什么要拆成多个微服务，而不是单体应用？ | 基础 | 服务拆分理由 | 认证、网关、RTC、消息、离线、联系人职责不同；便于横向扩展和故障隔离 | 服务目录 |
| 1-03 | 这个项目的"长短连接分离"体现在哪里？ | 进阶 | 架构理念 | HTTP 由 Gateway 处理；WebSocket 由 RTC 接入；登录后下发 `nettyUrl` 直连 RTC | `Gateway/...`, `RealTimeCommunication/...`, `AuthenticationService/.../UserServiceImpl.java` |
| 1-04 | 从"用户发送一条消息"到"对方收到消息"，核心链路会经过哪些服务？ | 进阶 | 端到端链路 | Gateway 鉴权 → Messaging(seq分配/MQ投递) → MessageStoreListener(落库/路由) → IM_CHAT:{nodeId} → NettyPushMessageListener → writeAndFlush；对方离线则走 IM_CHAT:OFFLINE → OfflineService | `MessageServiceImpl.java`, `MessageInboundHandler.java`, `OfflineMessageServiceImpl.java` |
| 1-05 | Redis 在这个项目里承担了哪些角色？ | 进阶 | 中间件角色拆分 | 验证码存储、RT 存储、登出时间戳、在线路由 `im:route:`、限流固定窗口、消息幂等去重 `im:msg:dedup:`、会话 seq `im:seq:`、离线消息热数据 ZSet、未读计数 Hash、分布式锁、RTC 节点负载 ZSet `im:rtc:load`、红包预计算 | 多服务代码 |
| 1-06 | 当前项目最值得在面试里讲的三个模块是什么，为什么？ | 深挖 | 简历与亮点提炼 | 推荐：Netty接入层（线程模型+重连数据）、消息主链路（3轮压测+量化数字）、红包系统（事务消息+Lua原子）；每个都要能讲清楚设计取舍和实测数字 | `RESUME.md` |

---

## 第 2 章：登录认证服务

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 2-01 | 登录认证模块主要解决哪些问题？ | 基础 | 模块目标 | 注册防重、登录验证、双 Token 签发、刷新、登出失效、RTC 直连地址分配 | `UserServiceImpl.java` |
| 2-02 | 注册为什么用了 Redisson 分布式锁，而不是只靠数据库唯一索引？ | 进阶 | 设计取舍 | 锁负责前置串行化，唯一索引做最终兜底；减少无效写库和回滚；锁外预处理（验证码校验），锁内做查重+事务写库 | `UserServiceImpl.java`, `RedisLockExecutor.java` |
| 2-03 | 这个项目为什么采用 AT/RT 双 Token，而不是单 JWT？ | 进阶 | 认证设计 | AT 短期（3h）高频鉴权，RT 长期（7天）续签；AT 无状态减少 Redis 读，RT 落 Redis 可控；兼顾安全性和体验 | `UserServiceImpl.java` |
| 2-04 | RefreshToken 为什么要落 Redis 并用 Lua compare-and-delete？ | 进阶 | RT 安全控制 | 单次使用防重放；原子 compare-and-delete 防止并发刷新（一个 RT 同时刷出两个新 AT）；服务端可控失效 | `UserServiceImpl.java` |
| 2-05 | 登录链路的数据流转是什么？ | 进阶 | 数据流转 | 查用户 → BCrypt 验密 → 签发 AT/RT(HS512) → RT 写 Redis(TTL=7天) → 查 ZSet 选 RTC 节点(P2C) → 返回 AT/RT/nettyUrl | `UserController.java`, `UserServiceImpl.java` |
| 2-06 | 注册链路的数据流转是什么？ | 进阶 | 数据流转 | 验证码写 Redis；注册时锁外预处理，锁内校验验证码、查重、事务写 `user` 和 `user_balance` | `UserController.java`, `UserServiceImpl.java` |
| 2-07 | 登出为什么要写 `jwt:logout:{userId}` 时间戳，而不是简单删 Token？ | 进阶 | JWT 注销 | JWT 无状态，AT 无法直接召回；登出时写时间戳，鉴权时比较 `token.iat < logoutAt` 拒绝旧 Token；本质是"只存一个时间戳的黑名单变体" | `JwtBlacklistService.java`, `AuthorizeFilter.java` |
| 2-08 | 当前 RTC 节点分配为什么是 P2C，而不是一致性哈希？ | 深挖 | 当前代码与设计演进 | 代码已改为基于 Redis ZSet 节点负载的 P2C（Power of Two Choices）；一致性哈希没有形成闭环时不应强写进简历 | `P2CLoadBalancer.java` |
| 2-09 | `P2CLoadBalancer` 是怎么工作的？如果 Redis 里没有负载数据会怎样？ | 深挖 | 算法理解与降级 | 从 ZSet 候选节点中随机挑两个，取 score（在线用户数）更小的；无负载数据则 score 按 0 处理；无有效节点时回退到首节点 | `P2CLoadBalancer.java` |

---

## 第 3 章：API 网关与认证

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 3-01 | Gateway 这一层主要负责什么？ | 基础 | 模块职责 | 入口治理：统一限流、鉴权、身份透传（X-User-Id）、路由转发 | `RateLimitFilter.java`, `AuthorizeFilter.java` |
| 3-02 | 为什么限流要前置在网关？ | 进阶 | 设计取舍 | 最早拦截恶意流量，避免进入鉴权和业务服务；节省后端 CPU/连接资源 | `RateLimitFilter.java` |
| 3-03 | 固定窗口 IP 限流的 Lua 脚本做了哪几步？ | 进阶 | Lua 逻辑 | `INCR key` → 首次设置 `EXPIRE`（1秒）→ 超过阈值返回 0（拒绝），否则返回 1（放行）；INCR + EXPIRE 非原子是已知缺陷 | `RateLimitFilter.java` |
| 3-04 | 这个限流方案的缺点是什么？ | 进阶 | 方案局限 | 窗口边界突刺（连续两个窗口边界可以打双倍 QPS）；只按 IP 粒度较粗；INCR+EXPIRE 非原子（极端情况 key 永不过期） | `RateLimitFilter.java` |
| 3-05 | AuthorizeFilter 为什么除了 JWT 验签，还要读 Redis 的登出时间戳？ | 进阶 | 鉴权补充逻辑 | 解决 JWT 无状态导致的登出失效；通过 `token.iat < logoutTime` 拒绝旧 AT；RTC 的 WebSocketTokenAuthHandler 同样有这个检查 | `AuthorizeFilter.java` |
| 3-06 | 为什么网关鉴权通过后还要向下游注入 `X-User-Id` 和内部头？ | 进阶 | 身份透传 | 避免下游重复解析 JWT；形成统一入口鉴权 + 内网服务信任模型；sendUserId 从 X-User-Id 注入防止客户端伪造发送者身份 | `AuthorizeFilter.java` |
| 3-07 | `/api/message` 被放入白名单意味着什么，面试里应该怎么解释？ | 深挖 | 工程反思 | 这是压测阶段临时放开的特例用于隔离链路瓶颈，不应作为正式生产设计；面试时要主动说明原因和改进方向 | `RateLimitFilter.java`, `AuthorizeFilter.java` |

---

## 第 4 章：RTC / Netty 长连接

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 4-01 | RTC 服务在整个系统里的职责是什么？ | 基础 | 模块职责 | 负责长连接接入、WebSocket 握手鉴权、会话管理、在线路由登记、跨节点消息下发、心跳检测 | `NettyServer.java`, `MessageInboundHandler.java` |
| 4-02 | 你的项目用了哪种 Reactor 线程模型？Boss/Worker/Business 各自的职责是什么？ | 基础 | 线程模型 | 主从 Reactor 的三级变体：Boss(1线程)=Main Reactor 只做 accept；Worker(CPU核数)=Sub Reactor 做 I/O 读写；Business(CPU×2)=独立业务线程池执行含阻塞调用的业务逻辑 | `NettyServer.java` |
| 4-03 | 为什么 Business 线程组要独立出来？如果不拆会怎样？ | 进阶 | 线程分离 | Business 有 Redis 续期/Lua 和 Feign ACK 回写等阻塞 I/O；若在 Worker 线程执行会阻塞 NioEventLoop，该 Worker 管理的所有 Channel 心跳/读写全部卡死，一个 Worker 阻塞影响数千用户 | `NettyServer.java` |
| 4-04 | NioEventLoop 的 run() 循环做了哪三件事？epoll 空轮询 bug 是什么，Netty 怎么解决？ | 进阶 | NIO 底层 | ①select() 检查就绪 I/O；②processSelectedKeys() 处理 read/write/accept；③runAllTasks() 执行 taskQueue 任务。空轮询：JDK NIO 在 Linux 下 select() 可能无事件返回 0 导致 CPU 空转；Netty 记录空轮询次数超阈值(512次)后重建 Selector | `NettyServer.java` |
| 4-05 | 你的 Pipeline 里有哪些 Handler，顺序是什么，各自在哪个线程组执行？ | 进阶 | Pipeline 职责划分 | IdleStateHandler(300s读空闲) → HttpServerCodec → ChunkedWriteHandler → HttpObjectAggregator(64KB) → WebSocketTokenAuthHandler → WebSocketServerProtocolHandler → MessageInboundHandler(businessGroup)；前6个在 Worker，最后1个在 Business | `NettyServer.java:78-85` |
| 4-06 | WebSocket 握手完成后，HTTP 相关的 Handler 还在 Pipeline 里吗？ | 进阶 | Pipeline 动态修改 | WebSocketServerProtocolHandler 握手成功后自动移除 HttpServerCodec、HttpObjectAggregator、ChunkedWriteHandler；握手后走 WebSocket 帧协议不再需要 HTTP 编解码 | `NettyServer.java` |
| 4-07 | `WebSocketTokenAuthHandler` 为什么必须放在 `WebSocketServerProtocolHandler` 前面？ | 进阶 | 认证时机 | 必须在 WebSocket 握手之前拦截 HTTP Upgrade 请求提取 token 鉴权；放后面收不到 FullHttpRequest；同时检查 Redis `jwt:logout:{userId}` 判断登出状态 | `WebSocketTokenAuthHandler.java` |
| 4-08 | `@ChannelHandler.Sharable` 是什么意思？你的 Handler 为什么能标这个注解？ | 进阶 | 线程安全 | 允许一个 Handler 实例被添加到多个 Channel 的 Pipeline；两个 Handler 无实例级可变状态——userId/idleCounter 存在 Channel Attribute 中（每 Channel 独立），Redis/Feign 本身线程安全 | `MessageInboundHandler.java`, `WebSocketTokenAuthHandler.java` |
| 4-09 | `pipeline.addLast(businessGroup, messageInboundHandler)` 这行代码做了什么？ | 进阶 | 线程绑定 | 把 messageInboundHandler 的所有回调(channelRead0/userEventTriggered/channelInactive)绑定到 businessGroup 的某个固定 EventExecutor 执行；实现 I/O 线程与业务线程分离；同一 Channel 永远由同一个 EventExecutor 处理，无并发 | `NettyServer.java` |
| 4-10 | SO_BACKLOG=128、SO_KEEPALIVE=true、TCP_NODELAY=true 分别是什么作用？ | 进阶 | ServerBootstrap 参数 | BACKLOG：全连接队列大小；KEEPALIVE：TCP层保活检测对端进程崩溃（默认2h，与应用层心跳互补）；TCP_NODELAY：关闭 Nagle 算法禁止小包合并，IM 低延迟场景必须关闭 | `NettyServer.java` |
| 4-11 | 心跳方案是怎么设计的？300 秒怎么理解？ | 进阶 | 心跳机制 | 客户端每30s主动发 HEART_BEAT；服务端 IdleStateHandler 300s无读事件才触发 SERVER_PING；连续3次空闲(最长15分钟)才强制关闭；300s是兜底检测，正常情况不触发 | `MessageInboundHandler.java:96-140` |
| 4-12 | `idleCounter` 存在 Channel Attribute 里，有线程安全问题吗？ | 进阶 | 并发安全 | 没有。同一 Channel 绑定到同一个固定 EventExecutor，所有事件（IdleStateEvent/channelRead）由同一线程处理，attr.get()/set() 不会并发，无需加锁 | `MessageInboundHandler.java` |
| 4-13 | `NettySessionManager` 的 `remove(Channel channel)` 为什么用双参数 remove？ | 进阶 | CAS 语义 | CAS 语义：只有当 Map 中 userId 对应的 value 确实是这个 channel 时才删除；防止用户新设备登录后旧连接 channelInactive 把新连接的映射也删掉 | `NettySessionManager.java` |
| 4-14 | 路由注册为什么要用 Lua 脚本而不是先 GET 再 SET？ | 进阶 | 原子性 | 原子性：两步之间可能另一个节点插入 SET；Lua 在 Redis 中原子执行，获取 oldNodeId 和写入 newNodeId 之间不会被打断 | `MessageInboundHandler.java:42-45` |
| 4-15 | `im:rtc:load` ZSet 是用来做什么的？什么时候更新？ | 进阶 | 负载联动 | 记录每个 RTC 节点的在线用户数（score=在线数，member=wsUrl）；每次 add/remove 用户后更新；Auth 服务的 P2CLoadBalancer 从这里选负载最小的节点返回给登录用户 | `NettySessionManager.java:106-111` |
| 4-16 | 单设备在线（挤占登录）是怎么实现的？跨节点踢人为什么走 Redis Pub/Sub？ | 进阶 | 分布式踢人 | HandshakeComplete 时 Lua 原子 swap 路由返回 oldNodeId；如果 oldNodeId 是其他节点则 Redis pub/sub 通知旧节点关闭旧连接；同节点则直接 close 旧 channel | `MessageInboundHandler.java:116-138` |
| 4-17 | Redis Pub/Sub 踢人方案有什么问题？ | 深挖 | 方案局限 | Pub/Sub 不持久化：目标节点断开 Redis 订阅期间踢人消息丢失 → 旧节点连接不关闭 → 最长20分钟内双设备在线；路由 TTL 过期后消息不再推到旧连接；严格场景可用 Redis Stream 替代 | `MessageInboundHandler.java` |
| 4-18 | 节点重启后，1000 个连接约 8 秒恢复这个数字是怎么来的？指数退避的参数是什么？ | 深挖 | 简历数字可辩护 | 初始1s、封顶10s、倍数2、抖动1s；优化前固定1s延迟3轮重试2000次失败→约11s恢复；优化后退避打散重连请求→1126次尝试126次失败→约8s恢复；核心收益是降低服务恢复期冲击 | `scripts/ws_load_test.js:211-228` |
| 4-19 | 优雅停机的流程是什么？sleep(5000ms) 为什么可能不够？ | 深挖 | 优雅停机 | sleep 5s等注册中心摘除 → 遍历在线用户发 LOG_OUT → sleep 3s → bossGroup/workerGroup/businessGroup 依次 shutdownGracefully；Nacos 默认30s才完全摘除，5s 不够，Gateway 可能还在路由新请求到停机节点 | `NettyServer.java:94-127` |
| 4-20 | RTC 这套设计的局限或风险点有哪些？ | 深挖 | 风险识别 | 脏路由（节点宕机后路由残留直到TTL）、kill -9 不触发 @PreDestroy 导致 ZSet 成员残留、WRITE_BUFFER_WATER_MARK 未设置可能 OOM、TCP_KEEPALIVE 2h 探测间隔远大于 NAT 超时、Pub/Sub 踢人不可靠 | `MessageInboundHandler.java`, `NettySessionManager.java` |
| 4-21 | 客户端 WebSocket 为什么不能用 HTTP Header 传 token？ | 基础 | 协议限制 | 浏览器 WebSocket API 不支持自定义 Header；只能走 URL query 或 Sec-WebSocket-Protocol 子协议；生产环境要用 wss 防止 token 出现在 access log 中 | `WebSocketTokenAuthHandler.java:38` |

---

## 第 5 章：消息发送主链路

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 5-01 | `sendMessage` 入口主要做了哪几类事情？ | 基础 | 主链路拆分 | 校验 clientMsgId → 多级缓存读用户/好友/会话 → 权限校验 → Lua 原子去重+seq分配 → 生成 messageId → 组装 AppMessage → mqSendExecutor 异步投递 → triggerAI → 返回 messageId/seq | `MessageServiceImpl.java` |
| 5-02 | 为什么要引入 `clientMsgId`？ | 进阶 | 幂等设计 | 客户端重试幂等；服务端用 Redis `im:msg:dedup:{sessionId}:{clientMsgId}` SETNX 防重复投递；TTL=5分钟覆盖客户端最大重试窗口 | `MessageServiceImpl.java` |
| 5-03 | 去重和 seq 分配为什么要放在同一个 Lua 脚本里？ | 进阶 | 原子性 | 两步之间进程崩了会导致去重key已设但seq未分配——seq空洞；Lua原子执行保证要么两步都做要么都不做 | `MessageServiceImpl.java:562-572` |
| 5-04 | 消息发送链路为什么不等 MySQL 写入完成再返回？同步落库时的 P99 是多少？ | 进阶 | 性能优先 | 同步落库时 P99 高达48s（第一轮瓶颈原因是 @Transactional 长占连接导致连接饥饿）；异步后降到80ms；发送链路低延迟优先，持久化由 MQ 消费端保障 | `MessageServiceImpl.java`, `MessageStoreListener.java` |
| 5-05 | 会话内有序是怎么保证的？`asyncSendOrderly` 用了 CONCURRENTLY 消费，不会乱序吗？ | 进阶 | 顺序性 | 发送端 `sendOrderly(hashKey=sessionId)` 确保同 session 消息落同一 MessageQueue；seq 在 Redis INCR 时已确定；消费端 CONCURRENTLY 因为落库操作独立、seq字段已定，客户端 /sync 按 seq ASC 查询，最终有序 | `MessageServiceImpl.java:153`, `MessageStoreListener.java:35` |
| 5-06 | 群聊消息推送是怎么避免发 N 条 MQ 消息的？ | 进阶 | 路由分发优化 | 批量 multiGet 所有群成员路由 → 按 nodeId 分组 → 每组一条 MQ 消息（批量推给同节点的多个用户）→ 离线用户一条；100人群分布在3节点+10离线 = 4条 MQ，不是90条 | `MessageStoreListener.java:97-149` |
| 5-07 | `msg_failover` 是用来做什么的？定时任务怎么保证不重复执行？ | 进阶 | 容错兜底 | MQ 投递失败时落本地兜底表；MsgFailoverRetryTask 每10s用 Redisson tryLock 抢锁（拿不到直接跳过），成功后查 status=0 的记录重投；retryCount>=5 标记死信 | `MsgFailoverMapper.java`, `MsgFailoverRetryTask.java` |
| 5-08 | 这套消息链路的多级缓存怎么防缓存雪崩？ | 进阶 | 缓存设计 | 用户/好友/会话缓存 TTL=24~48h 随机（ThreadLocalRandom），把过期时间打散；单聊走 `getSingleChatCacheSnapshot()` 一次 multiGet 3个key减少 RTT | `MessageServiceImpl.java:221-235` |
| 5-09 | 三轮压测是怎么进行的，每轮发现了什么瓶颈，怎么解决的？ | 深挖 | 压测故事（简历核心） | **第1轮**：491RPS/P99 48s → jstack 看 Tomcat 线程 WAITING 在 HikariPool.getConnection() → 根因是 @Transactional 事务长占连接 → 去掉事务 → 700RPS；**第2轮**：700RPS/P99不稳 → jstack 看线程阻塞在 RocketMQ producer 内部锁 → 引入 mqSendExecutor 独立线程池 → 900RPS；**第3轮**：900→994RPS → 监控 Redis 连接数耗尽 → 扩大连接池 max-active=8→128 → 994RPS/P99 80ms | `MessageServiceImpl.java`, `MqSendExecutorConfig.java`, `application.yml` |
| 5-10 | `mqSendExecutor` 线程池参数是什么？CallerRunsPolicy 什么时候触发？ | 进阶 | 线程池设计 | 核心16/最大64/队列8192/CallerRunsPolicy；当16线程忙+队列满+64线程也忙时，Tomcat 线程直接执行（降级不丢消息）；核心16×平均1~3ms≈5000+TPS | `MqSendExecutorConfig.java:19-29` |
| 5-11 | 为什么消息发送去掉了 `@Transactional`？这不会有数据不一致风险吗？ | 进阶 | 事务决策 | sendMessage 不需要事务：seq 由 Redis 保证，落库由异步MQ消费端保障，两者不需要在同一DB事务；@Transactional 导致 Redis 读/MQ 投递/DB写都占着同一个连接 → 连接饥饿 | `MessageServiceImpl.java` |
| 5-12 | wrk2 和 wrk 有什么区别？为什么选 wrk2 做压测？ | 进阶 | 压测工具 | wrk 是开环（尽快发），实际RPS取决于服务响应；wrk2 是闭环（严格按-R速率），避免 Coordinated Omission（服务慢了wrk会减慢发送掩盖真实延迟）；-L 参数输出完整延迟分布 | `scripts/LOAD_TEST_PLAN.md` |
| 5-13 | 消息 ID 生成为什么用雪花算法而不是 UUID？时钟回拨怎么处理？ | 进阶 | ID 生成 | UUID 无序作为主键导致 B+ 树频繁分裂；雪花ID趋势递增；两级防御：5ms内轻微回拨 sleep 等待，超过5ms抛异常拒绝生成；workerId 基于 IP hashCode % 32 有碰撞风险是缺陷 | `IdGenerator.java` |
| 5-14 | ACK 机制支持按 seq 和按 messageId 两种，区别是什么？为什么说 ACK 走 Feign 是瓶颈？ | 深挖 | ACK 设计 | seq ACK：批量"我收到了这个 session 到 seq=100 的所有消息"；messageId ACK：单条精确，需要多一次 DB 查询；Feign 在 Business 线程同步调用 Messaging /ack，Messaging DB 慢时阻塞 Business 线程影响同线程其他 Channel | `MessageInboundHandler.java:142-164` |
| 5-15 | 这套消息链路里最容易被面试官追问的三个点是什么？ | 深挖 | 面试风险点 | ①幂等 TTL 5分钟的依据；②sendOrderly+CONCURRENTLY 为什么还能保证顺序（seq决定顺序不是消费顺序）；③MQ 失败 failover 能保证不丢消息吗（不能完全保证，CallerRunsPolicy 兜底但 Tomcat 超时也可能丢） | `MessageServiceImpl.java` |

---

## 第 6 章：可靠性与离线消息

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 6-01 | 这个项目的消息可靠性目标是什么？ | 基础 | 可靠性模型 | 至少一次投递 + 消费端去重，效果上接近恰好一次；不是严格 exactly-once | `RESUME.md`, `MessageServiceImpl.java` |
| 6-02 | 发送端、MQ 层、消费端分别做了哪些可靠性措施？ | 进阶 | 分层保障 | 发送端：clientMsgId Lua 去重+failover兜底；MQ层：至少一次+重试16次；消费端：DuplicateKeyException 忽略（messageId唯一索引） | `MessageServiceImpl.java`, `MessageStoreListener.java` |
| 6-03 | 离线消息为什么采用 Redis ZSet + MySQL 的热冷分层？为什么 ZSet score 用 seq 不用时间戳？ | 进阶 | 存储分层 | 热数据快速拉取，冷数据长期保存；seq 是会话级单调递增，支持 ZRANGEBYSCORE(lastSeq, +∞) 精确增量拉取；时间戳同一秒内可能相同无法分页 | `OfflineMessageServiceImpl.java` |
| 6-04 | `isRedisHotRangeCovered` 是怎么判断热数据是否够用的？举个具体例子。 | 进阶 | 热冷判断 | 取 ZSet 最小 score(minHotSeq)；若 minHotSeq <= lastSeq+1 则覆盖走Redis；否则走MySQL。例：lastSeq=100，ZSet最小seq=98 → 98<=101 → 覆盖；lastSeq=50，ZSet最小seq=98 → 98>51 → 走MySQL | `OfflineMessageServiceImpl.java:255-270` |
| 6-05 | 离线消息拉取后就 remove，如果客户端没处理完崩溃了怎么办？ | 进阶 | 消费语义 | Redis 消费即删除，但有两层兜底：MySQL 冷数据7天内还在；客户端重连后可通过 /message/sync 从 im_message 主表按 seq 补齐；Redis 层删除安全的前提是有双重兜底 | `OfflineMessageServiceImpl.java:144-169` |
| 6-06 | 未读数为什么要 Redis Hash + MySQL 双写？一致性怎么保证？ | 进阶 | 热点状态设计 | Redis 负责高频读写(HINCRBY 原子)，MySQL 负责持久化和重建兜底；两者无事务保证，最坏情况未读数不准，打开会话清零即恢复；GREATEST(count-n, 0) 防止负数 | `UnreadCountServiceImpl.java` |
| 6-07 | 为什么离线通知只用 Redis List，不像聊天消息那样双写 MySQL？ | 进阶 | 设计取舍 | 通知类（好友申请/朋友圈）优先级低于聊天消息，丢了不影响核心体验；List 保留最新 200 条；RPUSH+LTRIM 在 Redis 单线程下串行无并发问题 | `OfflineNotifyListener.java` |
| 6-08 | 离线消息清理任务为什么要加分布式锁？用的是 SETNX 还是 Redisson？ | 进阶 | 定时任务并发控制 | 避免多实例重复清理；用的是 SETNX + TTL=10分钟；Redisson RLock 有 Watchdog 适合执行时间不确定的长任务；当前清理任务分批+8s截断，10分钟TTL够用 | `OfflineMessageCleanTask.java:27-90` |
| 6-09 | 限流检查的 INCR + EXPIRE 不是原子的，有什么问题？怎么修复？ | 进阶 | 原子性缺陷 | INCR 后进程崩溃未执行 EXPIRE，key 永不过期，该用户被永久限流；修复：用 Lua 脚本把 INCR + EXPIRE 合为原子操作，或 SET key 1 EX 1 NX + INCR 组合 | `OfflineMessageServiceImpl.java:241-253` |
| 6-10 | 同一条消息有两个入口进 OFFLINE（路由发现离线 / 推送失败回流），会重复吗？ | 深挖 | 幂等设计 | 会。但 storeToMySQL 先 COUNT 再 INSERT，存在非原子问题（两线程同时COUNT=0都INSERT）；DuplicateKeyException 没被 catch 是代码缺陷；应改为 INSERT IGNORE 或 ON DUPLICATE KEY | `OfflineMessageServiceImpl.java:199` |
| 6-11 | 用户上线后离线消息补齐流程是什么？/offline/pull 和 /message/sync 有什么区别？ | 深挖 | 端到端补齐 | 流程：获取 ackSeqMap → 对比 maxSeq → 走 /offline/pull（按 receiverId+sessionId+seq 查 offline_message，Redis 热数据优先）或 /message/sync（按 sessionId+seq 查 im_message 主表，返回 tombstone 标记空洞）| `OfflineMessageServiceImpl.java`, `MessageServiceImpl.java:574-607` |
| 6-12 | 这套离线消息设计的边界和风险点是什么？ | 深挖 | 工程反思 | storeToMySQL 先COUNT再INSERT非原子；Redis ZSet member 存完整JSON内存开销大；received Set 无 TTL 内存泄漏；checkPullLimit INCR+EXPIRE 非原子；跨会话拉取100个会话N次查询性能差 | `OfflineMessageServiceImpl.java`, `OfflineMessageCleanTask.java` |

---

## 第 7 章：红包系统

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 7-01 | 画出发红包和抢红包的完整链路。 | 基础 | 主链路 | 发红包：校验→CAS扣余额→INSERT红包→INSERT流水→发红包消息→Redis预计算→延迟MQ过期；抢红包：Redis极速拦截→CompletableFuture挂起→发事务消息→executeLocalTransaction执行Lua→future唤醒HTTP线程→MQ异步落库 | `RedPacketServiceImpl.java`, `RedPacketTxListener.java` |
| 7-02 | 四合一 Lua 脚本做了哪四步？为什么要合在一个脚本里？ | 进阶 | Lua 原子性 | ①SISMEMBER 防重领；②GET count 检查库存；③LPOP 弹出金额；④DECR count + SADD received + HSET pending。合一：Redis 单线程执行 Lua 原子，拆开则 check-then-act 并发超发 | `RedPacketConstants.UNIFIED_GRAB_LUA` |
| 7-03 | Pending Hash 是干什么的？为什么在事务边界外清理？ | 进阶 | Pending 保险箱 | Lua 执行成功时把金额写入 `red_packet:pending:{id}:{userId}`；作为"临时保险箱"防止 LPOP 金额后 DB 落库失败导致丢失；事务外清理：若在事务内清理后回滚，Pending Hash 已删但DB没写，金额永久丢失 | `RedPacketReceiveListener.java`, `RedPacketTxListener.java` |
| 7-04 | Broker 回查机制是怎么工作的？checkLocalTransaction 看什么？ | 进阶 | 事务消息 | executeLocalTransaction 没返回(超时/崩溃)时，Broker 每15s/30s/60s... 回查；checkLocalTransaction 检查 `red_packet:pending:{id}:{userId}` 是否存在：存在=Lua成功=COMMIT，不存在=ROLLBACK | `RedPacketTxListener.java:94-113` |
| 7-05 | CompletableFuture + corrId + FUTURE_CACHE 是干什么的？ | 进阶 | 线程通信 | HTTP 线程需要知道 Lua 执行结果（抢到多少钱），但 Lua 在 executeLocalTransaction 回调（不同线程）中执行；corrId=UUID 唯一关联一次请求；HTTP 线程 future.get(3s) 阻塞等待，Lua 线程 future.complete(amount) 唤醒；FUTURE_CACHE finally 块清理防泄漏 | `RedPacketReceiveServiceImpl.java`, `RedPacketTxListener.java` |
| 7-06 | future.get(3s) 超时了怎么办？用户会丢钱吗？ | 进阶 | 超时处理 | 返回"系统繁忙请重试"；但 Lua 可能已执行成功（金额已弹出），消息会 COMMIT，消费者异步落库；用户实际抢到了但看到错误提示；重新查红包详情可看到领取记录；不会超发 | `RedPacketReceiveServiceImpl.java` |
| 7-07 | 发红包的余额扣减 SQL 为什么要加 `balance >= #{amount}` 条件？这是什么模式？ | 进阶 | CAS扣减 | CAS 模式在 SQL 中的体现：WHERE 条件充当 compare，balance >= amount 防止余额变负；两个并发扣减请求：第一个成功余额变20，第二个20>=80失败updateCount=0；InnoDB UPDATE 加行锁串行执行 | `UserBalanceMapper.java:21-22` |
| 7-08 | 红包过期退款为什么要双重保障（延迟消息 + 定时任务）？CAS 状态更新的意义是什么？ | 进阶 | 过期容灾 | 延迟消息可能丢失；定时任务每小时兜底扫描；casUpdateStatus WHERE status=UNCLAIMED 确保幂等：已领完(CLAIMED)或已过期(EXPIRED)的CAS返回0跳过，无论触发几次退款只执行一次 | `RedPacketServiceImpl.java:362-408` |
| 7-09 | 防超发有几层防线？如何验证零超发？ | 进阶 | 资金安全 | ①Redis Lua 原子 DECR+LPOP；②MySQL 行锁 remaining_count>0 AND remaining_amount>=amount；③金额预计算 sum=totalAmount（不存在多弹）；验证SQL：`SELECT rp.total_amount, rp.remaining_amount, SUM(rpr.amount) FROM red_packet JOIN red_packet_receive GROUP BY 1 HAVING total_amount != remaining_amount + claimed_sum` | `RedPacketTxListener.java`, `RedPacketReceiveServiceImpl.java` |
| 7-10 | 防重复领取有几层防线？ | 进阶 | 幂等 | ①Lua SISMEMBER 检查 received Set；②应用层 countByRedPacketAndUser > 0 跳过；③数据库唯一索引(red_packet_id, receiver_id) catch DuplicateKeyException；三层保障即使 Redis 数据丢失也不会重复 | `RedPacketTxListener.java`, `RedPacketReceiveServiceImpl.java` |
| 7-11 | 二倍均值法是怎么分配金额的？为什么最后要 shuffle？ | 进阶 | 算法 | 每次随机 ∈ [minAmount, 剩余平均值×2]，期望值=剩余平均值保证公平；最后一个拿剩余；shuffle 打乱是因为算法生成的序列有规律（前大后小趋平均），LPOP 先到先得，shuffle 后先后顺序不影响公平 | `RedPacketServiceImpl.java` |
| 7-12 | 为什么抢红包用事务消息而不是本地消息表？ | 深挖 | 方案对比 | 本地消息表要求"本地操作和消息写入在同一MySQL事务"；抢红包的本地操作是 Redis Lua，不能纳入MySQL事务；事务消息的 executeLocalTransaction 允许执行任意操作（包括Redis）再根据结果决定消息投递 | `RedPacketTxListener.java` |
| 7-13 | 为什么不用 Redisson 分布式锁做抢红包并发控制？ | 深挖 | 方案对比 | 分布式锁串行化：100人同时抢需要串行100次，加锁/解锁开销大；Redis Lua 虽然也是单线程，但每次微秒级执行，100次串行也只需约0.1ms；Lua 脚本吞吐远高于 Redisson 加锁 | `RedPacketConstants.UNIFIED_GRAB_LUA` |
| 7-14 | 红包模块最有价值的两个面试亮点是什么？有哪些已知缺陷要主动提？ | 深挖 | 亮点与反思 | 亮点：事务消息保证Redis预扣与MySQL落库最终一致、四合一Lua原子防超发防重领；缺陷：received Set 无 TTL 内存泄漏、预计算用循环RPUSH N次RTT（应用Pipeline）、Redis宕机无重建机制 | `RedPacketServiceImpl.java`, `RedPacketTxListener.java` |

---

## 第 8 章：联系人与会话

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 8-01 | 好友同意链路为什么要加 Redisson 分布式锁？ | 进阶 | 并发控制 | 防止双方同时操作导致关系重复创建；锁 key 用 `min(userId, friendId):max(userId, friendId)` 保证双方互斥 | `ContactServiceImpl.java` |
| 8-02 | 联系人模块里事务主要保护了哪些操作？ | 进阶 | 事务边界 | 双向好友关系插入、会话创建、状态更新等需要一起成功 | `ContactServiceImpl.java` |
| 8-03 | 为什么联系人模块还要调用 Messaging / RTC？ | 进阶 | 跨服务协作 | 建立会话、发送好友申请通知消息、同步状态变化 | `ContactServiceImpl.java` |
| 8-04 | 联系人这块如果面试官问"缺点是什么"，可以怎么答？ | 深挖 | 反思能力 | 分布式事务边界较松，跨服务调用(Feign)失败无补偿；更多依赖业务幂等和人工补偿；跨服务链路可观测性不足 | `ContactServiceImpl.java` |

---

## 第 9 章：压测、测试与工程反思

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 9-01 | 你当前压测主要覆盖了哪些链路，没覆盖哪些链路？ | 基础 | 真实性校准 | 覆盖：消息发送(wrk2三轮)、WebSocket长连接稳定性(ws_load_test.js)、红包抢占(800RPS)、端到端链路(im_chain_test.js)；未充分覆盖：多节点协同、节点宕机路由脏数据恢复、广播风暴、弱网重连 | `scripts/LOAD_TEST_PLAN.md`, `ws_load_test.js` |
| 9-02 | 为什么说"只压发送接口"不能等价于"整个 IM 系统都压过了"？ | 进阶 | 压测边界 | 长连接容量、心跳保活、断连重连、多节点负载均衡、跨节点踢人、节点切换、离线消息积压消费都未充分覆盖 | `LOAD_TEST_PLAN.md` |
| 9-03 | 现在新增的 P2C 和 RTC 节点负载这块有什么测试？ | 进阶 | 新增测试点 | `P2CLoadBalancerTest`：验证选节点逻辑、Redis无数据降级；`NettySessionManagerTest`：验证 add/remove 后 Redis 负载正确更新 | `P2CLoadBalancerTest.java`, `NettySessionManagerTest.java` |
| 9-04 | 如果继续补测试，你最想补哪三类？ | 进阶 | 测试规划 | ①Netty 长连接容量测试（多少连接开始出现内存压力）；②弱网/重连测试（验证指数退避和路由脏数据恢复）；③节点宕机测试（kill -9 后路由清理、消息堆积消费） | 测试脚本与 RTC 模块 |
| 9-05 | 这个项目最需要在面试里"讲真话"的三个地方是什么？ | 深挖 | 面试策略 | ①一致性哈希是历史设计，当前已改为P2C（不要说一致性哈希已闭环）；②压测覆盖范围有限，多节点协同和故障恢复未充分测试；③/api/message 白名单是压测阶段特例，不是正式生产方案 | `P2CLoadBalancer.java`, `RateLimitFilter.java`, `LOAD_TEST_PLAN.md` |
| 9-06 | ws_load_test.js 压测脚本里验证"无异常断连"用的是什么指标？1000 连接 8s 恢复的数字怎么统计的？ | 深挖 | 指标可辩护 | 稳定在线：active=目标数 && closed=0 && errors=0 && unexpected=0，每5秒输出一行STAT；8s恢复：从 kill -9 进程到脚本输出 active=1000/1000 的时间差；服务端同步用 jstat -gcutil 监控GC | `scripts/ws_load_test.js` |

---

## 题库统计

| 章节 | 题数 |
|---|---:|
| 第 1 章：项目全景与架构设计 | 6 |
| 第 2 章：登录认证服务 | 9 |
| 第 3 章：API 网关与认证 | 7 |
| 第 4 章：RTC / Netty 长连接 | 21 |
| 第 5 章：消息发送主链路 | 15 |
| 第 6 章：可靠性与离线消息 | 12 |
| 第 7 章：红包系统 | 14 |
| 第 8 章：联系人与会话 | 4 |
| 第 9 章：压测、测试与工程反思 | 6 |
| **合计** | **94** |
