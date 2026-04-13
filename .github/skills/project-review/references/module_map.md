# InfiniteChat 项目复习模块地图

> 说明：本地图用于帮助 `project-review` skill 将每章问题映射到真实代码。  
> 原则：文档与代码冲突时，以代码为准。

---

## 第 1 章：项目全景与架构设计

- `RESUME.md`
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
- `RealTimeCommunication/src/main/java/com/wangyutao/realtimecommunication/websocket/WebSocketTokenAuthHandler.java`
- `RealTimeCommunication/src/main/java/com/wangyutao/realtimecommunication/websocket/MessageInboundHandler.java`
- `RealTimeCommunication/src/main/java/com/wangyutao/realtimecommunication/websocket/NettySessionManager.java`
- `RealTimeCommunication/src/main/resources/application.yml`

---

## 第 5 章：消息发送主链路

- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/MessageServiceImpl.java`
- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/UserServiceImpl.java`
- `Messaging/src/main/java/com/wangyutao/messaging/mapper/MsgFailoverMapper.java`

---

## 第 6 章：可靠性与离线消息

- `OfflineService/src/main/java/com/wangyutao/offlineservice/service/impl/OfflineMessageServiceImpl.java`
- `OfflineService/src/main/java/com/wangyutao/offlineservice/service/impl/UnreadCountServiceImpl.java`
- `OfflineService/src/main/java/com/wangyutao/offlineservice/task/OfflineMessageCleanTask.java`
- `scripts/ws_load_test.js`
- `scripts/im_chain_test.js`

---

## 第 7 章：红包系统

- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/RedPacketServiceImpl.java`
- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/GetRedPacketServiceImpl.java`
- `scripts/grab_redpacket.lua`
- `scripts/redis_warmup_redpacket.sh`

---

## 第 8 章：联系人与会话

- `contact/src/main/java/com/wangyutao/contact/service/impl/ContactServiceImpl.java`
- `Messaging/src/main/java/com/wangyutao/messaging/service/impl/MessageServiceImpl.java`

---

## 第 9 章：压测、测试与工程反思

- `scripts/LOAD_TEST_PLAN.md`
- `scripts/ws_load_test.js`
- `scripts/im_chain_test.js`
- `AuthenticationService/src/test/java/com/wangyutao/authenticationservice/loderBalance/P2CLoadBalancerTest.java`
- `RealTimeCommunication/src/test/java/com/wangyutao/realtimecommunication/websocket/NettySessionManagerTest.java`

---

## 当前需要特别提醒的“代码高于文档”点

1. RTC 节点分配
- 当前代码是 `P2CLoadBalancer`
- 不要再讲一致性哈希已闭环生效

2. 网关限流
- 当前代码是 Redis Lua 固定窗口
- 不是 Redisson RateLimiter

3. 登录链路
- 当前支持双 Token、Redis 中 RT、Lua compare-and-delete、登出时间戳

4. 长连接接入
- 登录后返回 `nettyUrl`
- 客户端直连 RTC
- 握手成功后写 Redis 路由 `im:route:{userId}`
