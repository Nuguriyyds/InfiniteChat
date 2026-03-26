# Developer Specification (DEV_SPEC)

> 版本：1.0  
> 模块：authentication  
> 生成方式：基于 InfiniteChat 现有源码逆向分析  
> 基线日期：2026-03-26

## 目录

- 模块概述
- 核心特性
- 技术选型
- 测试方案
- 系统架构与模块设计
- 数据模型与 Redis 协议
- 接口契约
- 核心方法分析
- 安全设计与已知约束
- 可扩展性建议

---

## 1. 模块概述

`authentication` 模块对应仓库中的 `AuthenticationService` 微服务，承担 InfiniteChat 的用户认证、账号初始化、Token 管理和账号基础资料维护能力。

### 1.1 模块职责

- 提供验证码发送、注册、密码登录、验证码登录、登出、RefreshToken 续签接口。
- 负责签发 AccessToken 与 RefreshToken，并将当前有效 RefreshToken 写入 Redis。
- 负责写入登出时间戳黑名单，配合 Gateway 与 RTC 让旧 Token 失效。
- 负责在登录成功后为客户端分配 RTC 节点，并返回可直接连接的 RTC WebSocket 地址 `nettyUrl`。
- 负责头像上传预签名地址生成与头像 URL 持久化。
- 负责初始化账号侧持久化数据，包括 `user` 与 `user_balance` 两张表。

### 1.2 非职责边界

- 不负责受保护 HTTP 接口的主鉴权，AccessToken 校验由 Gateway 完成。
- 但会对受保护接口做二次来源校验，要求请求携带 Gateway 注入的 `X-Internal-Auth` 内部可信头。
- 不负责 WebSocket 握手鉴权，RTC 模块会使用同一 JWT 密钥再次校验。
- 不负责文件内容中转上传，只生成 MinIO 预签名 URL。
- 不负责真实短信供应商接入，当前实现是“Redis 存验证码 + 邮件模拟短信”。

### 1.3 设计原则

- 边界前移：JWT 验签前置到 Gateway，认证服务专注账号域逻辑。
- 双重边界：Gateway 负责身份确认，AuthenticationService 负责校验“请求是否真的来自 Gateway”。
- 状态最小化：AccessToken 无状态，RefreshToken 仅在 Redis 保留一份当前有效值。
- 单用户整体失效：通过登出时间戳而非逐条 token 黑名单实现旧 AT 作废。
- 注册并发防重：按手机号维度加分布式锁，保证注册业务串行化。
- 节点路由稳定：RTC 节点通过 Nacos 发现并使用一致性哈希进行分配。

---

## 2. 核心特性

### 2.1 JWT 双 Token 体系

- AccessToken 用于业务接口鉴权，由 Gateway 与 RTC 校验。
- RefreshToken 仅用于 `/api/v1/user/refreshToken` 续签。
- 续签主入口为 `POST /api/v1/user/refreshToken`，同时保留 `GET` 兼容入口。
- 续签请求优先读取 `X-Refresh-Token`，兼容 `Authorization: {refreshToken}` 与 `Authorization: Bearer {refreshToken}`。
- AccessToken 有效期为 2 小时。
- RefreshToken 有效期为 7 天。
- Redis 中仅保留一份当前有效 RefreshToken，Key 为 `RT:{userId}`。

### 2.2 登出时间戳黑名单

- 登出时写入 `jwt:logout:{userId}`，值为毫秒级时间戳，TTL 为 2 小时。
- Gateway 在每次业务请求时比较 `token.iat` 与 `logoutAt`。
- RTC 在 WebSocket 握手时也做相同比较。
- 该方案支持“新 Token 自动生效，登出前签发的旧 Token 永久失效”。

### 2.3 RefreshToken 一次性消费

- 续签通过 Lua 脚本完成“比较 Redis 中 RT 是否等于客户端 RT + 删除旧 RT”。
- 同一个 RefreshToken 只能成功消费一次。
- 可以防止同一 RT 被并发重放换出多组新 Token。

### 2.4 注册并发防重

- 使用 `register:lock:{phone}` 作为 Redisson 分布式锁键。
- BCrypt 加密、雪花 ID 生成、昵称生成放在锁外，减少锁持有时间。
- 验证码校验与手机号查重放在锁内，避免 TOCTOU 竞态。
- 注册成功时同时写入 `user` 和 `user_balance`。

### 2.5 登录即返回 RTC 直连地址

- 登录成功后通过 Nacos 获取 RTC 实例列表。
- 使用一致性哈希将同一用户稳定分配到某个 RTC 节点。
- 读取选中 RTC 实例在 Nacos metadata 中声明的 `ws-protocol / ws-port / ws-path`，组装 `nettyUrl` 并返回给客户端。
- 当前默认示例为 `ws://{host}:9101/api/v1/chat/message`。

### 2.6 头像上传预签名

- 服务通过 MinIO SDK 生成 PUT 预签名 URL。
- 客户端直传对象存储，避免图片流量经过认证服务。
- 同时返回下载地址，便于前端立即保存头像 URL。

