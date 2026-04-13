# InfiniteChat 项目复习题库

> 题库按章节组织。老师按顺序逐题提问，结合代码路径讲解。  
> 难度分为：`基础` / `进阶` / `深挖`

---

## 第 1 章：项目全景与架构设计

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 1-01 | InfiniteChat 是什么项目，核心要解决什么问题？ | 基础 | 项目定位 | 分布式 IM；覆盖认证、消息收发、实时推送、离线消息、红包、联系人等完整链路 | `RESUME.md` |
| 1-02 | 这个项目为什么要拆成多个微服务，而不是单体应用？ | 基础 | 服务拆分理由 | 认证、网关、RTC、消息、离线、联系人职责不同；便于横向扩展和故障隔离 | 服务目录 |
| 1-03 | 这个项目的“长短连接分离”体现在哪里？ | 进阶 | 架构理念 | HTTP 由 Gateway 处理；WebSocket 由 RTC 接入；登录后下发 `nettyUrl` 直连 RTC | `Gateway/...`, `RealTimeCommunication/...`, `AuthenticationService/.../UserServiceImpl.java` |
| 1-04 | 从“用户发送一条消息”到“对方收到消息”，核心链路会经过哪些服务？ | 进阶 | 端到端链路 | Gateway/认证 -> Messaging -> RocketMQ -> RTC/Offline -> 客户端 | `MessageServiceImpl.java`, `MessageInboundHandler.java`, `OfflineMessageServiceImpl.java` |
| 1-05 | Redis 在这个项目里承担了哪些角色？ | 进阶 | 中间件角色拆分 | 验证码、RT、登出时间戳、在线路由、限流、消息幂等、会话 seq、离线消息热数据、未读数、分布式锁 | 多服务代码 |
| 1-06 | 当前项目最值得在面试里讲的三个模块是什么，为什么？ | 深挖 | 简历与亮点提炼 | 登录认证、网关治理、消息可靠性/离线消息/红包任选其三；要能讲清闭环和取舍 | `RESUME.md` |

---

## 第 2 章：登录认证服务

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 2-01 | 登录认证模块主要解决哪些问题？ | 基础 | 模块目标 | 注册防重、登录、双 Token、刷新、登出失效、RTC 直连地址分配 | `UserServiceImpl.java` |
| 2-02 | 注册为什么用了 Redisson 分布式锁，而不是只靠数据库唯一索引？ | 进阶 | 设计取舍 | 锁负责前置串行化，唯一索引做最终兜底；减少无效写库和回滚 | `UserServiceImpl.java`, `RedisLockExecutor.java` |
| 2-03 | 这个项目为什么采用 AT/RT 双 Token，而不是单 JWT？ | 进阶 | 认证设计 | AT 短期高频鉴权，RT 长期续签；兼顾安全性和体验 | `UserServiceImpl.java` |
| 2-04 | RefreshToken 为什么要落 Redis 并用 Lua compare-and-delete？ | 进阶 | RT 安全控制 | 单次使用、服务端可控、原子 compare-and-delete、防重放与并发刷新 | `UserServiceImpl.java` |
| 2-05 | 登录链路的数据流转是什么？ | 进阶 | 数据流转 | 查用户、BCrypt 验密、签发 AT/RT、RT 写 Redis、返回 `nettyUrl` | `UserController.java`, `UserServiceImpl.java` |
| 2-06 | 注册链路的数据流转是什么？ | 进阶 | 数据流转 | 验证码写 Redis；注册时锁外预处理，锁内校验验证码、查重、事务写 `user` 和 `user_balance` | `UserController.java`, `UserServiceImpl.java` |
| 2-07 | 登出为什么要写 `jwt:logout:{userId}` 时间戳，而不是简单删 Token？ | 进阶 | JWT 注销 | JWT 无状态，AT 无法直接召回；通过 `iat` 与登出时间比较让旧 Token 统一失效 | `JwtBlacklistService.java`, `AuthorizeFilter.java` |
| 2-08 | 当前 RTC 节点分配为什么是 P2C，而不是一致性哈希？ | 深挖 | 当前代码与设计演进 | 代码已改为基于 Redis 节点负载的 P2C；一致性哈希没有形成闭环时不应强写进简历 | `P2CLoadBalancer.java` |
| 2-09 | `P2CLoadBalancer` 是怎么工作的？如果 Redis 里没有负载数据会怎样？ | 深挖 | 算法理解与降级 | 随机挑两个候选节点，选负载更小的；没有负载则按 0 处理；无有效 metadata 时回退到首节点 | `P2CLoadBalancer.java` |

