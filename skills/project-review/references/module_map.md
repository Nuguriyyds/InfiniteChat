# InfiniteChat 项目复习模块地图

> 说明：本地图用于帮助 `project-review` skill 将每章问题映射到真实代码。  
> 原则：文档与代码冲突时，以代码为准。

---

## 第 1 章：项目全景与架构设计

- `RESUME.md` / `简历.md`
- `AuthenticationService/authentication_DEV_SPEC.md`
- `scripts/LOAD_TEST_PLAN.md`
- 服务目录：
  - `AuthenticationService`
  - `Gateway`
  - `RealTimeCommunication`
  - `Messaging`
  - `OfflineService`
  - `contact`

---

## 第 2 章：登录认证服务

- `AuthenticationService/src/main/java/com/wangyutao/authenticationservice/controller/UserController.java`
- `AuthenticationService/src/main/java/com/wangyutao/authenticationservice/service/impl/UserServiceImpl.java`
- `AuthenticationService/src/main/java/com/wangyutao/authenticationservice/service/JwtBlacklistService.java`
- `AuthenticationService/src/main/java/com/wangyutao/authenticationservice/loderBalance/P2CLoadBalancer.java`
- `AuthenticationService/src/main/java/com/wangyutao/authenticationservice/utils/RedisLockExecutor.java`

---

## 第 3 章：API 网关与认证

- `Gateway/src/main/java/com/wangyutao/gateway/filter/RateLimitFilter.java`
- `Gateway/src/main/java/com/wangyutao/gateway/filter/AuthorizeFilter.java`
- `Gateway/src/main/resources/application.yml`

---

## 第 4 章：RTC / Netty 长连接

- `RealTimeCommunication/src/main/java/com/wangyutao/realtimecommunication/websocket/NettyServer.java`
  - Pipeline 定义：第 78-85 行（Handler 顺序和线程归属）
  - 优雅停机 @PreDestroy：第 94-127 行
- `RealTimeCommunication/src/main/java/com/wangyutao/realtimecommunication/websocket/WebSocketTokenAuthHandler.java`
  - token 从 URL query 提取：第 38 行
- `RealTimeCommunication/src/main/java/com/wangyutao/realtimecommunication/websocket/MessageInboundHandler.java`
  - 路由注册 Lua 脚本：第 42-45 行
  - channelRead0 路由续期：第 67 行
  - 心跳 / idle 处理：第 96-140 行
  - HandshakeComplete 挤占登录：第 116-138 行
  - ACK 处理：第 142-164 行
- `RealTimeCommunication/src/main/java/com/wangyutao/realtimecommunication/websocket/NettySessionManager.java`
  - CAS remove：第 ~ 负责 remove(userId, channel)
  - ZSet 负载上报：第 106-111 行
- `RealTimeCommunication/src/main/resources/application.yml`
- `scripts/ws_load_test.js`
  - 指数退避公式：第 211-228 行

---

## 第 5 章：消息发送主链路

- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/MessageServiceImpl.java`
  - Lua 去重 + seq 分配脚本：第 562-572 行
  - asyncSendOrderly 投递：第 153 行
  - 缓存读取（multiGet）：第 221-235 行
  - seq 空洞处理（/sync）：第 574-607 行
- `Messaging/src/main/java/com/wangyutao/messaging/listener/MessageStoreListener.java`
  - 消费模式 CONCURRENTLY：第 35 行
  - 群聊路由分发（multiGet + 按节点分组）：第 97-149 行
  - syncSendOrderly 推送：第 88 行
- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/UserSessionServiceImpl.java`
  - ACK 只增不减（.lt 条件）：第 30-36 行
- `Messaging/src/main/java/com/wangyutao/messaging/config/MqSendExecutorConfig.java`
  - 线程池参数（核心16/最大64/队列8192/CallerRunsPolicy）：第 19-29 行
- `Messaging/src/main/java/com/wangyutao/messaging/utils/IdGenerator.java`
  - 雪花算法时钟回拨处理
- `Messaging/src/main/java/com/wangyutao/messaging/mapper/MsgFailoverMapper.java`
- `Messaging/src/main/java/com/wangyutao/messaging/task/MsgFailoverRetryTask.java`
- `Messaging/src/main/resources/application.yml`
  - Tomcat 线程池：第 2-7 行
  - Redis 连接池（优化后 max-active=128）：第 19-28 行
  - MySQL HikariCP（maximum-pool-size=30）：第 37-44 行
- `scripts/send_msg.lua`
  - clientMsgId 格式（防并发重复）：第 59 行
- `scripts/LOAD_TEST_PLAN.md`
  - 三轮压测命令和数据

---

## 第 6 章：可靠性与离线消息

- `OfflineService/src/main/java/com/wangyutao/offlineservice/service/impl/OfflineMessageServiceImpl.java`
  - 热数据写入 storeToRedis：第 129-142 行
  - MySQL 写入 storeToMySQL（非原子问题）：第 198-221 行
  - 热数据覆盖判断 isRedisHotRangeCovered：第 255-270 行
  - Redis 拉取（消费即删除）：第 144-169 行
  - 拉取限流（INCR+EXPIRE 非原子）：第 241-253 行
- `OfflineService/src/main/java/com/wangyutao/offlineservice/service/impl/UnreadCountServiceImpl.java`
  - HINCRBY 原子自增：第 62-72 行
  - GREATEST 防负数：第 ~ decrementUnreadCount