---

## 3. 技术选型

### 3.1 技术栈总览

| 类别 | 选型 | 版本 | 用途 |
|------|------|------|------|
| 语言/运行时 | Java | 1.8 | 服务运行语言 |
| 基础框架 | Spring Boot | 2.6.13 | Web 服务与依赖注入 |
| Web | `spring-boot-starter-web` | 跟随 Boot | Servlet 风格 REST 服务 |
| 参数校验 | `spring-boot-starter-validation` | 跟随 Boot | DTO 入参校验 |
| ORM | MyBatis-Plus | 3.5.2 | 用户与余额持久化 |
| 数据库 | MySQL | 运行时依赖 | `user` / `user_balance` 存储 |
| 缓存/状态 | Redis | 外部服务 | 验证码、RT、登出时间戳、路由协同 |
| 分布式锁 | Redisson | 3.16.0 | 注册防重 |
| JWT | JJWT | 0.9.1 | Token 签发与解析 |
| 密码加密 | Hutool BCrypt | 5.8.25 | 密码哈希与校验 |
| 服务发现 | Nacos Discovery | 2021.0.5.0 | RTC 节点发现 |
| 负载策略 | 自定义一致性哈希 | 自研 | RTC 节点分配 |
| 对象存储 | MinIO | 8.2.1 | 头像上传 |
| 邮件 | Commons Email + JavaMail | 1.4 / 1.4.7 | 模拟短信发送 |
| JSON | FastJson Converter | 1.2.76 | JSON 序列化 |

### 3.2 关键选型说明

#### 3.2.1 JWT 与 Redis 组合

- AccessToken 不落 Redis，避免每次请求额外查缓存。
- RefreshToken 落 Redis，提供可撤销、可比较、可续签的服务器端状态。
- 登出时间戳作为 AccessToken 的补充失效机制。

#### 3.2.2 Redisson 分布式锁

- 单靠数据库唯一索引无法约束验证码消费与双表写入语义。
- Redisson 锁将同一手机号注册流程串行化，错误语义更稳定。

#### 3.2.3 一致性哈希分配 RTC 节点

- 保证相同用户优先落到同一 RTC 节点。
- 扩缩容时迁移量小于普通取模方案。
- 160 个虚拟节点提升分布均匀性。

#### 3.2.4 MinIO 预签名上传

- 认证服务只负责授权，不承担二进制文件中转。
- 上传时效、对象范围由预签名 URL 约束。

---

## 4. 测试方案

### 4.1 当前仓库已有验证资产

- `AuthenticationService/src/test/.../AuthenticationServiceApplicationTests.java`
  - 当前仅有容器启动测试。
- `apifox_test_collection.json`
  - 包含认证相关的端到端请求集。
- `LOCAL_VERIFICATION.md`
  - 包含认证与全链路的手工验收步骤。

### 4.2 推荐测试分层

#### 4.2.1 单元测试

- `JwtUtil.generate/parse/getUserIdSafe`
- `RedisLockExecutor.executeWithLock`
- `ConsistentHashLoadBalancer.select`
- `IdGenerator.nextId`
- `JwtBlacklistService.addToBlacklist`

#### 4.2.2 集成测试

- 注册流程：验证码存在、手机号未注册、双表写入成功。
- 密码登录流程：用户存在、密码匹配、Redis 写入 RT。
- 验证码登录流程：验证码校验成功后删除 Redis 验证码。
- 续签流程：Lua 原子比对删除旧 RT，返回新双 Token。
- 登出流程：写入 `jwt:logout`、删除 RT、删除 `im:route`。

#### 4.2.3 契约测试

- 所有公开接口的入参校验。
- `X-Refresh-Token` / `Authorization` 与 `X-User-Id` / `X-Internal-Auth` 头部协作契约。
- `UserVO` 中 AccessToken 字段命名兼容性。

### 4.3 关键验收用例

- 同手机号并发注册，只允许一个成功。
- 同一 RefreshToken 并发续签，只允许一次成功。
- 登出后旧 AccessToken 访问受保护接口返回 401。
- 登录成功后返回可用的 `nettyUrl`。
- 获取上传 URL 后能够完成 MinIO 直传。

### 4.4 当前测试缺口

- 核心业务自动化测试基本缺失。
- 没有针对 Lua 脚本的并发回归测试。
- 没有验证 Gateway 与 Auth 协作契约的自动化用例。

---

## 5. 系统架构与模块设计

### 5.1 运行时架构

```text
Client
  |
  | HTTPS
  v
Gateway
  |- 白名单: /api/v1/user/noToken/**, /api/v1/user/refreshToken
  |- 非白名单: JWT 验签 -> 读取 jwt:logout:{userId} -> 注入 X-User-Id / X-Internal-Auth
  v
AuthenticationService
  |- MySQL: user, user_balance
  |- Redis: 验证码 / RT / 登出时间戳 / 路由协同
  |- Nacos: 发现 RTC 实例
  |- MinIO: 生成上传预签名 URL
  v
RealTimeCommunicationService
  |- 使用同一 JWT 密钥校验 token
  |- 读写 im:route:{userId}
  |- 订阅 userLogout 频道

Client
  |- 登录成功后拿到 nettyUrl
  |- 直连 ws://{selected-rtc-host}:9101/api/v1/chat/message?token={AT}
```