---

## 第 3 章：API 网关与认证

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 3-01 | Gateway 这一层主要负责什么？ | 基础 | 模块职责 | 入口治理；统一限流、鉴权、身份透传、路由转发 | `RateLimitFilter.java`, `AuthorizeFilter.java` |
| 3-02 | 为什么限流要前置在网关？ | 进阶 | 设计取舍 | 最早拦截恶意流量，避免进入鉴权和业务服务，节省后端资源 | `RateLimitFilter.java` |
| 3-03 | 固定窗口 IP 限流的 Lua 脚本做了哪几步？ | 进阶 | Lua 逻辑 | `incr`、首次设置 `expire`、超过阈值返回 0，否则返回 1 | `RateLimitFilter.java` |
| 3-04 | 这个限流方案的缺点是什么？ | 进阶 | 方案局限 | 窗口边界突刺；只按 IP 维度较粗；白名单处理需要谨慎 | `RateLimitFilter.java` |
| 3-05 | AuthorizeFilter 为什么除了 JWT 验签，还要读 Redis 的登出时间戳？ | 进阶 | 鉴权补充逻辑 | 解决 JWT 无状态导致的登出失效；通过 `token.iat < logoutTime` 拒绝旧 AT | `AuthorizeFilter.java` |
| 3-06 | 为什么网关鉴权通过后还要向下游注入 `X-User-Id` 和内部头？ | 进阶 | 身份透传 | 避免下游重复解析 JWT；形成统一入口鉴权 + 内网服务信任模型 | `AuthorizeFilter.java` |
| 3-07 | `/api/message` 被放入白名单意味着什么，面试里应该怎么解释？ | 深挖 | 工程反思 | 这是压测阶段临时放开的特例，不应作为正式生产设计；要主动说明是为了隔离链路瓶颈 | `RateLimitFilter.java`, `AuthorizeFilter.java` |

---

## 第 4 章：RTC / Netty 长连接

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 4-01 | RTC 服务在整个系统里的职责是什么？ | 基础 | 模块职责 | 负责长连接接入、握手鉴权、会话管理、在线路由登记、消息下发 | `NettyServer.java`, `MessageInboundHandler.java` |
| 4-02 | 客户端是怎么连到 RTC 节点上的？ | 进阶 | 接入链路 | 登录拿 `nettyUrl` + token；客户端发起 WebSocket 握手；RTC 被动接入，不主动选节点 | `UserServiceImpl.java`, `WebSocketTokenAuthHandler.java` |
| 4-03 | Netty pipeline 里为什么要放 `IdleStateHandler`、`WebSocketTokenAuthHandler` 和 `MessageInboundHandler`？ | 进阶 | Pipeline 职责划分 | 心跳/空闲检测、一次性 token 校验、真正的会话注册与消息处理 | `NettyServer.java` |
| 4-04 | `WebSocketTokenAuthHandler` 解决了什么问题？为什么放在握手阶段处理？ | 进阶 | 认证时机 | 在建立长连接前拦截非法 token；避免未认证连接进入后续业务处理 | `WebSocketTokenAuthHandler.java` |
| 4-05 | `MessageInboundHandler` 在连接建立后做了哪些关键动作？ | 进阶 | 路由登记 | 绑定 `userId -> channel`、写 `im:route:{userId}`、必要时踢旧设备 | `MessageInboundHandler.java` |
| 4-06 | `NettySessionManager` 为什么还要把节点负载写入 Redis ZSet？ | 进阶 | 与登录调度联动 | 为 Auth 的 P2C 节点选择提供当前在线连接数视图 | `NettySessionManager.java` |
| 4-07 | 旧连接踢下线是怎么实现的？为什么需要 Pub/Sub？ | 深挖 | 多节点协作 | 同一用户新连接建立后，需要通知旧节点释放连接；多节点间通过 Redis Pub/Sub 协作 | `MessageInboundHandler.java` |
| 4-08 | RTC 这套设计的局限或风险点有哪些？ | 深挖 | 风险识别 | 脏路由、节点宕机后的路由清理、连接数不等于真实负载、测试覆盖不足等 | `MessageInboundHandler.java`, `NettySessionManager.java` |

