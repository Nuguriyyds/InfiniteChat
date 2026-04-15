# InfiniteChat — 简历项目经历

---

## 项目介绍

基于微服务架构的分布式即时通信系统，涵盖用户认证、消息收发、实时推送、离线消息、朋友圈等完整 IM 业务链路。采用 Spring Cloud 微服务治理，RocketMQ 异步解耦，Netty 实现 WebSocket 长连接推送，Redis 多数据结构支撑缓存/路由/限流/分布式锁等核心场景。

---

## 核心技术栈

Spring Boot、Spring Cloud Gateway、Netty、RocketMQ、Redis/Redisson、MySQL、MyBatis-Plus、Nacos、OpenFeign、JWT

---

## 核心功能

- **API 网关与认证**：基于 Spring Cloud Gateway 实现全局 IP 限流（Redisson RateLimiter）和 JWT 鉴权双层过滤链，设计登出时间戳机制（token.iat < logoutTime）解决 JWT 无状态下的登出失效问题。

- **分布式消息引擎**：消息发送链路通过雪花 ID + 会话级递增 seq 保证全局唯一与会话内有序；利用 RocketMQ 有序消息按目标节点 Tag 定向投递，发送链路不等待 MySQL 写入，异步持久化降低发送延迟。

- **消息可靠性保障**：客户端 clientMsgId + Redis SETNX 发送端幂等，MQ 投递失败写入本地消息表（im_msg_failover）定时重试，消费端 MySQL 唯一索引兜底，实现端到端"至少一次投递 + 消费端去重"。

- **Netty 长连接推送**：基于 Netty 构建 WebSocket 服务器，Boss/Worker/Business 三级线程模型，Pipeline 含心跳检测（IdleStateHandler 90s）、Token 鉴权（一次性 Handler）、消息推送；连接时注册 Redis 路由，断连时清除，支持多节点部署与 Pub/Sub 单设备踢人。

- **离线消息热冷分层**：推送失败的消息通过 MQ 回流至离线服务，采用 Redis ZSet（热，3 天）+ MySQL（冷，7 天）双层存储，未读计数基于 Redis Hash + MySQL 双写；用户上线后按 seq 游标增量拉取，分批清理任务通过分布式锁保证单实例执行。

- **红包系统**：发红包通过 RocketMQ 事务消息保证扣款与创建红包的原子性；抢红包核心逻辑封装为 Redis Lua 脚本（SISMEMBER + LPOP + SADD）原子执行，防止超发；24 小时延迟消息触发过期退款。

- **联系人关系管理**：好友同意操作使用 Redisson 分布式锁（锁 key 取 min/max 保证双方互斥）+ 事务双向插入，通过 Feign 降级调用 Messaging 创建会话、RTC 推送通知；用户注册通过 MQ 事件驱动异步创建 AI 助手。