### 5.2 包结构设计

| 包 | 职责 |
|------|------|
| `controller` | 对外 REST 接口 |
| `service` / `service.impl` | 认证业务编排 |
| `mapper` | 用户与余额表持久化 |
| `model.dto` | 请求入参 |
| `model.vo` | 返回对象 |
| `model.entity` | 数据库实体 |
| `model.enums` | 配置、错误码、超时枚举 |
| `interceptor` | 校验 Gateway 注入的内部可信头 |
| `utils` | JWT、锁、ID、对象存储、结果封装等工具 |
| `config` | MyBatis、Redis、线程池、MinIO、MVC 配置 |
| `exception` | 业务异常与全局异常处理 |
| `loderBalance` | RTC 节点一致性哈希负载策略 |

### 5.3 核心组件说明

#### 5.3.1 `UserController`

- 暴露全部认证与资料相关 API。
- 统一挂载在 `/api/v1/user/**`。
- `noToken` 子路径用于公开接口。
- `refreshToken` 接口本身不依赖 Gateway 的 AccessToken 鉴权。

#### 5.3.2 `UserServiceImpl`

- 负责验证码、注册、登录、续签、登出、头像更新、预签名 URL 生成。
- 通过 `buildUserVOAndAllocateNode` 复用登录与续签后的返回装配逻辑。

#### 5.3.3 `JwtUtil`

- 统一使用 HS512 与固定密钥签发和解析 JWT。
- Claims 只使用 `sub`、`iat`、`exp`。

#### 5.3.4 `JwtBlacklistService`

- 负责写入 `jwt:logout:{userId}`。
- 该 Key 被 Gateway 和 RTC 共用。

#### 5.3.5 `RedisLockExecutor`

- 对 Redisson `RLock` 进行模板化封装。
- 统一处理等待锁、执行业务和释放锁流程。

#### 5.3.6 `ServiceInstanceUtil` + `ConsistentHashLoadBalancer`

- 通过 Nacos 拉取 RTC 实例列表。
- 使用 `host:port` 签名感知拓扑变化。
- 使用 MurmurHash 和 160 虚拟节点完成用户到节点的稳定映射。
- `ServiceInstanceUtil` 会继续读取目标 RTC 实例 metadata，并直接组装完整 `nettyUrl`。

#### 5.3.7 `OSSUtil`

- 封装 MinIO 预签名 URL 生成。
- 认证服务只返回地址，不代理文件上传内容。

### 5.4 认证边界设计

#### 5.4.1 Gateway 负责

- Bearer Token 提取。
- JWT 验签与过期校验。
- 登出时间戳比较。
- 向下游受保护接口注入 `X-User-Id` 与 `X-Internal-Auth`。

#### 5.4.2 AuthenticationService 负责

- 注册、登录、续签、登出、资料更新等账号域业务。
- RefreshToken 状态管理。
- 校验 `X-Internal-Auth` 与 `X-User-Id`，拒绝绕过 Gateway 的直连请求。
- RTC 节点分配。

#### 5.4.3 重要部署前提

- 当前实现通过 `GatewayTrustInterceptor` 做服务侧二次校验，而不是在 Auth 内部重复验 AT。
- 生产环境必须通过环境变量覆盖 `INTERNAL_REQUEST_SECRET`，不能依赖默认值。
- 对外流量仍应优先只暴露 Gateway，避免内部可信头扩散到公网边界。

### 5.5 关键业务流程

#### 5.5.1 注册流程

```text
1. 客户端提交 phone/password/code
2. 锁外预处理
   - BCrypt.hashpw(password)
   - idGenerator.nextId()
   - 生成随机昵称
3. 获取 register:lock:{phone}
4. 事务内执行
   - Redis GET {phone} 校验验证码
   - SELECT COUNT(*) FROM user WHERE phone=?
   - INSERT user
   - INSERT user_balance
   - Redis DEL {phone}
5. 返回注册成功
```

#### 5.5.2 登录流程

```text
1. 校验密码或验证码
2. 查询 user
3. 拷贝用户字段到 UserVO
4. 生成 AT/RT
5. SET RT:{userId} = refreshToken EX 7d
6. Nacos 获取 RTC 实例
7. 一致性哈希分配节点
8. 返回 UserVO + nettyUrl
```

#### 5.5.3 登出流程

```text
1. Gateway 已完成 AT 验签并注入 X-User-Id / X-Internal-Auth
2. Auth 写 jwt:logout:{userId} = currentTimeMillis EX 2h
3. Lua 原子执行
   - GET im:route:{userId} -> oldNodeId
   - DEL RT:{userId}
   - DEL im:route:{userId}
   - 若 oldNodeId 存在且不为 OFFLINE，则 PUBLISH userLogout, {oldNodeId}:{userId}
   - 否则 PUBLISH userLogout, {userId}
4. 异步补偿检查路由是否残留
```