---

## 第 5 章：消息发送主链路

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 5-01 | `sendMessage` 入口主要做了哪几类事情？ | 基础 | 主链路拆分 | 幂等拦截、权限校验、分配 seq、组装消息、异步投递 MQ、失败兜底、返回 ACK | `MessageServiceImpl.java` |
| 5-02 | 为什么要引入 `clientMsgId`？ | 进阶 | 幂等设计 | 客户端重试幂等；服务端用 `im:msg:dedup:{sessionId}:{clientMsgId}` 防重复投递 | `MessageServiceImpl.java` |
| 5-03 | 会话内有序是怎么保证的？ | 进阶 | 顺序性 | Redis `im:seq:{sessionId}` 递增生成 seq；MQ 使用 `asyncSendOrderly(..., sessionId)` | `MessageServiceImpl.java` |
| 5-04 | 单聊和群聊的 MQ 投递策略有什么区别？ | 进阶 | 路由与扩散 | 单聊根据 `im:route:{userId}` 选择目标节点或 OFFLINE；群聊按节点聚合用户后批量投递 | `MessageServiceImpl.java` |
| 5-05 | 为什么消息发送不等待 MySQL 写入完成，而是先异步投递？ | 进阶 | 性能与解耦 | 发送链路低延迟优先；持久化异步化；失败由兜底表和重试任务保障 | `MessageServiceImpl.java` |
| 5-06 | `msg_failover` 是用来做什么的？ | 进阶 | 容错兜底 | MQ 投递失败时落本地兜底表，后续定时补偿重发 | `MessageServiceImpl.java` |
| 5-07 | 这个链路里有哪些缓存优化？ | 进阶 | 热点优化 | 用户、好友、会话、群成员列表缓存；过期时间随机化防雪崩 | `MessageServiceImpl.java` |
| 5-08 | 这套消息链路里最容易被面试官追问的三个点是什么？ | 深挖 | 面试风险点 | 幂等 TTL、顺序性边界、MQ 失败兜底是否丢消息、ACK 与持久化关系 | `MessageServiceImpl.java` |

---

## 第 6 章：可靠性与离线消息

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 6-01 | 这个项目的消息可靠性目标是什么？ | 基础 | 可靠性模型 | 至少一次投递 + 消费端去重，而不是严格 exactly-once | `RESUME.md`, `MessageServiceImpl.java` |
| 6-02 | 发送端、MQ 层、消费端分别做了哪些可靠性措施？ | 进阶 | 分层保障 | 发送端 `clientMsgId` 幂等；MQ 失败写 failover；消费端唯一索引/去重 | `MessageServiceImpl.java`, 相关消费端代码 |
| 6-03 | 离线消息为什么采用 Redis ZSet + MySQL 的热冷分层？ | 进阶 | 存储分层 | 热数据快速拉取，冷数据长期保存；兼顾性能和成本 | `OfflineMessageServiceImpl.java` |
| 6-04 | 未读数为什么要 Redis Hash + MySQL 双写？ | 进阶 | 热点状态设计 | Redis 负责高频读写，MySQL 负责持久化和重建兜底 | `UnreadCountServiceImpl.java` |
| 6-05 | 用户上线后离线消息是怎么拉取的？ | 进阶 | 数据流转 | 按 seq 游标增量拉取；热数据优先，不足再补冷数据 | `OfflineMessageServiceImpl.java` |
| 6-06 | 离线消息清理任务为什么要加分布式锁？ | 进阶 | 定时任务并发控制 | 避免多实例重复清理同一批数据 | `OfflineMessageCleanTask.java` |
| 6-07 | 这套离线消息设计的边界和风险点是什么？ | 深挖 | 工程反思 | 热冷数据一致性、游标边界、补数逻辑复杂、跨天清理与并发竞争 | `OfflineMessageServiceImpl.java`, `OfflineMessageCleanTask.java` |

---

