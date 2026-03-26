# Developer Specification (DEV_SPEC)

> 版本：1.0  
> 模块：Gateway  
> 生成方式：基于 InfiniteChat 现有源码逆向分析  
> 基线日期：2026-03-26

## 目录

- 模块概述
- 核心特性
- 技术选型
- 测试方案
- 系统架构与模块设计
- 路由模型与 Redis 协议
- 接口契约
- 核心方法分析
- 安全设计与已知约束
- 可扩展性建议

---

## 1. 模块概述

`gateway` 模块对应仓库中的 `Gateway` 微服务，承担 InfiniteChat 的统一 HTTP 流量入口职责。它位于所有 HTTP 请求之前，同时保留一个代理式 WebSocket 入口用于调试/回退，负责全局限流、JWT 鉴权、登出失效校验、用户身份透传，以及基于 Nacos/静态地址的路由转发。

### 1.1 模块职责

- 提供统一入口端口 `10010`，承接客户端对所有业务模块的 HTTP 请求。
- 对非白名单路径执行全局 JWT 验签，并校验 Redis 中的登出时间戳。
- 在鉴权通过后先移除客户端自带的 `X-User-Id` / `X-Internal-Auth`，再向下游注入可信头，供业务服务识别当前用户与请求来源。
- 在鉴权前按客户端 IP 执行全局限流，保护边缘入口。
- 基于 `lb://` + Nacos 发现将请求转发到 `AuthenticationService`、`MessagingService`、`OfflineService`、`ContactService`、`moment-service`、`RealTimeCommunicationService`。
- 保留 WebSocket 代理入口 `/api/v1/chat/message`，可将握手转发到 RTC 的物理 Netty 端口 `9101`。
- 暴露 Swagger / OpenAPI 文档相关路径的白名单能力，便于联调和调试。

### 1.2 非职责边界

- 不负责签发 AccessToken / RefreshToken，Token 生成与续签由 `AuthenticationService` 完成。
- 不负责业务域鉴权，例如“是否群成员”“是否好友”“是否可查看会话”，这些由下游业务服务处理。
- 不负责 WebSocket 长连接生命周期管理，连接注册、踢人、心跳、会话路由由 `RealTimeCommunication` 模块处理。
- 不负责持久化业务数据；只读取 Redis 中的登出时间戳，并写入/读取限流计数器。
- 不负责细粒度的接口级权限模型，例如管理员接口、运营接口、多租户隔离。

### 1.3 设计原则

- 边界前移：把限流和身份校验放在网关入口，尽量在业务服务之前失败返回。
- 响应式优先：使用 WebFlux + Reactor Netty 作为网关运行模型，避免在边缘层引入 Servlet 阻塞线程模型。
- 最小共享状态：JWT 仍保持无状态，只借助 Redis 保存“最后登出时间戳”这一最小共享状态。
- 身份透传标准化：使用 `X-User-Id` + `X-Internal-Auth` 向下游传播用户身份与内部可信来源，避免各模块重复解析 JWT。
- HTTP / WebSocket 分离：HTTP 统一走 Gateway，WebSocket 正式接入由 `AuthenticationService` 返回 RTC 节点直连地址；Gateway 侧保留代理式 WS 入口用于调试与回退。

---

## 2. 核心特性

### 2.1 双层全局过滤链

- `RateLimitFilter` 执行顺序为 `-2`，先于鉴权过滤器运行。
- `AuthorizeFilter` 执行顺序为 `-1`，在限流通过后执行 JWT 验证。
- 这意味着无论请求的 Token 是否有效，都会先经过 IP 限流。

### 2.2 基于 Reactive Redis + Lua 的固定窗口限流

- 限流维度为客户端 IP。
- Redis Key 为 `rate_limit:ip:{ip}`。
- 固定窗口大小为 `60` 秒。
- 当前阈值配置为 `100000` 次/分钟，明显带有压测环境特征。
- Lua 脚本执行 `INCR -> 首次 EXPIRE -> 超阈值返回 0 -> 否则返回 1`，避免并发下 `INCR` 与 `EXPIRE` 分离带来的竞态。

### 2.3 JWT HS512 无状态验签

- 网关使用 JJWT 0.11.5 对传入 JWT 执行 HS512 验签。
- Claims 读取关注三个核心字段：
  - `sub`：用户 ID
  - `iat`：签发时间
  - `exp`：过期时间
- Token 来源支持两种：
  - `Authorization` 请求头
  - `token` 查询参数
- 若 `Authorization` 以 `Bearer ` 开头，网关会自动去掉前缀后再验签。

### 2.4 基于登出时间戳的旧 Token 失效

- 网关从 Redis 读取 `jwt:logout:{userId}`。
- 若 `logoutAt > 0` 且 `token.iat < logoutAt`，则判定该 Token 为“登出前签发的旧 Token”并拒绝访问。
- 该机制允许用户重新登录后拿到新 Token，而无需清理历史登出记录。

### 2.5 下游身份透传

- 鉴权通过后，网关会先删除客户端自带的 `X-User-Id` / `X-Internal-Auth`，再写入可信的 `X-User-Id` / `X-Internal-Auth`。
- 下游多个模块的控制器都显式依赖该头部：
  - `AuthenticationService` 的登出、头像更新
  - `Messaging` 的发消息、ACK、建群、红包等
  - `OfflineService` 的拉取/已读/通知接口
  - `ContactService` 的好友关系接口
  - `moment-service` 的朋友圈接口
- 当前只有 `AuthenticationService` 显式校验 `X-Internal-Auth`；其他模块仍主要依赖网络边界与 `X-User-Id`。

### 2.6 HTTP / WebSocket 双通道设计

- HTTP 业务请求主要通过 `lb://` 交给 Nacos + Spring Cloud LoadBalancer。
- 正式客户端 WebSocket 契约不经过 Gateway，而是直连登录返回的 RTC 节点地址。
- Gateway 仍保留 `NettyWebSocketRouter` 代理入口，便于单机调试、压测和兼容旧脚本。

### 2.7 统一跨域与连接池配置

