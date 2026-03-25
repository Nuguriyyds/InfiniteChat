# InfiniteChat — 简历项目经历

---

## 项目介绍

基于微服务架构的分布式即时通信系统，涵盖用户认证、消息收发、实时推送、离线消息、红包、朋友圈等完整 IM 业务链路。采用 Spring Cloud 微服务治理（7 个服务节点），RocketMQ 异步解耦，Netty 实现 WebSocket 长连接推送，Redis 多数据结构支撑缓存/路由/限流/分布式锁等核心场景。部署于 2 核 4G（中间件）+ 2 核 8G（业务）双节点环境，经阶梯式压测验证核心链路稳定性。

---

## 核心技术栈

Spring Boot、Spring Cloud Gateway、Netty、RocketMQ、Redis/Redisson、MySQL、MyBatis-Plus、Nacos、OpenFeign、JWT

---

## 核心功能

- **API 网关与认证**：基于 Spring Cloud Gateway 实现双层过滤链——RateLimitFilter 通过 Redis Lua 脚本实现固定窗口 IP 限流（原子 INCR + EXPIRE），AuthorizeFilter 执行 JWT HS512 验签 + Redis 登出时间戳校验（token.iat < logoutTime），解决 JWT 无状态下的登出失效问题。

- **分布式消息引擎**：消息发送链路通过雪花 ID + 会话级递增 seq 保证全局唯一与会话内有序；利用 RocketMQ 有序消息按目标节点 Tag 定向投递，发送链路不等待 MySQL 写入，异步持久化降低发送延迟。

- **消息可靠性保障**：客户端 clientMsgId + Redis SETNX 发送端幂等，MQ 投递失败写入本地消息表（im_msg_failover）定时重试，消费端 MySQL 唯一索引兜底，实现端到端"至少一次投递 + 消费端去重"。

- **Netty 长连接推送**：基于 Netty 构建 WebSocket 服务器，Boss/Worker/Business 三级线程模型，Pipeline 含心跳检测（IdleStateHandler 90s）、Token 鉴权（一次性 Handler）、消息推送；连接时注册 Redis 路由，断连时清除，支持多节点部署与 Pub/Sub 单设备踢人。

- **离线消息热冷分层**：推送失败的消息通过 MQ 回流至离线服务，采用 Redis ZSet（热，3 天）+ MySQL（冷，7 天）双层存储，未读计数基于 Redis Hash + MySQL 双写；用户上线后按 seq 游标增量拉取，分批清理任务通过分布式锁保证单实例执行。

- **红包系统**：发红包在本地事务中完成余额扣减（MySQL 行锁原子 CAS）、创建红包、记录流水，随后预计算金额列表存入 Redis List（普通红包平均分配，拼手气红包二倍均值法）。抢红包采用 RocketMQ 事务消息保证 Redis 预扣与 MySQL 落库的最终一致：半消息发送后在 executeLocalTransaction 中执行四合一 Lua 脚本（SISMEMBER 防重 + DECR 扣库存 + LPOP 弹金额 + SADD 标记 + HSET 暂存），CompletableFuture 同步返回结果；MQ 消费端异步落库（行锁扣减 + 领取记录 + 加余额 + 流水），Broker 回查 Pending Hash 精准补偿。24 小时延迟消息触发过期退款，CAS 保证幂等。

- **联系人关系管理**：好友同意操作使用 Redisson 分布式锁（锁 key 取 min/max 保证双方互斥）+ 事务双向插入，通过 Feign 降级调用 Messaging 创建会话、RTC 推送通知；用户注册通过 MQ 事件驱动异步创建 AI 助手。

## 性能压测与调优

- **登录接口调优**：基于 wrk2 对登录接口进行阶梯式压测（100→200→300 QPS），通过 top + jstack 定位瓶颈为 BCrypt CPU 密集计算（2 核机器 RUNNABLE 线程 90+，CPU 占用 96%，IO Wait 为 0）；将 BCrypt cost 从 10 降至 6，单机吞吐量从 17 QPS 提升至 200 QPS（约 12 倍），P99 从 49s 降至 333ms；确认拐点在 200 QPS，超过后 P50 从 139ms 飙升至 2.16s，为生产环境容量规划提供数据支撑。

- **消息发送链路调优**：针对单聊消息发送接口进行全链路压测。初始 200 QPS 下 P99 达 2.46s，排查发现 RocketMQ 同步投递阻塞 Tomcat 线程池，改为 asyncSendOrderly 异步顺序投递，失败由回调写入本地兜底表（im_msg_failover），延迟大幅下降；随后 500 QPS 压测中发现网关 CPU 占用 53%，通过 jstack 定位到 RateLimitFilter 与 AuthorizeFilter 白名单策略不一致，导致每个请求仍执行 HMAC-SHA512 验签与 Redis 登出时间戳查询，统一白名单后平均延迟从 3.3s 降至 26ms（128 倍）；进一步压测定位到 2 核机器上 7 个 JVM 共享 CPU 为硬件瓶颈（load average 7.57），最终单机 1200 QPS 稳定运行，P99 < 72ms，零错误零超时。

- **红包系统压测**：针对群聊拼手气红包（50 名额/个）进行阶梯式压测（wrk2，10→50→150→300→500→800→1200 QPS），验证四合一 Lua 脚本原子预扣 + RocketMQ 事务消息 + MySQL 异步落库的全链路并发安全性。2 核 8G 单节点下，800 QPS 时 P99 = 51ms、零错误；1200 QPS 出现拐点（P999 从 89ms 飙升至 804ms，瓶颈为 MQ 事务半消息提交尾部延迟与 CPU 调度抖动）。压测后数据一致性验证：零重复领取、零超发、领取总额与红包金额分文不差。