#### 5.5.4 续签流程

```text
1. 客户端优先把 refreshToken 放在 X-Refresh-Token Header
2. 兼容 Authorization: {refreshToken} 与 Authorization: Bearer {refreshToken}
3. Controller 本地解析 refreshToken -> 得到 userId
4. Service 使用 Lua 原子比较并删除旧 RT
5. DB 查询完整用户信息
6. 重新生成双 Token
7. 覆盖 Redis 中 RT:{userId}
8. 重新分配 RTC 节点并返回 UserVO
```

---

## 6. 数据模型与 Redis 协议

### 6.1 MySQL 数据模型

#### 6.1.1 `user`

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | bigint | 主键，雪花 ID |
| `user_name` | varchar(64) | 随机昵称 |
| `password` | varchar(128) | BCrypt 哈希 |
| `email` | varchar(128) | 邮箱，当前注册流程未使用 |
| `phone` | varchar(20) | 手机号，唯一索引 |
| `avatar` | varchar(255) | 头像 URL |
| `signature` | varchar(255) | 个性签名 |
| `gender` | tinyint | 性别 |
| `status` | tinyint | 用户状态 |
| `created_at` | datetime | 创建时间 |
| `updated_at` | datetime | 更新时间 |

约束说明：

- 主键：`user_id`
- 唯一索引：`uk_phone(phone)`、`uk_email(email)`

#### 6.1.2 `user_balance`

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | bigint | 主键，关联用户 |
| `balance` | decimal(10,2) | 余额 |
| `created_at` | datetime | 创建时间 |
| `updated_at` | datetime | 更新时间 |

实现说明：

- 注册成功后默认写入 `balance = 1000.00`。
- 实体类只显式映射了 `user_id`、`balance`、`updated_at`。

### 6.2 Redis Key 设计

| Key / Channel | 类型 | TTL | 生产者 | 消费者 | 用途 |
|------|------|------|------|------|------|
| `{phone}` | String | 5 分钟 | Auth | Auth | 验证码 |
| `register:lock:{phone}` | Redisson Lock | 5 秒租约 | Auth | Auth | 注册串行化 |
| `RT:{userId}` | String | 7 天 | Auth | Auth | 当前有效 RefreshToken |
| `jwt:logout:{userId}` | String | 2 小时 | Auth | Gateway / RTC | AT 失效时间戳 |
| `im:route:{userId}` | String | RTC 控制 | RTC / Auth 删除 | RTC / Messaging | 用户所在 RTC 路由 |
| `userLogout` | Pub/Sub | 无 | Auth / RTC | RTC | 踢人广播，payload 为 `nodeId:userId` 或 `userId` |

### 6.3 Redis 协议细节

#### 6.3.1 验证码

- Key 直接使用手机号明文。
- Value 为 6 位数字字符串。
- 邮件发送失败时会删除 Redis 中的验证码。

#### 6.3.2 RefreshToken

- 只保留当前最新一份 RT。
- 新登录与新续签都会覆盖旧 RT。
- 续签时先原子消费旧 RT，再写入新 RT。

#### 6.3.3 登出时间戳

- Value 为 `System.currentTimeMillis()`。
- 只对 AccessToken 生效。
- TTL 对齐 AccessToken 最大有效期。

#### 6.3.4 RTC 路由协同

- Auth 登录阶段只负责“分配” RTC 节点，不写 `im:route:{userId}`。
- 真正的路由注册由 RTC WebSocket 握手成功后完成。
- Auth 登出时会先读取旧路由，再删除路由并发布踢人消息。
- 优先发布精准 payload：`{oldNodeId}:{userId}`。
- 若路由缺失或已被标记为 `OFFLINE`，则退化为广播 payload：`{userId}`。

---

## 7. 接口契约

### 7.1 统一响应模型

当前模块的 Controller 正常返回与全局异常返回均统一为 `Result<T>`。

#### 7.1.1 成功响应

- 类型：`Result<T>`

```json
{
  "code": 200,
  "message": "请求成功",
  "data": {}
}
```

#### 7.1.2 业务异常响应

- 类型：`Result<T>`

```json
{
  "code": 40006,
  "message": "登录失败, 手机号或密码错误",
  "data": null
}
```

#### 7.1.3 网关拦截响应

- Gateway 会直接返回 HTTP 401 或 429。
- 该返回不经过 AuthenticationService Controller。

### 7.2 认证接口总览