- 启用 `spring.main.web-application-type=reactive`。
- 全局 CORS 放开 `/**`，允许任意 Origin Pattern、任意方法、任意请求头。
- HTTP Client 连接池最大连接数为 `1000`。
- 连接获取超时为 `3000ms`，连接建立超时为 `2000ms`。

---

## 3. 技术选型

### 3.1 技术栈总览

| 类别 | 选型 | 版本 | 用途 |
|------|------|------|------|
| 语言 / 运行时 | Java | 1.8 | 网关服务运行语言 |
| 基础框架 | Spring Boot | 2.6.13 | 应用启动与配置装配 |
| 网关框架 | Spring Cloud Gateway | 2021.0.5 | 响应式 API 网关 |
| 响应式栈 | Spring WebFlux / Reactor Netty | 跟随 Boot / Cloud | 非阻塞请求处理与转发 |
| 服务发现 | Spring Cloud Alibaba Nacos Discovery | 2021.0.5.0 | 下游服务注册发现 |
| 客户端负载 | Spring Cloud LoadBalancer | 2021.0.5 | `lb://` 路由负载转发 |
| Redis 访问 | Spring Data Redis Reactive | 跟随 Boot | 限流脚本与登出时间戳读取 |
| Redis 状态存储 | Redis | 外部服务 | 限流计数器、登出时间戳 |
| JWT 验签 | JJWT | 0.11.5 | HS512 验签与 Claims 解析 |
| 字符串工具 | Apache Commons Lang3 | 跟随依赖管理 | `StringUtils` 判空等 |
| 本地缓存依赖 | Caffeine | 跟随依赖管理 | 当前模块已引入但主链路未使用 |
| Redisson | redisson-spring-boot-starter | 3.23.5 | 当前模块提供 Bean 配置，但主链路未调用 |
| Netty 线程定制 | Reactor `LoopResources` | Reactor Netty | 自定义网关 IO 线程组 |

### 3.2 关键选型说明

#### 3.2.1 选择 Spring Cloud Gateway 而非 Servlet 网关

- 当前系统已经拆分为多微服务，需要统一入口、统一路径路由和统一过滤能力。
- Gateway 天然支持 `GlobalFilter`、`lb://` 路由、WebSocket 代理和 Reactor 非阻塞模型。
- 对于 IM 场景，边缘入口既有大量 HTTP 请求，也有 WebSocket 握手，Gateway 更适合承担统一入口角色。

#### 3.2.2 选择 Reactive Redis + Lua 实现限流

- 当前网关是 WebFlux 响应式模型，主链路优先选择 `ReactiveStringRedisTemplate`，避免把阻塞式 Redis 调用直接放进 EventLoop。
- 使用 Lua 将 `INCR` 与 `EXPIRE` 合并为原子操作，避免首个请求时设置过期时间失败导致脏 Key 永久累积。
- 相比直接在应用层拼 `GET/SET/EXPIRE`，Lua 更容易保证并发一致性。

#### 3.2.3 选择 JWT + Redis 登出时间戳组合

- 纯 JWT 不能天然支持“主动登出后旧 Token 立刻作废”。
- 纯 Session 则会让网关每次请求都依赖中心化会话查询，失去 JWT 的无状态优势。
- 当前方案只在 Redis 存 `logoutAt`，保持“无状态验签 + 最小共享状态”的折中设计。

#### 3.2.4 选择 `X-User-Id` + `X-Internal-Auth` 作为统一身份透传头

- 下游业务服务不必重复解析 JWT，减少重复代码与密钥耦合。
- 控制器层可直接通过 `@RequestHeader("X-User-Id")` 获取当前登录用户。
- `X-Internal-Auth` 作为“请求确实由 Gateway 转发而来”的补充证明，可用于下游服务二次校验来源。
- 这种模式仍要求所有业务服务必须部署在 Gateway 之后，否则存在头部伪造风险。

#### 3.2.5 Gateway 保留静态 WebSocket 代理入口

- 当前 RTC 同时暴露：
  - HTTP 控制面端口 `8083`
  - Netty WebSocket 数据面端口 `9101`
- Gateway 的 WebSocket 路由未走 `lb://RealTimeCommunicationService`，而是直接写死到本机 `9101`。
- 在方案 2 下，这个入口不再是正式客户端接入地址，而是保留的代理式入口。
- 这仍然要求 Gateway 与 RTC 在同机部署，或至少保证 `127.0.0.1:9101` 可直达 RTC Netty。

#### 3.2.6 自定义 Gateway IO 线程资源

- `NettyConfig` 显式创建 `LoopResources.create("gateway-io", 4, true)`。
- 这使得网关 IO 线程数量固定为 4，便于边缘层线程模型可控、命名可观测。
- 代价是线程数与 CPU 核数不再完全自适应，需要在高并发场景下结合机器规格调优。

#### 3.2.7 冗余依赖与保留配置

- `RedissonConfig` 在当前 Gateway 代码中已创建 `RedissonClient` Bean，但 `RateLimitFilter` 和 `AuthorizeFilter` 并未使用它。
- `Caffeine` 依赖在当前模块同样未参与核心链路。
- 这说明网关存在“从旧设计迁移后保留依赖”的现象，规范需要以现状代码为准而非仅看依赖清单。

---

## 4. 测试方案

### 4.1 当前仓库已有验证资产

- `Gateway/src/test/.../GatewayApplicationTests.java`
  - 当前仅有 Spring 上下文启动冒烟测试。
- `LOCAL_VERIFICATION.md`
  - 覆盖以 Gateway 为主的本地链路联调步骤。
- `TEST_AND_DEPLOYMENT_GUIDE.md`
  - 包含 Gateway 端口、wscat WebSocket 测试、部署说明。
- `scripts/ws_load_test.js` + `scripts/WS_LOAD_TEST.md`
  - 提供默认以 RTC 直连地址为目标的 WebSocket 压测脚本，也可通过参数切回 Gateway 代理入口。
- `scripts/im_chain_test.js` + `scripts/IM_CHAIN_TEST.md`
  - 提供 HTTP 走 Gateway、WS 默认直连 RTC 的组合链路验证脚本。

### 4.2 推荐测试分层