- `OfflineService/src/main/java/com/wangyutao/offlineservice/task/OfflineMessageCleanTask.java`
  - SETNX 分布式锁：第 29 行
  - 分批清理 sleep：第 50-53 行
- `OfflineService/src/main/java/com/wangyutao/offlineservice/listener/OfflineNotifyListener.java`
  - trim(-200, -1) 保留最新200条
- `scripts/ws_load_test.js`
- `scripts/im_chain_test.js`
  - 离线场景验证流程：第 713-777 行

---

## 第 7 章：红包系统

- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/RedPacketServiceImpl.java`
  - 余额扣减 CAS SQL
  - 二倍均值法金额预计算
  - 延迟消息过期（syncSendDeliverTimeMills）
  - handleExpiredRedPacket CAS 状态更新：第 362-408 行
- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/RedPacketReceiveServiceImpl.java`
  - CompletableFuture + FUTURE_CACHE
  - corrId 设计
  - Redis 极速拦截：第 66 行
- `Messaging/src/main/java/com/wangyutao/messaging/listener/redpacket/RedPacketTxListener.java`
  - executeLocalTransaction（执行 Lua + 唤醒 future + 返回 COMMIT/ROLLBACK）
  - checkLocalTransaction（Broker 回查 Pending Hash）：第 94-113 行
- `Messaging/src/main/java/com/wangyutao/messaging/listener/redpacket/RedPacketReceiveListener.java`
  - 事务外清理 Pending Hash
  - doSaveReceiveRecord 三层幂等
- `Messaging/src/main/java/com/wangyutao/messaging/constants/RedPacketConstants.java`
  - UNIFIED_GRAB_LUA 四合一 Lua 脚本
- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/GetRedPacketServiceImpl.java`
  - N+1 消除（listByIds + HashMap）
  - 缓存 key 含 pageNum
- `Messaging/src/main/java/com/wangyutao/messaging/template/RedPacketTxRocketMQTemplate.java`
  - @ExtRocketMQTemplateConfiguration 独立 Producer Group
- `Messaging/src/main/java/com/wangyutao/messaging/mapper/UserBalanceMapper.java`
  - balance >= #{amount} CAS 扣减：第 21-22 行
- `scripts/grab_redpacket.lua`（如存在：Lua 脚本独立文件）
- `scripts/redis_warmup_redpacket.sh`（如存在：Redis 预热脚本）

---

## 第 8 章：联系人与会话

- `contact/src/main/java/com/wangyutao/contact/service/impl/ContactServiceImpl.java`
- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/MessageServiceImpl.java`

---

## 第 9 章：压测、测试与工程反思

- `scripts/LOAD_TEST_PLAN.md`
- `scripts/ws_load_test.js`
  - STAT 输出格式（active/attempted/errors/opened/closed/unexpected）
  - 指数退避 buildReconnectDelay：第 211-228 行
- `scripts/im_chain_test.js`
  - Online/Offline 场景验证
- `scripts/send_msg.lua`（wrk2 压测脚本）
- `scripts/sync_msg.lua`（消息同步压测脚本）
- `AuthenticationService/src/test/java/com/wangyutao/authenticationservice/loderBalance/P2CLoadBalancerTest.java`
- `RealTimeCommunication/src/test/java/com/wangyutao/realtimecommunication/websocket/NettySessionManagerTest.java`

---

## 当前需要特别提醒的"代码高于文档"点

### 1. RTC 节点分配
- 当前代码是 `P2CLoadBalancer`，基于 Redis ZSet 中的 `im:rtc:load` 负载数据
- **不要再讲一致性哈希已闭环生效**

### 2. 网关限流
- 当前代码是 Redis Lua 固定窗口（INCR + EXPIRE）
- 不是 Redisson RateLimiter

### 3. 登录链路
- 当前支持双 Token（AT=3h / RT=7天）、Redis 中存 RT、Lua compare-and-delete、登出时间戳
- 登录返回 `nettyUrl` 用于客户端直连 RTC

### 4. 长连接接入
- 客户端直连 RTC（不经过 Gateway）
- 握手成功后写 Redis 路由 `im:route:{userId}`，TTL=1200s（20分钟）
- 每次收到消息 `redisTemplate.expire` 续期

### 5. 消息链路
- `MessageStoreListener` 是 CONCURRENTLY 模式（不是 ORDERLY），但发送端用 sendOrderly
- 去掉了 `@Transactional`（第1轮压测瓶颈根因）
- Redis 连接池 max-active=128（第3轮压测优化结果）

### 6. 红包系统
- 抢红包用 RocketMQ **事务消息**（不是普通消息 + 本地消息表）
- 有独立的 `RedPacketTxRocketMQTemplate`（独立 Producer Group）
- Pending Hash 是关键中间状态（连接 Lua 执行结果和 DB 落库）

### 7. 简历数字必须能说清来源
- 2000→126 失败重试：固定1s延迟3轮vs指数退避压测日志对比
- 8s恢复：kill -9 到 active=1000/1000 的时间差
- 491→994 RPS：三轮 wrk2 压测迭代
- P99 48s→80ms：对应三轮瓶颈修复
- 800 RPS P99 51ms：红包接口压测（抢红包，非发红包）