| 接口 | 方法 | 路径 | 鉴权要求 | 说明 |
|------|------|------|------|------|
| 发送验证码 | POST | `/api/v1/user/noToken/sms` | 否 | 发验证码 |
| 注册 | POST | `/api/v1/user/noToken/register` | 否 | 创建用户和余额账户 |
| 密码登录 | POST | `/api/v1/user/noToken/loginPwd` | 否 | 返回 `UserVO` |
| 验证码登录 | POST | `/api/v1/user/noToken/loginCode` | 否 | 返回 `UserVO` |
| 续签 | POST | `/api/v1/user/refreshToken` | 否，需 RT | 主入口，用 RT 换新双 Token |
| 续签兼容入口 | GET | `/api/v1/user/refreshToken` | 否，需 RT | 保留给旧客户端过渡 |
| 登出 | POST | `/api/v1/user/logout` | 是 | 作废旧 Token |
| 更新头像 | PATCH | `/api/v1/user/avatar` | 是 | 更新 `user.avatar` |
| 获取上传 URL | GET | `/api/v1/user/uploadUrl` | 是 | 获取 MinIO 上传地址 |

### 7.3 DTO / VO 契约

#### 7.3.1 `UserSMSRequest`

| 字段 | 类型 | 约束 |
|------|------|------|
| `phone` | string | 非空，长度 11 |

#### 7.3.2 `UserRegisterRequest`

| 字段 | 类型 | 约束 |
|------|------|------|
| `phone` | string | 非空，长度 11 |
| `password` | string | 非空，长度 6-16 |
| `code` | string | 非空，长度 6 |

#### 7.3.3 `UserLoginPwdRequest`

| 字段 | 类型 | 约束 |
|------|------|------|
| `phone` | string | 非空，长度 11 |
| `password` | string | 非空，长度 6-16 |

#### 7.3.4 `UserLoginCodeRequest`

| 字段 | 类型 | 约束 |
|------|------|------|
| `phone` | string | 非空，长度 11 |
| `code` | string | 非空，长度 6 |

#### 7.3.5 `UserUpdateAvatarRequest`

| 字段 | 类型 | 约束 |
|------|------|------|
| `avatarUrl` | string | 非空 |

#### 7.3.6 `UserVO`

| 字段 | 类型 | 说明 |
|------|------|------|
| `userId` | string / long | 通过 `ToStringSerializer` 避免 JS 精度丢失 |
| `userName` | string | 昵称 |
| `avatar` | string | 头像 |
| `signature` | string | 个性签名 |
| `gender` | integer | 性别 |
| `status` | integer | 状态 |
| `nettyUrl` | string | RTC WebSocket 直连地址，不包含 query token |
| `Token` / `token` | string | AccessToken，当前命名存在兼容性风险 |
| `refreshToken` | string | RefreshToken |
| `expireTime` | long | 当前实现未赋值 |

#### 7.3.7 `UploadUrlResponse`

| 字段 | 类型 | 说明 |
|------|------|------|
| `uploadUrl` | string | MinIO PUT 预签名 URL |
| `downloadUrl` | string | 对象访问地址 |

### 7.4 详细接口契约

#### 7.4.1 发送验证码

**接口**

- `POST /api/v1/user/noToken/sms`

**请求头**

- `Content-Type: application/json`

**请求体**

```json
{
  "phone": "13800000001"
}
```

**成功响应**

```json
{
  "code": 200,
  "message": "验证码已发送",
  "data": "验证码已发送"
}
```

**副作用**

- `SET {phone} {code} EX 5min`
- 异步发送邮件模拟短信

#### 7.4.2 注册

**接口**

- `POST /api/v1/user/noToken/register`

**请求体**

```json
{
  "phone": "13800000001",
  "password": "Test@123456",
  "code": "123456"
}
```

**成功响应**

```json
{
  "code": 200,
  "message": "注册成功",
  "data": "注册成功"
}
```

**核心约束**

- 按手机号加锁。
- 验证码必须命中 Redis 且完全相等。
- 同手机号不能重复注册。
- 成功后插入 `user` 与 `user_balance`。

**典型错误码**

- `40001`：验证码错误
- `40003`：用户已存在
- `50002`：获取分布式锁失败
- `50003`：数据库操作异常

#### 7.4.3 密码登录

**接口**

- `POST /api/v1/user/noToken/loginPwd`

**请求体**

```json
{
  "phone": "13800000001",
  "password": "Test@123456"
}
```

**成功响应数据**

```json
{
  "code": 200,
  "message": "请求成功",
  "data": {
    "userId": "1912345678901234567",
    "userName": "活泼的小熊猫",
    "avatar": null,
    "signature": null,
    "gender": 0,
    "status": 1,
    "nettyUrl": "ws://172.31.130.141:9101/api/v1/chat/message",
    "Token": "access-token",
    "refreshToken": "refresh-token",
    "expireTime": null
  }
}
```

**服务端行为**

- 根据手机号查询用户。
- 使用 `BCrypt.checkpw` 校验密码。
- 生成 AT/RT。
- 写入 `RT:{userId}`。
- 分配 RTC 节点并返回 `nettyUrl`。

**典型错误码**

- `40006`：手机号或密码错误
- `50000`：系统异常或无可用 RTC 节点

#### 7.4.4 验证码登录

**接口**

- `POST /api/v1/user/noToken/loginCode`

**请求体**

```json
{
  "phone": "13800000001",
  "code": "123456"
}
```

**服务端行为**