#### 4.2.1 单元测试

- `JwtUtil.parse`
  - 合法 Token、篡改 Token、过期 Token、空 Token。
- `RateLimitFilter.getClientIp`
  - `X-Forwarded-For`、`X-Real-IP`、`RemoteAddress`、多 IP 逗号分隔。
- `RateLimitFilter.isWhitelist`
  - Swagger、OpenAPI、`/api/message/**` 等路径白名单判定。
- `JwtBlacklistService.getLogoutTime`
  - Key 存在、不存在、值异常。
- `AuthorizeFilter`
  - 白名单路径、缺失 Token、Bearer 头、Query Token、登出时间戳拦截。

#### 4.2.2 集成测试

- 受保护 HTTP 请求携带合法 AT，经网关后可命中下游并带上 `X-User-Id` 与 `X-Internal-Auth`。
- 受保护 HTTP 请求不带 Token 时被网关返回 `401`。
- 受保护 HTTP 请求携带过期/伪造 Token 时返回带 JSON Body 的 `401`。
- Redis 中存在 `jwt:logout:{userId}` 且晚于 `iat` 时，请求被拒绝。
- 同一 IP 在窗口内超阈值后触发 `429`。
- `/api/v1/chat/message?token=...` 可经网关代理转发到 RTC 完成握手。

#### 4.2.3 契约测试

- 白名单矩阵契约：
  - `/api/v1/user/noToken/**`
  - `/api/v1/user/refreshToken`
  - `/swagger/**`
  - `/v3/api-docs/**`
  - `/webjars/**`
- 下游身份透传契约：
  - 鉴权成功后必须注入 `X-User-Id` 与 `X-Internal-Auth`
  - 鉴权失败不得注入
  - `AuthenticationService` 受保护接口应校验 `X-Internal-Auth`
- WebSocket 契约：
  - Gateway 可识别 Header / Query Token
  - 但 RTC 当前实际要求 Query `token`
- 路由契约：
  - `/api/v1/chat/message` 必须优先命中 WebSocket 精确路由
  - `/api/v1/chat/**` 其余路径走 RTC HTTP 路由

### 4.3 关键验收用例

- `POST /api/v1/user/noToken/loginPwd` 可不带 Token 直接通过网关。
- `GET /api/contact/list` 不带 Token 时，网关返回 `401`。
- `GET /api/contact/list?token={validAT}` 可以通过网关并命中下游。
- 旧 AT 在用户登出后再次访问，被网关拒绝。
- `GET /api/v1/chat/sys/onlineUser` 携带合法 AT 可以通过网关访问 RTC HTTP 接口。
- `GET ws://{gateway-host}:10010/api/v1/chat/message?token={validAT}` 作为代理入口可握手成功。
- `GET ws://{gateway-host}:10010/api/v1/chat/message` 不带 token 时被拒绝。
- 当前代码下 `/api/message/**` 被视为白名单，说明消息业务链路不会经过网关鉴权与限流。

### 4.4 当前测试缺口

- 没有针对 `GlobalFilter` 行为的自动化单元测试或集成测试。
- 没有针对路由优先级的自动化验证。
- 没有覆盖 WebSocket 经 Gateway 转发的自动化握手测试。
- 没有验证 `/api/message/**` 临时白名单是否会带来头部伪造风险的自动化用例。
- 没有针对 Redis 故障、Nacos 服务缺失、静态 WS 目标不可达等异常路径的回归测试。

---

## 5. 系统架构与模块设计

### 5.1 运行时架构

```text
Client
  |
  | HTTP
  v
Gateway (:10010, WebFlux)
  |
  |- RateLimitFilter (order -2)
  |    |- 解析客户端 IP
  |    |- Redis Lua 固定窗口限流
  |
  |- AuthorizeFilter (order -1)
  |    |- 白名单放行
  |    |- 提取 Authorization / query.token
  |    |- JJWT HS512 验签
  |    |- Redis 读取 jwt:logout:{userId}
  |    |- 移除伪造头并注入 X-User-Id / X-Internal-Auth
  |
  |- Routes
  |    |- /api/v1/user/**         -> lb://AuthenticationService
  |    |- /api/message/**         -> lb://MessagingService
  |    |- /api/offline/**         -> lb://OfflineService
  |    |- /api/contact/**         -> lb://ContactService
  |    |- /api/moment/**          -> lb://moment-service
  |    |- /api/v1/chat/message    -> ws://127.0.0.1:9101 (代理入口)
  |    |- /api/v1/chat/**         -> lb://RealTimeCommunicationService
  v
Downstream Services

Client
  |
  | WebSocket
  v
Selected RTC Node (:9101, direct connect)
```

### 5.2 包结构设计

| 包 / 资源 | 职责 |
|------|------|
| `config` | Netty 线程资源与 Redisson 客户端配置 |
| `filter` | 全局限流与全局鉴权过滤器 |
| `service` | 与 Redis 登出时间戳交互的轻量服务 |
| `utils` | JWT 解析工具 |
| `resources/application*.yml` | 端口、中间件、CORS、路由规则 |
| `GatewayApplication` | 应用启动入口 |

### 5.3 核心组件说明

#### 5.3.1 `GatewayApplication`

- Spring Boot 启动入口。
- 当前没有额外启用注解，依赖 Spring Boot 自动配置和 starter 机制完成 Gateway / Nacos / Redis 装配。

#### 5.3.2 `RateLimitFilter`

- 全局限流过滤器，顺序为 `-2`。
- 负责：
  - 白名单路径跳过
  - 客户端 IP 识别
  - 执行 Redis Lua 脚本
  - 在阈值超限时直接返回 `429`
- 当前实现不依赖 `RedissonClient`，完全走 `ReactiveStringRedisTemplate`。

#### 5.3.3 `AuthorizeFilter`

- 全局鉴权过滤器，顺序为 `-1`。
- 负责：
  - 白名单判断
  - 从 Header / Query 提取 Token
  - JWT 验签与 Claims 解析
  - 查询 Redis 登出时间戳
  - 移除客户端伪造的透传头
  - 向下游注入 `X-User-Id` 与 `X-Internal-Auth`
  - 失败时返回 `401`