## 第 7 章：红包系统

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 7-01 | 红包系统为什么要用 RocketMQ 事务消息？ | 进阶 | 事务一致性 | 保证扣款和创建红包的一致性，避免只扣钱不发包或反之 | `RedPacketServiceImpl.java` |
| 7-02 | 抢红包为什么用 Redis Lua，而不是 Java 里多次调用 Redis？ | 进阶 | 原子性 | `SISMEMBER + LPOP + SADD` 原子执行，避免超发和重复抢 | `scripts/grab_redpacket.lua`, `GetRedPacketServiceImpl.java` |
| 7-03 | 红包过期退款是怎么做的？ | 进阶 | 延迟补偿 | 通过延迟消息触发退款和状态收敛 | `RedPacketServiceImpl.java` |
| 7-04 | 红包模块最有价值的两个面试亮点是什么？ | 深挖 | 亮点提炼 | 事务消息保证业务一致性；Lua 保证并发下原子扣减与幂等 | `RedPacketServiceImpl.java`, `grab_redpacket.lua` |

---

## 第 8 章：联系人与会话

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 8-01 | 好友同意链路为什么要加 Redisson 分布式锁？ | 进阶 | 并发控制 | 防止双方同时操作导致关系重复创建；锁 key 用 `min/max userId` 保证互斥 | `ContactServiceImpl.java` |
| 8-02 | 联系人模块里事务主要保护了哪些操作？ | 进阶 | 事务边界 | 双向好友关系插入、会话创建、状态更新等需要一起成功 | `ContactServiceImpl.java` |
| 8-03 | 为什么联系人模块还要调用 Messaging / RTC？ | 进阶 | 跨服务协作 | 建立会话、发送通知、同步状态变化 | `ContactServiceImpl.java` |
| 8-04 | 联系人这块如果面试官问“缺点是什么”，可以怎么答？ | 深挖 | 反思能力 | 分布式事务边界较松，更多依赖业务补偿和服务降级；跨服务链路更复杂 | `ContactServiceImpl.java` |

---

## 第 9 章：压测、测试与工程反思

| # | 题目 | 难度 | 考察要点 | 参考答案要点 | 关键代码 |
|---|---|---|---|---|---|
| 9-01 | 你当前压测主要覆盖了哪些链路，没覆盖哪些链路？ | 基础 | 真实性校准 | 重点是消息发送/链路测试；Netty 长连接容量和事件循环专项压测仍不足 | `scripts/LOAD_TEST_PLAN.md`, `ws_load_test.js`, `im_chain_test.js` |
| 9-02 | 为什么说“只压发送接口”不能等价于“整个 IM 系统都压过了”？ | 进阶 | 压测边界 | 长连接容量、心跳、断连重连、广播风暴、节点切换都未充分覆盖 | `LOAD_TEST_PLAN.md` |
| 9-03 | 现在新增的 P2C 和 RTC 节点负载这块有什么测试？ | 进阶 | 新增测试点 | `P2CLoadBalancerTest`、`NettySessionManagerTest`；验证选节点和 Redis 负载更新逻辑 | `P2CLoadBalancerTest.java`, `NettySessionManagerTest.java` |
| 9-04 | 如果继续补测试，你最想补哪三类？ | 进阶 | 测试规划 | Netty 长连接容量、弱网/重连、节点宕机与路由脏数据恢复 | 测试脚本与 RTC 模块 |
| 9-05 | 这个项目最需要在面试里“讲真话”的三个地方是什么？ | 深挖 | 面试策略 | 一致性哈希历史设计、压测覆盖范围、Gateway 白名单特殊处理 | `P2CLoadBalancer.java`, `RateLimitFilter.java`, `LOAD_TEST_PLAN.md` |

---

## 题库统计

| 章节 | 题数 |
|---|---:|
| 第 1 章：项目全景与架构设计 | 6 |
| 第 2 章：登录认证服务 | 9 |
| 第 3 章：API 网关与认证 | 7 |
| 第 4 章：RTC / Netty 长连接 | 8 |
| 第 5 章：消息发送主链路 | 8 |
| 第 6 章：可靠性与离线消息 | 7 |
| 第 7 章：红包系统 | 4 |
| 第 8 章：联系人与会话 | 4 |
| 第 9 章：压测、测试与工程反思 | 5 |
| **合计** | **58** |