- `GET {phone}` 校验验证码。
- 成功后删除验证码，防止复用。
- 复用与密码登录相同的 Token 装配逻辑。

**典型错误码**

- `40001`：验证码错误
- `40006`：用户不存在或登录失败

#### 7.4.5 续签

**接口**

- `POST /api/v1/user/refreshToken`
- `GET /api/v1/user/refreshToken`（兼容入口）

**请求头**

- `X-Refresh-Token: {refreshToken}`（推荐）
- `Authorization: {refreshToken}`（兼容）
- `Authorization: Bearer {refreshToken}`（兼容）

注意：

- `POST` 与 `GET` 都会进入同一套续签逻辑。
- 当前实现会优先读取 `X-Refresh-Token`，其次读取 `Authorization`。

**服务端行为**

- Controller 先解析 RT 提取 `userId`。
- Service 使用 Lua 原子校验并删除旧 RT。
- 查询 DB 获取完整用户信息。
- 重新生成双 Token 并覆盖 Redis 中的 RT。

**Lua 语义**

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    redis.call('del', KEYS[1])
    return 1
else
    return 0
end
```

**典型错误码**

- `40100`：Header 缺失
- `40101`：RT 无效、过期、已被消费或与 Redis 不一致

#### 7.4.6 登出

**接口**

- `POST /api/v1/user/logout`

**请求头**

- `Authorization: Bearer {accessToken}`

下游内部头（由 Gateway 注入，客户端无需自行传递）：

- `X-User-Id: {gateway injected userId}`
- `X-Internal-Auth: {gateway injected internal secret}`

**成功响应**

```json
{
  "code": 200,
  "message": "退出成功",
  "data": "退出成功"
}
```

**服务端行为**

- 写入 `jwt:logout:{userId}`。
- 先读取旧 `im:route:{userId}`，再删除 `RT:{userId}` 和 `im:route:{userId}`。
- 优先向 `userLogout` 发布 `{oldNodeId}:{userId}` 精准踢线消息。
- 若没有旧节点信息，则退化为广播 `{userId}`。
- 启动异步补偿清理。

#### 7.4.7 更新头像

**接口**

- `PATCH /api/v1/user/avatar`

**请求体**

```json
{
  "avatarUrl": "http://minio/infinitec-chat/avatar/a.png"
}
```

**成功响应**

```json
{
  "code": 200,
  "message": "请求成功",
  "data": null
}
```

**服务端行为**

- 通过 `X-User-Id` 查询当前用户。
- 更新 `user.avatar`。

#### 7.4.8 获取上传 URL

**接口**

- `GET /api/v1/user/uploadUrl?fileName={fileName}`

**请求参数**

| 参数 | 类型 | 约束 |
|------|------|------|
| `fileName` | string | 非空 |

**成功响应**

```json
{
  "code": 200,
  "message": "请求成功",
  "data": {
    "uploadUrl": "http://minio/...presigned...",
    "downloadUrl": "http://minio/infinitec-chat/avatar.png"
  }
}
```

**服务端行为**

- 生成 bucket=`infinitec-chat` 的 PUT 预签名 URL。
- URL 默认 30 秒过期。

---

## 8. 核心方法分析

### 8.1 `sendClientSms(UserSMSRequest)`

**定位**

- 验证码发送入口，当前实现为邮件模拟短信。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `userSMSRequest.phone` | String | 手机号 |

**输出**

- `void`

**数据流转**

1. 生成 6 位随机验证码。
2. 写入 Redis：`SET {phone} {code} EX 5min`。
3. 提交异步任务到 `ioThreadPool`。
4. 异步线程通过 QQ SMTP 向开发者邮箱发送模拟短信。
5. 邮件失败时删除 Redis 中的验证码。

### 8.2 `userRegister(UserRegisterRequest)`

**定位**

- 用户注册总入口。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `phone` | String | 手机号 |
| `password` | String | 明文密码 |
| `code` | String | 验证码 |

**输出**

- `void`

**数据流转**

1. 锁外执行 `BCrypt.hashpw(password)`。
2. 锁外生成雪花 ID。
3. 锁外生成随机昵称。
4. 获取 `register:lock:{phone}`。
5. 在 `TransactionTemplate` 中执行：
   - `GET {phone}` 校验验证码。
   - 查询手机号是否已注册。
   - 插入 `user`。
   - 插入 `user_balance`。
   - 删除验证码。
6. 释放分布式锁。

### 8.3 `userLoginPwd(UserLoginPwdRequest)` / `userLoginCode(UserLoginCodeRequest)`

**定位**

- 两种登录入口，最终汇聚到统一的 Token 与 RTC 装配逻辑。

**输出**

- `UserVO`

**数据流转**

密码登录：

1. 查询用户。
2. `BCrypt.checkpw` 校验密码。
3. 调用 `buildUserVOAndAllocateNode(user)`。

验证码登录：

1. 校验 Redis 中的验证码。
2. 查询用户。
3. 删除验证码。
4. 调用 `buildUserVOAndAllocateNode(user)`。

### 8.4 `buildUserVOAndAllocateNode(User)` + `buildNewTokenPair(UserVO)`

**定位**

- 登录/续签后的统一返回装配器。

**输出**

- 含 AT/RT 与 `nettyUrl` 的 `UserVO`

**数据流转**

1. 拷贝 `User` 到 `UserVO`。
2. 生成 AccessToken：
   - `sub=userId`
   - `iat=now`
   - `exp=now+2h`
3. 生成 RefreshToken：
   - `sub=userId`
   - `iat=now`
   - `exp=now+7d`
4. `SET RT:{userId} {refreshToken} EX 7d`
5. 通过 Nacos + 一致性哈希选择 RTC 节点。
6. 读取 RTC 实例 metadata（`ws-protocol / ws-port / ws-path`）并组装 `nettyUrl`
7. 返回 `UserVO`

**补充说明**

- `expireTime` 字段当前未赋值。
- Token 字段在 VO 中命名为 `Token`，存在序列化兼容风险。

### 8.5 `refreshToken(Long userId, String clientRefreshToken)`

**定位**

- RefreshToken 一次性消费续签方法。

**输出**

- `UserVO`

**数据流转**

1. Controller 优先从 `X-Refresh-Token`，其次从 `Authorization` 解析 RT。
2. 兼容原始 RT 与 `Bearer {refreshToken}` 形式。
3. 读取 Redis Key：`RT:{userId}`。
4. Lua 原子执行“比较 RT 是否一致 + 删除旧 RT”。
5. 失败则抛出 `LOGIN_EXPIRED`。
6. 查询 DB 获取完整用户信息。
7. 复用登录装配逻辑生成全新双 Token。

### 8.6 `userLogout(UserLogOutRequest)`

**定位**

- 主动登出的清理方法。

**输出**

- `void`

**数据流转**

1. 写入 `jwt:logout:{userId}`，TTL 2 小时。
2. Lua 先读取 `im:route:{userId}` 的旧节点 ID。
3. Lua 删除 `RT:{userId}`。
4. Lua 删除 `im:route:{userId}`。
5. 若旧节点存在且不为 `OFFLINE`，向 `userLogout` 发布 `{oldNodeId}:{userId}`。
6. 若旧节点不存在，则向 `userLogout` 发布 `{userId}` 广播踢线。
7. 异步补偿检查路由残留并再次清理。

**补充说明**

- RTC 监听器同时兼容精准踢线与广播踢线两种 payload。
- 当前实现已经修复“Auth 发广播但 RTC 不断连”的链路不一致问题。

### 8.7 `ConsistentHashLoadBalancer.select(List<ServiceInstance>, Long userId)`

**定位**

- RTC 节点一致性哈希选址的核心方法。
- 负责把用户 ID 稳定映射到某个 `ServiceInstance`。

**输入**

| 参数 | 类型 | 说明 |
|------|------|------|
| `instances` | `List<ServiceInstance>` | 当前从 Nacos 拉取到的 RTC 实例列表 |
| `userId` | `Long` | 用于路由的用户唯一标识 |

**输出**

- `ServiceInstance`

**数据流转**

1. 判空：如果 `instances` 为空，直接返回 `null`。
2. 生成当前实例签名：
   - 取每个实例的 `host:port`
   - 排序
   - 通过 `,` 拼接成 `currentSignature`
3. 判断实例拓扑是否变化：
   - 若 `currentSignature != lastInstanceSignature`
   - 进入 `synchronized (this)` 做双重检查
   - 仅在实例集变化时重建哈希环
4. 构建或复用哈希环：
   - 每个物理节点先放入一个真实节点 hash
   - 再放入 `160` 个虚拟节点，key 形式为 `host:port#i`
   - 底层结构为 `TreeMap<Integer, ServiceInstance>`