#### 5.3.4 `JwtBlacklistService`

- 通过 `ReactiveStringRedisTemplate` 读取 `jwt:logout:{userId}`。
- 若 Key 不存在则返回 `0L`，供 `AuthorizeFilter` 判定为“没有登出记录”。
- 不负责编写黑名单，只负责读侧能力。

#### 5.3.5 `JwtUtil`

- 封装 JJWT 解析逻辑。
- 采用硬编码密钥 `infinitechat_secret_key_2026_safe_and_secure_for_im_system_64_bits`。
- 与 `AuthenticationService`、`RealTimeCommunication` 使用相同密钥字符串，形成跨模块隐式契约。

#### 5.3.6 `NettyConfig`

- 通过 `WebServerFactoryCustomizer<NettyReactiveWebServerFactory>` 自定义 Gateway 自身的 Reactor Netty 服务端线程资源。
- 线程名前缀为 `gateway-io`。
- 当前 IO 线程数固定为 `4`。

#### 5.3.7 `RedissonConfig`

- 创建 `RedissonClient` Bean，连接共享 Redis。
- 连接池、超时、重试参数均已配置。
- 但在当前 Gateway 主链路代码中未被使用，属于保留配置。

### 5.4 路由设计

| 路由 ID | 匹配路径 | 目标 | 路由方式 | 备注 |
|------|------|------|------|------|
| `AuthenticationService` | `/api/v1/user/**` | `AuthenticationService` | `lb://` | 认证、登录、登出、上传 URL |
| `MessagingService` | `/api/message/**` | `MessagingService` | `lb://` | 当前被过滤器临时白名单放行 |
| `OfflineService` | `/api/offline/**` | `OfflineService` | `lb://` | 离线拉取、已读、未读 |
| `ContactService` | `/api/contact/**` | `ContactService` | `lb://` | 好友、联系人 |
| `moment-service` | `/api/moment/**` | `moment-service` | `lb://` | 朋友圈 |
| `NettyWebSocketRouter` | `/api/v1/chat/message` | `ws://127.0.0.1:9101` | 静态 WS | 保留的代理入口，必须位于 RTC HTTP 路由之前 |
| `RTC_Http_Router` | `/api/v1/chat/**` | `RealTimeCommunicationService` | `lb://` | 在线人数、系统通知推送等 |

### 5.5 关键业务流程

#### 5.5.1 受保护 HTTP 请求流程

```text
1. 客户端请求 Gateway protected path
2. RateLimitFilter:
   - 解析 IP
   - 执行 Redis Lua 限流脚本
   - 若超限则返回 429
3. AuthorizeFilter:
   - 提取 Authorization / query.token
   - 解析 JWT -> 得到 sub / iat
   - 查询 jwt:logout:{userId}
   - 若 iat < logoutAt 则返回 401
4. 移除客户端伪造的 `X-User-Id` / `X-Internal-Auth`，再写入可信头
5. 根据 Path 路由到下游服务
6. 下游控制器从 `X-User-Id` 读取当前用户身份，`AuthenticationService` 额外校验 `X-Internal-Auth`
```

#### 5.5.2 公开认证请求流程