5. 计算用户哈希：
   - `hash = getHash(String.valueOf(userId))`
   - 哈希算法为 MurmurHash32
6. 顺时针寻址：
   - `tailMap = hashRing.tailMap(hash)`
   - 若 `tailMap` 非空，取 `tailMap.firstKey()`
   - 若为空，说明落在环尾之后，回绕到 `hashRing.firstKey()`
7. 返回目标 `ServiceInstance`

**核心语义**

- 同一 `userId` 在实例列表不变时，会稳定命中同一 RTC 节点。
- 实例列表发生扩缩容时，只重建一次哈希环，避免每次请求重复构建。
- 使用虚拟节点改善小规模物理节点时的分布不均问题。

**关联私有方法**

- `buildHashRing(List<ServiceInstance>)`
  - 负责把真实节点和虚拟节点全部写入新的 `TreeMap`
  - 构建完成后原子替换旧 `hashRing`
- `getHash(String str)`
  - 使用 `HashUtil.murmur32(str.getBytes(UTF_8))`
  - 外层取 `Math.abs(...)` 保证 key 为非负整数

**关键点**

- `lastInstanceSignature` 是哈希环是否需要重建的快速判定条件。
- `volatile + synchronized + 双重检查` 组合用于降低高并发下的重建开销。
- `TreeMap.tailMap()` 实现了经典一致性哈希的“顺时针找最近节点”。

### 8.8 `ServiceInstanceUtil.getWebSocketUrl(Long userId)`

**定位**

- 认证模块与 RTC 发现协同的关键入口。

**输出**

- `String`，完整 RTC WebSocket 直连地址

**数据流转**

1. 从 Nacos 拉取 `RealTimeCommunicationService` 实例列表。
2. 交给 `ConsistentHashLoadBalancer.select(instances, userId)`。
3. 根据 `userId` 的哈希值在哈希环上顺时针寻找目标节点。
4. 读取目标实例 metadata 中的 `ws-protocol / ws-port / ws-path`。
5. 组装并返回完整 RTC WebSocket 直连地址。

---

## 9. 安全设计与已知约束

### 9.1 已实现的安全设计

| 能力 | 实现方式 | 防御目标 |
|------|------|------|
| JWT 签名 | HS512 + 对称密钥 | 防篡改 |
| 密码存储 | BCrypt | 防彩虹表与撞库 |
| 登出失效 | `jwt:logout:{userId}` | 防旧 AT 复用 |
| 来源校验 | `GatewayTrustInterceptor` + `X-Internal-Auth` | 防绕过 Gateway 直连受保护接口 |
| RT 防重放 | Lua 原子比对删除 | 防同一 RT 多次续签 |
| 注册防重 | Redisson 锁 | 防并发重复注册 |
| 上传授权 | MinIO 预签名 URL | 限定上传时效与对象范围 |

### 9.2 当前实现约束

#### 9.2.1 受保护接口仍依赖 Gateway 与内部可信头

- 模块内部没有重复校验 AccessToken。
- `GatewayTrustInterceptor` 当前只校验 `X-Internal-Auth` 是否匹配、`X-User-Id` 是否存在且可转为 Long。
- 生产环境必须覆盖 `INTERNAL_REQUEST_SECRET`，并继续保证 Auth 仅暴露在 Gateway 之后。

#### 9.2.2 RefreshToken 契约仍处于兼容过渡期

- 当前主入口是 `POST /api/v1/user/refreshToken`。
- 同时保留 `GET /api/v1/user/refreshToken` 兼容旧客户端。
- Header 解析同时兼容 `X-Refresh-Token`、`Authorization: {refreshToken}` 与 `Authorization: Bearer {refreshToken}`，会增加客户端与测试矩阵复杂度。

#### 9.2.3 Token 出参字段仍有兼容风险

- `UserVO` 使用字段名 `Token`，而不是更明确的 `accessToken`。
- 仓库中的测试脚本采用 `Token` 与 `token` 双兼容读取。
- `expireTime` 字段当前仍未赋值。

#### 9.2.4 历史响应封装类仍有残留

- 当前 Controller 正常返回与异常返回已经统一为 `Result`。
- 但仓库中仍保留 `BaseResponse`、`ResultUtils` 等旧封装类，容易让维护者误判真实契约。

#### 9.2.5 明文配置与默认密钥风险仍然存在

- `application.yml` 中存在数据库、Redis、MinIO、邮箱等明文凭据。
- `security.internal-request.secret` 仍带默认回退值。
- `ConfigEnum` 中也包含 Token 密钥和短信参数。

#### 9.2.6 验证码能力较弱

- 验证码 Key 直接使用手机号。
- 没有按手机号、设备、业务场景做精细化限频和风控。
- 当前仅为开发态邮件模拟短信。

---

## 10. 可扩展性建议

### 10.1 收敛 RefreshToken 兼容面

- 完成客户端迁移后，下线 `GET /api/v1/user/refreshToken`。
- 逐步移除对 `Authorization` 承载 RT 的兼容解析，收敛到 `X-Refresh-Token`。
- 可在 JWT 中增加 `token_type` 或 `scope` claim，降低 AT/RT 混用风险。

### 10.2 统一 Token 出参与历史类清理

- 将 AccessToken 字段固定为 `accessToken`。
- 补齐 `expireTime` 或 `expiresIn`。
- 删除未再使用的 `BaseResponse`、`ResultUtils` 等历史封装，减少误用。

### 10.3 强化内部可信调用机制

- 保留当前 `X-Internal-Auth` 二次来源校验。
- 将内部可信头密钥统一迁移到环境变量或配置中心，并建立轮换机制。
- 中长期可演进到更强的服务间信任方案，例如签名头、mTLS 或 Service Mesh。

### 10.4 安全与风控增强

- 将数据库、Redis、MinIO、邮箱等明文凭据迁移到环境变量或配置中心。
- 引入验证码发送频控、滑动窗口限流、图形验证码、人机校验。
- 记录登录日志、设备指纹、续签审计信息。

### 10.5 自动化测试补齐

- 为注册、登录、续签、登出建立集成测试。
- 为 `GatewayTrustInterceptor` 建立受保护接口契约测试。
- 为 RefreshToken 与登出 Lua 逻辑补齐并发/回归测试。