```text
1. 客户端访问 `/api/v1/user/noToken/**` 或 `/api/v1/user/refreshToken`（`POST` 主入口，`GET` 兼容）
2. RateLimitFilter:
   - 仍然执行 IP 限流（refreshToken/noToken 不在限流白名单中）
3. AuthorizeFilter:
   - 命中白名单，跳过 JWT 校验
4. 路由到 AuthenticationService
```

#### 5.5.3 WebSocket 握手经 Gateway 流程（保留代理入口）

```text
1. 客户端访问 ws://{gateway-host}:10010/api/v1/chat/message?token={AT}
2. RateLimitFilter 对握手请求执行 IP 限流
3. AuthorizeFilter:
   - 从 query.token 提取 AT
   - 验签并校验 logoutAt
4. 路由命中 NettyWebSocketRouter
5. Gateway 将握手转发到 ws://127.0.0.1:9101/api/v1/chat/message?token={AT}
6. RTC 的 WebSocketTokenAuthHandler 再次从 query.token 校验 JWT
7. 握手成功，连接升级为 WebSocket

补充说明：
- 该流程仍然可用，但在方案 2 下不再作为正式客户端接入契约。
- 正式客户端应使用 `AuthenticationService` 基于目标 RTC 实例 metadata 生成的 `nettyUrl` 直连 RTC 节点。
```

#### 5.5.4 登出后旧 Token 拦截流程

```text
1. AuthenticationService 登出时写入 jwt:logout:{userId}=logoutAt
2. 用户随后携带旧 AccessToken 再次访问 Gateway
3. Gateway 解析 token.iat
4. Gateway 读取 logoutAt
5. 若 token.iat < logoutAt，直接返回 401
6. 下游业务服务不会收到该请求
```

---

## 6. 路由模型与 Redis 协议

### 6.1 网关路由模型

#### 6.1.1 入口路径分层

| 层次 | 路径模式 | 语义 |
|------|------|------|
| 公开认证入口 | `/api/v1/user/noToken/**` | 注册、登录、验证码等公开接口 |
| RefreshToken 入口 | `/api/v1/user/refreshToken` | 续签接口，`POST` 主入口，`GET` 兼容放行 |
| 受保护业务入口 | `/api/contact/**`、`/api/offline/**`、`/api/moment/**`、`/api/v1/chat/**` | 需要 Gateway 鉴权 |
| 消息业务入口 | `/api/message/**` | 当前代码中临时白名单放行 |
| 文档入口 | `/swagger/**`、`/v3/api-docs/**`、`/webjars/**` | 联调辅助路径 |
| WebSocket 代理入口 | `/api/v1/chat/message` | Gateway 保留的 RTC 代理接入点 |

#### 6.1.2 下游服务协作模型

| 下游模块 | 服务名 | 身份来源 | 是否依赖网关注入 `X-User-Id` | 是否校验 `X-Internal-Auth` |
|------|------|------|------|------|
| AuthenticationService | `AuthenticationService` | Header | 是，登出/更新头像依赖 | 是，`GatewayTrustInterceptor` 显式校验 |
| Messaging | `MessagingService` | Header | 是，发送、ACK、建群、红包依赖 | 否，当前未实现 |
| Offline | `OfflineService` | Header | 是 | 否，当前未实现 |
| Contact | `ContactService` | Header | 是 | 否，当前未实现 |
| Moment | `moment-service` | Header | 是 | 否，当前未实现 |
| RTC HTTP | `RealTimeCommunicationService` | 当前多数接口不取 `X-User-Id` | 否为主 | 否 |
| RTC WebSocket | 选中的 RTC 节点 `:9101` | Query `token` | 否，RTC 自己再验 JWT | 不适用 |

### 6.2 Redis Key 设计

| Key | 类型 | TTL | 生产者 | 消费者 | 用途 |
|------|------|------|------|------|------|
| `jwt:logout:{userId}` | String | 2 小时（与 AT 最大有效期对齐） | AuthenticationService | Gateway / RTC | 主动登出后旧 AT 失效判定 |
| `rate_limit:ip:{ip}` | String Counter | 60 秒 | Gateway | Gateway | IP 固定窗口限流 |

### 6.3 Redis 协议细节

#### 6.3.1 登出时间戳

- Value 为毫秒时间戳字符串，例如 `1742822400123`。
- 网关读取后调用 `Long.parseLong` 转成长整型。
- 若 Key 不存在，`JwtBlacklistService` 返回 `0L`。
- 这要求写入方 `AuthenticationService` 必须保证值可被 `Long.parseLong` 正常解析。

#### 6.3.2 限流计数器

- Key 以客户端 IP 为粒度。
- 首次访问窗口内：
  - `INCR` 结果为 `1`
  - 同时 `EXPIRE 60`
- 同窗口内计数持续累加。
- 当计数值大于阈值 `100000` 时返回 `0`，网关拦截。

### 6.4 网关内部协议细节

#### 6.4.1 Token 提取顺序

1. 优先读取 `Authorization`
2. 若为空，再读取查询参数 `token`
3. 若字符串以 `Bearer ` 开头，则去掉前缀
4. 若最终仍为空，则视为未登录

#### 6.4.2 客户端 IP 解析顺序

1. `X-Forwarded-For`
2. `X-Real-IP`
3. `exchange.getRequest().getRemoteAddress()`
4. 若 IP 中包含逗号，仅取第一个值

#### 6.4.3 身份透传头

- Gateway 会先删除客户端同名头，再写入以下可信头：
  - `X-User-Id: {jwt.sub}`
  - `X-Internal-Auth: {security.internal-request.secret}`
- `X-User-Id` 用于标识当前登录用户。
- `X-Internal-Auth` 用于标识“该请求确实由 Gateway 转发”。
- 当前只有 `AuthenticationService` 显式校验 `X-Internal-Auth`。

---

## 7. 接口契约

### 7.1 网关统一拦截响应模型

网关自身不定义统一业务成功包装，成功请求会被直接转发到下游，由下游返回其自己的响应体。网关只在限流与鉴权失败时构造响应。

#### 7.1.1 限流失败响应

- HTTP 状态码：`429 Too Many Requests`
- Content-Type：`application/json;charset=UTF-8`

```json
{
  "code": 429,
  "message": "请求过于频繁，请稍后再试",
  "data": null
}
```

#### 7.1.2 Token 非法或过期响应

- HTTP 状态码：`401 Unauthorized`
- Content-Type：`application/json;charset=UTF-8`

```json
{
  "code": 401,
  "message": "Token已过期或不合法，请重新登录",
  "data": null
}
```

#### 7.1.3 缺失 Token 响应

- HTTP 状态码：`401 Unauthorized`
- 当前实现直接 `setComplete()`，不写 JSON Body。
- 因而“缺失 Token”和“Token 非法”在响应体层面并不一致。

### 7.2 入口路径总览

| 路径 | 方法 | 当前是否经网关鉴权 | 当前是否经网关限流 | 目标 | 说明 |
|------|------|------|------|------|------|
| `/api/v1/user/noToken/**` | `*` | 否 | 是 | Auth | 公开认证接口 |
| `/api/v1/user/refreshToken` | `POST` | 否 | 是 | Auth | RefreshToken 主入口 |
| `/api/v1/user/refreshToken` | `GET` | 否 | 是 | Auth | RefreshToken 兼容入口 |
| `/api/v1/user/**` 其余路径 | `*` | 是 | 是 | Auth | 受保护认证接口 |
| `/api/contact/**` | `*` | 是 | 是 | Contact | 联系人业务 |
| `/api/offline/**` | `*` | 是 | 是 | Offline | 离线消息业务 |
| `/api/moment/**` | `*` | 是 | 是 | moment | 朋友圈业务 |
| `/api/message/**` | `*` | 否（临时白名单） | 否（临时白名单） | Messaging | 当前实现带安全缺口 |
| `/api/v1/chat/message` | `WS` 握手 | 是 | 是 | RTC Netty | Gateway 保留的代理式 WS 握手 |
| `/api/v1/chat/**` 其余路径 | `*` | 是 | 是 | RTC HTTP | 在线用户/系统通知等 |

### 7.3 受保护 HTTP 契约

#### 7.3.1 客户端入参契约

- 推荐 Header：
  - `Authorization: Bearer {accessToken}`
- 当前兼容写法：
  - `Authorization: {accessToken}`
  - `?token={accessToken}`

#### 7.3.2 网关注入契约

- 鉴权通过后，网关会向下游请求补充：

```http
X-User-Id: {jwt.sub}
X-Internal-Auth: {gateway internal secret}
```

- `AuthorizeFilter` 会先删除客户端自带的同名头，再写入可信值。
- 下游若依赖当前登录用户身份，应优先读取该头。
- `AuthenticationService` 当前会进一步校验 `X-Internal-Auth`。

#### 7.3.3 示例

客户端请求：

```http
GET /api/contact/list HTTP/1.1
Authorization: Bearer eyJ...
```

Gateway 转发到下游时的等价请求语义：

```http
GET /api/contact/list HTTP/1.1
Authorization: Bearer eyJ...
X-User-Id: 1912345678901234567
X-Internal-Auth: {configured internal secret}
```

### 7.4 详细路径契约

#### 7.4.1 `/api/v1/user/noToken/**`

- 目标服务：`AuthenticationService`
- 是否鉴权：否
- 是否限流：是
- 典型接口：
  - `POST /api/v1/user/noToken/sms`
  - `POST /api/v1/user/noToken/register`
  - `POST /api/v1/user/noToken/loginPwd`
  - `POST /api/v1/user/noToken/loginCode`

#### 7.4.2 `/api/v1/user/refreshToken`

- 目标服务：`AuthenticationService`
- 是否鉴权：否
- 是否限流：是
- 支持方法：
  - `POST`：主入口
  - `GET`：兼容入口
- 当前下游期望：
  - `X-Refresh-Token: {refreshToken}`（推荐）
  - `Authorization: {refreshToken}`（兼容）
  - `Authorization: Bearer {refreshToken}`（兼容）
- 注意：
  - Gateway 只是放行，不参与 RefreshToken 解析。
  - `POST` 是当前标准契约，`GET` 仅用于旧客户端过渡。

#### 7.4.3 `/api/v1/user/**` 受保护路径

- 目标服务：`AuthenticationService`
- 是否鉴权：是
- 是否限流：是
- 典型接口：
  - `POST /api/v1/user/logout`
  - `PATCH /api/v1/user/avatar`
  - `GET /api/v1/user/uploadUrl`
- 下游默认通过 `X-User-Id` 识别当前登录用户。
- `AuthenticationService` 还会校验 `X-Internal-Auth`，拒绝绕过 Gateway 的直连请求。

#### 7.4.4 `/api/contact/**`

- 目标服务：`ContactService`
- 是否鉴权：是
- 是否限流：是
- 典型接口：
  - `/add`
  - `/accept/{requestId}`
  - `/reject/{requestId}`
  - `/requests`
  - `/list`
  - `/search`
- `ContactController` 全面依赖 `X-User-Id`。

#### 7.4.5 `/api/offline/**`

- 目标服务：`OfflineService`
- 是否鉴权：是
- 是否限流：是
- 典型接口：
  - `/pull`
  - `/unread`
  - `/read`
  - `/clear/{sessionId}`
  - `/notifications`
- `OfflineMessageController` 全面依赖 `X-User-Id`。

#### 7.4.6 `/api/moment/**`

- 目标服务：`moment-service`
- 是否鉴权：是
- 是否限流：是
- 典型接口：
  - `/publish`
  - `/like`
  - `/unlike`
  - `/comment`
  - `/friends`
  - `/detail/{momentId}`
- `MomentController` 全面依赖 `X-User-Id`。

#### 7.4.7 `/api/message/**`

- 目标服务：`MessagingService`
- 当前代码中的过滤器行为：
  - `RateLimitFilter.isWhitelist(path)` 返回 `true`
  - `AuthorizeFilter.filter(...)` 直接放行
- 这意味着：
  - Gateway 不会对消息业务做 JWT 校验
  - Gateway 不会注入 `X-User-Id` / `X-Internal-Auth`
  - 若客户端自己伪造 `X-User-Id`，下游当前可能直接信任
- 该白名单注释明确标注为“压测临时白名单，压测完删除”。

#### 7.4.8 `/api/v1/chat/message` WebSocket 握手

**入口**

- `GET ws://{gateway-host}:10010/api/v1/chat/message?token={accessToken}`

**Gateway 行为**

- 先限流，再鉴权，再转发到 `ws://127.0.0.1:9101`

**下游 RTC 行为**

- `WebSocketTokenAuthHandler` 只从 Query 中读取 `token`
- 同样执行 JWT 验签与 `logoutAt` 比对

**重要结论**

- 这是 Gateway 保留的代理式 WS 入口，不是方案 2 下的正式客户端接入地址。
- 虽然 Gateway 支持从 `Authorization` Header 中取 Token，但当前 WS 下游 RTC 不支持 Header 取 Token。
- 因此无论是代理入口还是 RTC 直连入口，真正稳定的 WS Token 契约仍然是 `?token={accessToken}`。

#### 7.4.9 `/api/v1/chat/**` HTTP 路径

- 目标服务：`RealTimeCommunicationService`
- 是否鉴权：是
- 是否限流：是
- 典型接口：
  - `GET /api/v1/chat/sys/onlineUser`
  - `POST /api/v1/chat/push/newSession/{userId}`
  - `POST /api/v1/chat/push/friendApplication/{userId}`
  - `POST /api/v1/chat/push/newGroupSession/{userId}`
  - `POST /api/v1/chat/push/moment`

### 7.5 CORS 与连接配置契约

- 所有路径匹配 `/**` 都适用全局 CORS 设置。
- 允许：
  - 任意 `Origin Pattern`
  - 任意 Method
  - 任意 Header
  - `allowCredentials=true`
- 这意味着浏览器端跨域能力较宽松，但也要求生产环境谨慎收敛 Origin 配置。

---

## 8. 核心方法分析

### 8.1 `RateLimitFilter.filter(ServerWebExchange, GatewayFilterChain)`

**定位**

- 网关请求进入后的第一道全局过滤器。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `exchange` | `ServerWebExchange` | 当前请求与响应上下文 |
| `chain` | `GatewayFilterChain` | 后续过滤器 / 路由链 |

**输出**

- `Mono<Void>`

**数据流转**

1. 读取 `exchange.getRequest().getPath().value()` 得到 `path`。
2. 调用 `isWhitelist(path)` 判断是否跳过限流。
3. 若不跳过，调用 `getClientIp(exchange)` 解析客户端 IP。
4. 组装 Redis Key：`rate_limit:ip:{ip}`。
5. 以 `RATE_INTERVAL=60`、`RATE_LIMIT=100000` 执行 Lua 脚本。
6. `result == 0L` 时返回 `429` JSON。
7. 否则调用 `chain.filter(exchange)` 交给后续过滤器。

**关键语义**

- 限流在鉴权之前执行。
- 当前是固定窗口而非滑动窗口或令牌桶。
- 白名单路径不会产生限流 Redis 计数。

### 8.2 `AuthorizeFilter.filter(ServerWebExchange, GatewayFilterChain)`

**定位**

- 网关全局 JWT 鉴权核心入口。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `exchange` | `ServerWebExchange` | 当前请求上下文 |
| `chain` | `GatewayFilterChain` | 后续链路 |

**输出**

- `Mono<Void>`

**数据流转**

1. 取请求路径 `path`。
2. 如果命中白名单：
   - `/api/v1/user/noToken/.*`
   - `/api/v1/user/refreshToken`
   - `/swagger`
   - `/v3/api-docs`
   - `/webjars`
   - `/api/message`
   则直接放行。
3. 从 `Authorization` 读取 Token；若为空，则尝试读取查询参数 `token`。
4. 若以 `Bearer ` 开头，则裁剪前缀。
5. Token 为空时直接返回 `401` 且无 Body。
6. 调用 `JwtUtil.parse(token)` 解析 Claims。
7. 取 `sub -> userId`、`iat -> tokenIssuedAt`。
8. 调用 `jwtBlacklistService.getLogoutTime(userId)` 获取 `logoutAt`。
9. 若 `logoutAt > 0 && tokenIssuedAt < logoutAt`，返回 `401` JSON。
10. 否则先移除客户端自带的 `X-User-Id` / `X-Internal-Auth`，再写入 Gateway 可信头。
11. 构造新 `exchange` 并执行 `chain.filter(mutatedExchange)`。

**关键语义**

- 认证通过后不删除客户端原始 `Authorization`，而是追加身份头。
- 透传前会先清理同名透传头，避免客户端伪造 `X-User-Id` / `X-Internal-Auth`。
- 读 Redis 登出时间戳是异步 `Mono<Long>` 流程，与 WebFlux 一致。
- 过滤器同时为 HTTP 与 WebSocket 握手请求生效。

### 8.3 `JwtBlacklistService.getLogoutTime(String userId)`

**定位**

- 网关对“登出时间戳黑名单”的只读访问封装。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | `String` | JWT `sub` 中的用户 ID |

**输出**

- `Mono<Long>`

**数据流转**

1. 拼接 Redis Key：`jwt:logout:{userId}`。
2. `reactiveRedisTemplate.opsForValue().get(key)` 读取字符串值。
3. 若存在，执行 `Long.parseLong`。
4. 若不存在，`defaultIfEmpty(0L)` 返回 0。

**关键语义**

- `0L` 不是业务上的真实登出时间，而是“无登出记录”的哨兵值。
- 若 Redis 内值格式异常，会在解析阶段抛出错误并中断鉴权流程。

### 8.4 `JwtUtil.parse(String token)`

**定位**

- 网关侧 JWT 解析与验签工具。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `token` | `String` | 去除 `Bearer ` 前缀后的 JWT 字符串 |

**输出**

- `Claims` 或 `null`

**数据流转**

1. 判空，空字符串直接返回 `null`。
2. 创建 `Jwts.parserBuilder()`。
3. 注入共享密钥字节数组。
4. 调用 `parseClaimsJws(token)` 完成：
   - 签名校验
   - 格式校验
   - 过期检查
5. 成功则返回 `Claims`。
6. 失败时捕获 `JwtException` 或泛型异常，记录日志并返回 `null`。

**关键语义**

- 该方法不会抛出受检异常给上层，而是将解析失败折叠成 `null`。
- 上层过滤器必须显式判空后做 401 拒绝。

### 8.5 `RateLimitFilter.getClientIp(ServerWebExchange)`

**定位**

- 网关侧限流分组键的来源解析方法。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `exchange` | `ServerWebExchange` | 请求上下文 |

**输出**

- `String`，客户端 IP

**数据流转**

1. 先读 `X-Forwarded-For`。
2. 若为空或 `unknown`，再读 `X-Real-IP`。
3. 若仍为空，则回退到 `RemoteAddress`。
4. 若结果包含逗号，截取首个 IP。
5. 返回最终 IP 字符串。

**关键语义**

- 默认信任前置代理写入的转发头。
- 若 Gateway 直接暴露公网，这个解析顺序是合理的；若前面还有不可信代理，需要额外校验。

### 8.6 `NettyConfig.nettyCustomizer()`

**定位**

- Gateway 服务端 Reactor Netty 线程资源定制点。

**输入**

- 无显式业务参数。

**输出**

- `WebServerFactoryCustomizer<NettyReactiveWebServerFactory>`

**数据流转**

1. Spring Boot 创建 `NettyReactiveWebServerFactory`。
2. `nettyCustomizer` 在工厂层增加 server customizer。
3. 服务端运行时使用 `LoopResources.create("gateway-io", 4, true)`。

**关键语义**

- 该方法影响的是 Gateway 自身的服务端监听线程，而不是下游业务服务线程。
- 对高并发场景，线程数固定为 4 是明确的容量约束点。

---

## 9. 安全设计与已知约束

### 9.1 已实现的安全设计

| 能力 | 实现方式 | 防御目标 |
|------|------|------|
| 入口限流 | Redis Lua 固定窗口 IP 限流 | 防高频恶意请求与暴力打点 |
| JWT 验签 | JJWT + HS512 对称密钥 | 防 Token 篡改 |
| 主动登出失效 | `jwt:logout:{userId}` + `iat` 比对 | 防旧 AT 被继续复用 |
| 身份透传 | 鉴权后注入 `X-User-Id` | 避免下游重复验签 |
| 内部来源可信标记 | `X-Internal-Auth` + Auth 二次校验 | 防伪造身份头直连 AuthenticationService |
| Swagger 白名单 | 文档路径跳过鉴权 | 支持联调与接口发现 |
| WebSocket 再校验 | RTC 握手时二次验签 | 防仅依赖 Gateway 单点信任 |

### 9.2 当前实现约束

#### 9.2.1 `/api/message/**` 仍在临时白名单中

- `AuthorizeFilter` 和 `RateLimitFilter` 都对白名单放行 `/api/message`。
- 这意味着消息模块当前绕过了网关的 JWT 鉴权和限流。
- 消息控制器中大量接口依赖 `X-User-Id`，如果客户端自行伪造该 Header，下游可能直接信任。

#### 9.2.2 `X-Internal-Auth` 当前主要由 AuthenticationService 显式校验

- Gateway 已经会为受保护 HTTP 路径统一注入 `X-Internal-Auth`。
- 但当前只有 `AuthenticationService` 显式校验该头；其他业务模块仍主要依赖网络隔离与 `X-User-Id`。
- 因而“内部可信来源校验”在系统范围内尚未完全收敛为统一标准。

#### 9.2.3 Gateway 代理入口与 RTC 直连入口的鉴权语义不完全一致

- Gateway 支持从 Header 或 Query 读取 Token。
- RTC `WebSocketTokenAuthHandler` 只支持 Query `token`。
- 在方案 2 下这不再影响正式客户端契约，但保留的 Gateway 代理入口和 RTC 直连入口在实现上仍不完全对称。

#### 9.2.4 保留的 WebSocket 代理路由硬编码为 `127.0.0.1:9101`

- 当前 `NettyWebSocketRouter` 写死 `ws://127.0.0.1:9101`。
- 这要求 Gateway 与 RTC Netty 端口必须在同机或本地网络命名空间内可直达。
- 由于方案 2 的正式客户端不依赖该入口，这个约束主要影响调试和回退场景。

#### 9.2.5 Gateway 代理入口与正式客户端入口并存

- 在方案 2 下，`AuthenticationService` 返回的 `nettyUrl` 是正式客户端入口，且该地址应由目标 RTC 实例 metadata 动态组装。
- Gateway 中的 `/api/v1/chat/message` 仅作为保留代理入口存在。
- 因此项目内部需要明确区分：
  - 正式客户端契约
  - 调试 / 回退契约

#### 9.2.6 JWT 密钥在多个模块硬编码复制

- Gateway、AuthenticationService、RealTimeCommunication 都依赖同一密钥字符串。
- 当前通过代码复制维持一致，而不是通过安全配置中心或环境变量统一注入。
- 一旦任意一处修改未同步，跨模块鉴权会整体失效。

#### 9.2.7 缺失 Token 与非法 Token 的响应体不一致

- 缺失 Token 时返回空 Body 的 `401`。
- 非法 / 过期 Token 时返回 JSON Body 的 `401`。
- 这会增加前端错误处理分支和接口契约复杂度。

#### 9.2.8 Query Token 可用于所有受保护路径

- `AuthorizeFilter` 对所有非白名单路径都支持 `?token=` 回退。
- 这虽然便于 WebSocket 握手，但也意味着普通 HTTP 接口理论上可以把 AT 暴露在 URL 中。
- URL 可能进入浏览器历史、代理日志和监控系统，存在泄露风险。

#### 9.2.9 Redis 是鉴权与限流的强依赖

- 限流脚本执行失败时没有显式降级或兜底响应策略。
- 登出时间戳读取异常时也没有兜底放行/拒绝策略。
- 因此 Redis 故障会直接影响 Gateway 可用性。

#### 9.2.10 CORS 配置较宽松

- 当前允许任意 Origin Pattern、任意 Header、任意 Method，且 `allowCredentials=true`。
- 虽然便于开发期联调，但生产环境需要收敛来源域名。

#### 9.2.11 依赖与实现存在漂移

- `RedissonConfig` 与 `Caffeine` 仍保留在 Gateway 模块中，但主链路未使用。
- 历史复盘文档中还存在“Gateway 使用 Redisson 限流”的表述，而现代码已改为 Reactive Redis + Lua。
- 文档与实现若不同步，容易误导后续维护者。

---

## 10. 可扩展性建议

### 10.1 移除 `/api/message/**` 临时白名单

- 将消息业务重新纳入网关鉴权与限流。
- 对压测场景改为单独的 profile、专用压测网关或仅放开特定测试接口。
- 若必须保留压测旁路，应采用显式开关配置而不是硬编码白名单。

### 10.2 统一 WebSocket 接入协议

- 方案 2 下，正式客户端入口应统一为：
  - `ws://{selected-rtc-host}:{metadata.ws-port}{metadata.ws-path}?token={AT}`
- `AuthenticationService` 返回的 `nettyUrl` 应始终是基于 RTC 实例 metadata 组装出的完整直连地址，不包含 query token。
- Gateway 中的 `/api/v1/chat/message` 保留为调试 / 回退入口，不再作为正式客户端协议的一部分。

### 10.3 统一身份传播机制

- 保留 `X-User-Id` 作为下游业务身份来源。
- 将 `X-Internal-Auth` 校验从 `AuthenticationService` 推广到其他依赖 Header 身份的下游模块。
- 中长期可演进到更强的服务间信任方案，例如：
  - 内部网段隔离
  - Service Mesh / mTLS
  - 内部签名头
- 避免“只要能直连业务服务就能伪造 `X-User-Id`”。

### 10.4 路由与过滤策略配置化

- 将白名单、限流阈值、WS 路由目标、Header 名称从代码迁移到配置文件或配置中心。
- 支持按路径、按用户、按业务类型配置差异化限流。
- 支持按环境切换：
  - 开发环境
  - 压测环境
  - 生产环境

### 10.5 安全与配置治理

- 将 JWT 密钥、Redis 凭据、Nacos 凭据迁移到环境变量或配置中心。
- 对 Query Token 仅在 WebSocket 路径启用，普通 HTTP 路径禁用 URL Token 回退。
- 收敛 CORS 允许域名。

### 10.6 自动化测试补齐

- 为 `RateLimitFilter`、`AuthorizeFilter` 建立 WebFlux 单元测试。
- 补充基于 `WebTestClient` 的集成测试，验证：
  - 路由命中
  - `X-User-Id` / `X-Internal-Auth` 注入
  - 401 / 429 响应
  - 白名单矩阵
- 补充两类 WebSocket 测试：
  - RTC 直连握手测试（正式客户端契约）
  - Gateway 代理握手测试（保留入口）
