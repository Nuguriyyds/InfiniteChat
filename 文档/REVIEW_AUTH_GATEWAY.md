# InfiniteChat — Authentication & Gateway 模块复盘

## 一、整体架构总览

```
┌──────────────────────────────────────────────────────────────────────┐
│                            客户端 (App)                              │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │  HTTPS
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Gateway  (Spring Cloud Gateway)                  │
│                         端口: 10010 (WebFlux 响应式)                  │
│                                                                      │
│  ┌─────────────────┐    ┌──────────────────┐    ┌────────────────┐  │
│  │ RateLimitFilter  │───▶│ AuthorizeFilter  │───▶│   路由转发      │  │
│  │   order = -2     │    │   order = -1     │    │ (Nacos + LB)   │  │
│  │  IP 维度限流     │    │  JWT 验签+登出   │    │                │  │
│  │  Redisson        │    │  校验+注入       │    │                │  │
│  │  RateLimiter     │    │  X-User-Id       │    │                │  │
│  └─────────────────┘    └──────────────────┘    └────────────────┘  │
└──────────────┬───────────────────┬───────────────────┬───────────────┘
               │                   │                   │
     /api/v1/user/**      /api/message/**      /api/v1/chat/**
               │                   │                   │
               ▼                   ▼                   ▼
     ┌─────────────────┐  ┌──────────────┐  ┌──────────────────────┐
     │Authentication   │  │  Messaging   │  │  RealTimeCommunication│
     │Service (8082)   │  │  Service     │  │  Service (Netty)     │
     └─────────────────┘  └──────────────┘  └──────────────────────┘
```

### 核心定位

| 模块 | 角色 | 技术栈 |
|------|------|--------|
| Gateway | 流量入口，统一鉴权、限流、路由 | Spring Cloud Gateway (WebFlux)、Redisson、JJWT、Nacos |
| AuthenticationService | 用户认证、注册、登出、Token 管理、Netty 节点分配 | Spring Boot (Servlet)、MyBatis-Plus、Redisson、BCrypt、雪花算法 |

两个模块通过 **Redis** 共享状态（登出时间戳、RefreshToken），通过 **Nacos** 实现服务发现和负载均衡。

---

## 二、中间件一览

```
┌─────────────────────────────────────────────────────┐
│                     Redis (端口 6399)                │
│                                                     │
│  jwt:logout:{userId}     ← 登出时间戳 (TTL 2h)      │
│  RT:{userId}             ← RefreshToken (TTL 7d)    │
│  im:route:{userId}       ← Netty 节点路由           │
│  {phone}                 ← 短信验证码 (TTL 5min)     │
│  register:lock:{phone}   ← 注册分布式锁             │
│  rate_limit:ip:{ip}      ← IP 限流计数器            │
│  userLogout (channel)    ← 登出踢人的 Pub/Sub 频道   │
└─────────────────────────────────────────────────────┘

┌──────────────────────────────┐
│   Nacos (端口 18375)         │
│                              │
│  服务注册: Gateway,          │
│    AuthenticationService,    │
│    MessagingService,         │
│    RealTimeCommunicationSvc  │
│  配置中心: (未启用)           │
└──────────────────────────────┘

┌──────────────────────────────┐    ┌──────────────────────────────┐
│  MySQL                       │    │  MinIO (对象存储)             │
│  用户表 (user)               │    │  头像上传                    │
│  余额表 (user_balance)       │    │  预签名 URL                  │
└──────────────────────────────┘    └──────────────────────────────┘
```

### Redis 在两个模块中的 6 种用法

| 用法 | Key 格式 | 数据结构 | 模块 |
|------|----------|----------|------|
| 登出时间戳 | `jwt:logout:{userId}` | String (毫秒时间戳) | Auth 写 / Gateway 读 |
| RefreshToken 存储 | `RT:{userId}` | String (token 全文) | Auth 读写 |
| Netty 路由缓存 | `im:route:{userId}` | String (host:port) | Auth 删 / RTC 读写 |
| 短信验证码 | `{phone}` | String (6 位数字) | Auth 读写 |
| 分布式锁 | `register:lock:{phone}` | Redisson RLock | Auth |
| IP 限流 | `rate_limit:ip:{ip}` | Redisson RRateLimiter | Gateway |

---

## 三、Gateway 模块详解

### 3.1 请求处理链路

```
客户端请求
    │
    ▼
RateLimitFilter (order = -2)
    │  按客户端 IP 限流 (100次/分钟)
    │  基于 Redisson RRateLimiter (令牌桶算法)
    │  白名单: 仅 /swagger、/v3/api-docs、/webjars 跳过
    │
    ▼
AuthorizeFilter (order = -1)
    │  白名单路径直接放行:
    │    /api/v1/user/noToken/*  (登录、注册、发短信)
    │    /api/v1/user/refreshToken
    │
    │  非白名单路径:
    │    1. 从 Authorization Header 提取 Bearer Token
    │    2. JwtUtil.parse(token) → HS512 验签 + 过期检查
    │    3. 从 claims 取 subject(userId) 和 issuedAt(签发时间)
    │    4. 查 Redis: jwt:logout:{userId} 获取登出时间戳
    │    5. 若 iat < logoutAt → 401 拒绝 (Token 签发于登出之前)
    │    6. 校验通过 → 往请求头注入 X-User-Id，传给下游服务
    │
    ▼
Spring Cloud Gateway 路由转发
    │  根据 Path 匹配规则，通过 Nacos + LoadBalancer 转发到对应微服务
    │
    ▼
下游微服务 (Auth / Messaging / RTC)
```

### 3.2 路由配置

| 路由 ID | 匹配路径 | 目标 | 转发方式 |
|---------|----------|------|----------|
| AuthenticationService | `/api/v1/user/**` | AuthenticationService | `lb://` Nacos 负载均衡 |
| MessagingService | `/api/message/**` | MessagingService | `lb://` Nacos 负载均衡 |
| NettyWebSocketRouter | `/api/v1/chat/message` (精确) | `ws://172.30.233.168:9101` | 直连 WebSocket |
| RTC_Http_Router | `/api/v1/chat/**` (模糊) | RealTimeCommunicationService | `lb://` Nacos 负载均衡 |

注意：WebSocket 路由是精确匹配且排在前面，避免被 HTTP 路由吞掉。

### 3.3 限流机制 (RateLimitFilter)

```
客户端 IP
    │
    ▼
┌───────────────────────────────────────┐
│ Redisson RRateLimiter (令牌桶)        │
│                                       │
│  trySetRate(100, 1, MINUTES)          │
│  → 底层: Redis Hash + 令牌桶算法      │
│  → 幂等: HSETNX，重复调用无副作用     │
│                                       │
│  tryAcquire(1)                        │
│  → 尝试消费 1 个令牌                  │
│  → 成功: 放行                         │
│  → 失败: 返回 429 Too Many Requests   │
└───────────────────────────────────────┘
```

关键设计：
- 在鉴权之前执行 (order=-2)，确保恶意请求（无效 Token、暴力登录）都会被限流
- 使用 `Schedulers.boundedElastic()` 调度 Redisson 阻塞调用，不阻塞 Netty EventLoop
- 纯 IP 维度，不依赖 userId（因为此时鉴权还没跑）

### 3.4 JWT 验签 (AuthorizeFilter)

```
Token 字符串
    │
    ▼
┌───────────────────────────────────────┐
│ JJWT parserBuilder()                  │
│   .setSigningKey(SECRET_KEY)          │  ← HS512 对称密钥
│   .build()                            │
│   .parseClaimsJws(token)              │
│                                       │
│ 一次性完成:                            │
│   ✓ 签名验证 (防篡改)                 │
│   ✓ 过期检查 (exp claim)              │
│   ✓ 结构校验 (Header.Payload.Sig)     │
│                                       │
│ 返回 Claims:                          │
│   sub = userId                        │
│   iat = 签发时间 (毫秒)               │
│   exp = 过期时间                      │
└───────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────┐
│ 登出时间戳校验                         │
│                                       │
│ Redis GET jwt:logout:{userId}         │
│                                       │
│ if (logoutAt > 0 && iat < logoutAt)   │
│     → 401: Token 签发于登出之前        │
│ else                                  │
│     → 放行，注入 X-User-Id            │
└───────────────────────────────────────┘
```

这是"时间戳黑名单"方案的核心：不存"用户是否被拉黑"，而是存"用户最后一次登出的时间"。
新 Token 的 iat 必然晚于 logoutAt，天然有效；旧 Token 的 iat 早于 logoutAt，永久失效。

---

## 四、AuthenticationService 模块详解

### 4.1 API 接口总览

| 接口 | 方法 | 路径 | 需要鉴权 | 功能 |
|------|------|------|----------|------|
| 发送验证码 | POST | `/api/v1/user/noToken/sms` | 否 | Redis 存验证码 + 邮件模拟短信 |
| 注册 | POST | `/api/v1/user/noToken/register` | 否 | 分布式锁 + 事务写库 |
| 密码登录 | POST | `/api/v1/user/noToken/loginPwd` | 否 | BCrypt 校验 + 签发双 Token |
| 验证码登录 | POST | `/api/v1/user/noToken/loginCode` | 否 | Redis 校验验证码 + 签发双 Token |
| 登出 | POST | `/api/v1/user/logout` | 是 | 记录登出时间戳 + 清除路由 + 踢人 |
| 续签 | GET | `/api/v1/user/refreshToken` | 否* | Lua 原子校验 RT + 签发新双 Token |
| 更新头像 | PATCH | `/api/v1/user/avatar` | 是 | 更新 DB |
| 获取上传URL | GET | `/api/v1/user/uploadUrl` | 是 | MinIO 预签名 URL |

*续签接口不走 JWT 鉴权（它本身就是用来换新 Token 的），但由 RefreshToken 自身的签名 + Redis 比对保护。

### 4.2 核心业务流程

#### 4.2.1 注册流程

```
客户端
  │  POST /noToken/register {phone, password, code}
  │
  ▼
┌──────────────────────────────────────────────────────┐
│ 锁外预处理 (不持锁，减小锁粒度)                       │
│                                                      │
│  1. BCrypt.hashpw(password)     ← 加密 ~100ms       │
│  2. idGenerator.nextId()        ← 雪花 ID ~1ms      │
│  3. NicknameGenerator.generate() ← 随机昵称          │
└──────────────────────────────┬───────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────┐
│ Redisson 分布式锁 (key = register:lock:{phone})      │
│   waitTime = 3s, leaseTime = 5s                      │
│                                                      │
│ ┌──────────────────────────────────────────────────┐ │
│ │ Spring 编程式事务 (TransactionTemplate)           │ │
│ │                                                  │ │
│ │  1. Redis GET {phone} → 校验验证码               │ │
│ │     (必须在锁内，消除 TOCTOU 竞态窗口)           │ │
│ │  2. SELECT COUNT(*) WHERE phone=? → 判重         │ │
│ │  3. INSERT user 表                               │ │
│ │  4. INSERT user_balance 表 (初始余额 1000)       │ │
│ │  5. Redis DEL {phone} → 销毁验证码               │ │
│ │                                                  │ │
│ └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

面试要点：
- **为什么验证码校验放在锁内？** 消除 TOCTOU（Time-of-check to Time-of-use）竞态：如果锁外校验，两个并发请求可能同时通过验证码校验，然后一个拿锁成功注册，另一个拿锁后验证码已被删但校验已过，导致重复注册。
- **为什么 BCrypt 放在锁外？** BCrypt 是 CPU 密集型（~100ms），放在锁内会大幅增加锁持有时间，降低并发能力。
- **为什么用编程式事务而不是 `@Transactional`？** 编程式事务的边界更精确，避免将锁等待时间也包在事务里。

#### 4.2.2 登录流程

```
客户端
  │  POST /noToken/loginPwd {phone, password}
  │  或 POST /noToken/loginCode {phone, code}
  │
  ▼
┌────────────────────────────────────────┐
│ 1. 校验凭证                            │
│    密码登录: BCrypt.checkpw()          │
│    验证码登录: Redis GET → 比对 → DEL  │
│                                        │
│ 2. DB 查询用户                         │
│    SELECT * FROM user WHERE phone=?    │
│                                        │
│ 3. 签发双 Token                        │
│    AccessToken:  HS512, sub=userId,    │
│                  iat=now, exp=now+2h   │
│    RefreshToken: HS512, sub=userId,    │
│                  iat=now, exp=now+7d   │
│                                        │
│ 4. Redis 存 RefreshToken               │
│    SET RT:{userId} {refreshToken} EX 7d│
│                                        │
│ 5. 一致性哈希分配 Netty 节点            │
│    Nacos 拉取 RTC 实例列表              │
│    → ConsistentHashLoadBalancer.select()│
│    → 返回 ws://host:port               │
└────────────────────────────────────────┘
    │
    ▼
返回给客户端:
{
  userId, userName, avatar, ...,
  token: "eyJ...",           ← AccessToken
  refreshToken: "eyJ...",    ← RefreshToken
  nettyUrl: "ws://x.x.x.x:9101/api/v1/chat/message"
}
```

#### 4.2.3 登出流程

```
客户端
  │  POST /user/logout
  │  Header: Authorization: Bearer {accessToken}
  │
  ▼ (经过 Gateway 鉴权，X-User-Id 已注入)
  │
  ▼
┌────────────────────────────────────────────────────────────┐
│ 1. 记录登出时间戳                                          │
│    SET jwt:logout:{userId} {currentTimeMillis} EX 2h       │
│                                                            │
│ 2. Lua 脚本原子操作 (保证一致性)                            │
│    ┌────────────────────────────────────────────────────┐  │
│    │ redis.call('del', 'RT:{userId}')      -- 删 RT     │  │
│    │ redis.call('del', 'im:route:{userId}')-- 删路由    │  │
│    │ redis.call('publish', 'userLogout',   -- 踢人消息  │  │
│    │            'im:route:{userId}')                    │  │
│    └────────────────────────────────────────────────────┘  │
│                                                            │
│ 3. 异步补偿 (1秒后)                                        │
│    检查路由是否残留，若仍在则重新删除并发布踢人消息           │
└────────────────────────────────────────────────────────────┘
```

面试要点：
- **为什么用 Lua 脚本？** 删 RT、删路由、发布踢人消息这三步必须原子执行。如果分开执行，中间步骤失败会导致状态不一致（例如 RT 删了但路由还在，用户的 WebSocket 连接没断）。
- **为什么需要异步补偿？** Redis Pub/Sub 是 fire-and-forget，如果 Netty 节点恰好在处理其他事情没来得及处理踢人消息，路由可能残留。1 秒后的补偿检查是兜底机制。
- **时间戳黑名单的优势？** 登出记录 `logoutAt`，之后用户重新登录拿到新 Token（`iat > logoutAt`），旧 Token（`iat < logoutAt`）永久失效，无需在登录时清除黑名单。

#### 4.2.4 Token 续签流程

```
客户端 (AccessToken 过期，RefreshToken 未过期)
  │  GET /user/refreshToken
  │  Header: Authorization: {refreshToken}
  │
  ▼ (Gateway 白名单，不走鉴权)
  │
  ▼
┌──────────────────────────────────────────────────────┐
│ Controller:                                          │
│   JwtUtil.parse(refreshToken) → 提取 userId          │
│   若解析失败 → 说明 RT 本身已过期或被篡改 → 401      │
│                                                      │
│ Service (Lua 脚本原子操作):                           │
│ ┌──────────────────────────────────────────────────┐ │
│ │ if redis.call('get', 'RT:{userId}') == clientRT  │ │
│ │ then                                             │ │
│ │     redis.call('del', 'RT:{userId}')  -- 一次性  │ │
│ │     return 1                          -- 通过    │ │
│ │ else                                             │ │
│ │     return 0                          -- 拒绝    │ │
│ │ end                                              │ │
│ └──────────────────────────────────────────────────┘ │
│                                                      │
│ 校验通过:                                             │
│   1. 查 DB 获取完整用户信息                            │
│   2. 签发全新的 AccessToken + RefreshToken             │
│   3. Redis 覆写新的 RT                                │
│   4. 一致性哈希重新分配 Netty 节点                     │
│   5. 返回完整 UserVO                                  │
└──────────────────────────────────────────────────────┘
```

面试要点：
- **为什么用 Lua 实现"比对 + 删除"？** 防止并发重放攻击。如果分成 GET + DEL 两步，攻击者可以在 GET 和 DEL 之间用同一个 RT 并发请求多次续签，拿到多组有效 Token。Lua 保证原子性，RT 只能被消费一次。
- **RefreshToken 被盗怎么办？** 攻击者用被盗 RT 续签 → RT 被消费删除 → 合法用户续签时 RT 不存在 → 续签失败 → 用户被迫重新登录。虽然不能阻止第一次盗用，但能让用户感知到异常。

### 4.3 双 Token 机制

```
┌──────────────────────────────────────────────────────────────┐
│                    JWT 双 Token 体系                          │
│                                                              │
│  AccessToken                    RefreshToken                 │
│  ├─ 用途: 业务接口鉴权          ├─ 用途: 换取新的双 Token     │
│  ├─ 有效期: 2 小时              ├─ 有效期: 7 天               │
│  ├─ 存储: 客户端内存            ├─ 存储: 客户端 + Redis       │
│  ├─ 算法: HS512                 ├─ 算法: HS512               │
│  ├─ Claims: sub, iat, exp       ├─ Claims: sub, iat, exp    │
│  └─ 校验: Gateway 每次请求      └─ 校验: 续签时 Lua 原子比对  │
│                                                              │
│  生命周期:                                                    │
│  ──登录──────────2h──────────AT过期──────────7d──────RT过期── │
│  │<-- AT 有效 -->│              │<-- 可续签 -->│              │
│                  │── 续签 ──▶ 新 AT + 新 RT                  │
└──────────────────────────────────────────────────────────────┘
```

### 4.4 一致性哈希负载均衡

```
                    Nacos
                      │
                      ▼ 拉取 RTC 实例列表
              ┌───────────────────┐
              │ ServiceInstanceUtil│
              └───────┬───────────┘
                      │
                      ▼
    ┌─────────────────────────────────────────┐
    │    ConsistentHashLoadBalancer            │
    │                                         │
    │    哈希环 (TreeMap<Integer, Instance>)   │
    │                                         │
    │    ┌───┐  ┌───┐  ┌───┐                 │
    │    │N1 │  │N2 │  │N3 │  ← 物理节点     │
    │    └─┬─┘  └─┬─┘  └─┬─┘                 │
    │      │×160  │×160  │×160  ← 虚拟节点    │
    │      ▼      ▼      ▼                    │
    │   ─●──●──●──●──●──●──●──●── (哈希环)    │
    │           ▲                              │
    │     MurmurHash(userId)                   │
    │     → tailMap(hash).firstKey()           │
    │     → 顺时针找到最近的节点               │
    └─────────────────────────────────────────┘
```

面试要点：
- **160 个虚拟节点**：避免物理节点少时数据分布不均匀。
- **双重检查锁重建哈希环**：只有实例列表变化（扩缩容）时才重建，签名比对 (`host:port` 排序拼接) 判断是否变化。
- **哈希算法**：MurmurHash32，比 Java 默认 hashCode 分布更均匀。
- **为什么在登录时分配 Netty 节点？** 让客户端拿到登录响应后直接连对应的 Netty 节点，建立 WebSocket 长连接。一致性哈希保证同一用户总是连到同一节点（除非扩缩容），利于消息推送。

### 4.5 分布式锁 (RedisLockExecutor)

```java
executeWithLock(lockKey, waitTime, leaseTime, () -> {
    // 业务逻辑
});
```

| 参数 | 注册场景的值 | 含义 |
|------|-------------|------|
| lockKey | `register:lock:{phone}` | 按手机号加锁，不同手机号互不阻塞 |
| waitTime | 3000ms | 等锁最多 3 秒，超时抛异常 |
| leaseTime | 5000ms | 持锁最多 5 秒，超时自动释放（防死锁） |

底层是 Redisson 的 `RLock`（基于 Redis Hash + Lua + 看门狗），支持可重入、自动续期。

### 4.6 雪花算法 ID 生成器

```
  0 | 0000...0000 (41位时间戳) | 00000 (5位数据中心) | 00000 (5位机器) | 000000000000 (12位序列号)
  │          │                       │                      │                    │
 符号位   毫秒级时间差              datacenterId          workerId           同毫秒内自增
          (自定义纪元                (主机名 hash          (IP hash            (最大 4095/ms)
           2023-01-01)               % 32)                 % 32)
```

亮点：时钟回拨防御。
- 回拨 ≤ 5ms：线程 sleep 双倍时间等待恢复。
- 回拨 > 5ms：直接抛异常拒绝生成，防止 ID 重复。

---

## 五、安全机制汇总

### 5.1 认证安全

| 机制 | 实现 | 防御目标 |
|------|------|----------|
| JWT HS512 签名 | JJWT 库，对称密钥 | 防 Token 篡改 |
| 登出时间戳 | Redis `jwt:logout:{userId}` = 毫秒时间戳 | 防已登出 Token 被复用 |
| RefreshToken 一次性消费 | Lua 原子 GET+DEL | 防 RT 重放攻击 |
| BCrypt 密码加密 | Hutool BCrypt | 防彩虹表、暴力破解 |

### 5.2 网关安全

| 机制 | 实现 | 防御目标 |
|------|------|----------|
| IP 限流 | Redisson RRateLimiter (100次/分) | 防 DoS / 暴力破解 |
| 白名单路由控制 | path 匹配跳过鉴权 | 只放行公开接口 |
| X-User-Id 注入 | 网关验签后写入，下游信任 | 防用户伪造身份 |

### 5.3 注册安全

| 机制 | 实现 | 防御目标 |
|------|------|----------|
| 分布式锁 | Redisson RLock (按手机号) | 防并发重复注册 |
| 锁内验证码校验 | Redis GET 在锁内执行 | 消除 TOCTOU 竞态 |
| 验证码一次性消费 | 事务提交前 DEL | 防验证码重复使用 |

---

## 六、关键数据流全景图

### 6.1 首次登录完整链路

```
App                     Gateway(:10010)         Auth(:8082)          Redis           MySQL         Nacos
 │                           │                      │                  │               │              │
 │  POST /noToken/loginPwd   │                      │                  │               │              │
 │  {phone, password}        │                      │                  │               │              │
 │─────────────────────────▶ │                      │                  │               │              │
 │                           │                      │                  │               │              │
 │                     RateLimitFilter               │                  │               │              │
 │                     (IP限流检查)─────────────────────────────────────▶│               │              │
 │                           │◀────────────────────────────────────────│(tryAcquire)   │              │
 │                           │                      │                  │               │              │
 │                     AuthorizeFilter               │                  │               │              │
 │                     (/noToken/ 白名单，跳过)       │                  │               │              │
 │                           │                      │                  │               │              │
 │                     路由转发 lb://Auth             │                  │               │              │
 │                           │─────────────────────▶ │                  │               │              │
 │                           │                      │                  │               │              │
 │                           │                BCrypt.checkpw()          │               │              │
 │                           │                      │─────────────────────────────────▶ │              │
 │                           │                      │◀────────────────────────────────│(SELECT user)  │
 │                           │                      │                  │               │              │
 │                           │                 签发 AT + RT             │               │              │
 │                           │                      │──SET RT:{uid}──▶ │               │              │
 │                           │                      │                  │               │              │
 │                           │              一致性哈希选 Netty ─────────────────────────────────────────▶│
 │                           │                      │◀─────────────────────────────────────────────────│
 │                           │                      │                  │               │  (实例列表)   │
 │                           │◀─────────────────────│                  │               │              │
 │◀──────────────────────────│  {token, refreshToken, nettyUrl, ...}   │               │              │
 │                           │                      │                  │               │              │
 │  ws://nettyHost:{metadata.ws-port}{metadata.ws-path} │          │                  │               │              │
 │══════════════════════════════════════════════════════════════▶ Netty (WebSocket 长连接)              │
```

### 6.2 鉴权请求链路（以发消息为例）

```
App                     Gateway(:10010)                    Redis
 │                           │                               │
 │  POST /api/message/send   │                               │
 │  Authorization: Bearer AT │                               │
 │─────────────────────────▶ │                               │
 │                           │                               │
 │                     RateLimitFilter                        │
 │                     tryAcquire(IP) ──────────────────────▶ │
 │                           │◀──────────────────────────────│ allowed=true
 │                           │                               │
 │                     AuthorizeFilter                        │
 │                     JwtUtil.parse(AT) → claims             │
 │                     userId = claims.sub                    │
 │                     iat = claims.issuedAt                  │
 │                           │                               │
 │                     GET jwt:logout:{userId} ─────────────▶ │
 │                           │◀──────────────────────────────│ (不存在→0，或时间戳)
 │                           │                               │
 │                     iat < logoutAt ?                       │
 │                       否 → 注入 X-User-Id → 路由转发       │
 │                       是 → 401                            │
 │                           │                               │
 │                     ──▶ MessagingService                   │
```

### 6.3 登出 + 旧 Token 失效链路

```
时间线:  T1(登录)        T2(登出)         T3(攻击者用旧Token)     T4(重新登录)
          │                │                    │                      │
          │ 签发 AT        │ jwt:logout:uid     │ Gateway 校验:         │ 签发新 AT
          │ iat = T1       │ = T2               │ iat(T1) < logout(T2) │ iat = T4
          │                │                    │ → 401 拒绝           │ iat(T4) > logout(T2)
          │                │ DEL RT:uid         │                      │ → 放行 ✓
          │                │ DEL im:route:uid   │                      │
          │                │ PUB userLogout     │                      │
          │                │ (踢断 WebSocket)   │                      │
```

---

## 七、面试高频问题与回答思路

### Q1: 你的 JWT 方案怎么实现登出的？为什么不直接拉黑 Token？

> 我用的是"登出时间戳"方案。登出时在 Redis 存 `jwt:logout:{userId} = 当前毫秒时间戳`，TTL = AccessToken 最大有效期（2h）。网关校验时，从 JWT 的 `iat` claim 取签发时间，和 Redis 里的登出时间做比较，`iat < logoutAt` 则拒绝。
>
> 不拉黑 Token（jti）的原因：我的系统是单设备登录，登出是用户维度的全端踢下线。如果按 jti 拉黑，只能作废当前这一条 Token，而我需要"该用户所有旧 Token 一律失效"的语义。要实现这个语义，要么维护每个用户的活跃 jti 集合（复杂），要么用时间戳方案（一个 key 搞定）。

### Q2: 为什么注册要用分布式锁？数据库唯一索引不够吗？

> 唯一索引能防重复插入，但不能防"重复尝试"带来的副作用。我的注册流程除了写 user 表，还写 user_balance 表、消费验证码。如果两个并发请求同时通过验证码校验，一个成功插入，另一个在唯一索引处失败——但验证码已经被第一个请求消费了，第二个请求拿到的是"验证码错误"还是"用户已存在"？错误语义混乱。
>
> 分布式锁保证同一手机号同一时刻只有一个请求在执行整个注册事务，验证码校验和写库是一个原子语义。

### Q3: 你的限流为什么放在鉴权之前？

> 如果限流在鉴权之后（order > -1），攻击者发大量无效 Token 请求时，每个请求都会触发 HS512 验签（CPU 密集型），然后被鉴权拒绝，限流 Filter 根本不会被执行——因为鉴权失败后直接返回 401，不会调用 `chain.filter()`。
>
> 把限流放在 order=-2（鉴权是 -1），所有请求不管 Token 有没有效，都必须先过 IP 限流这关。

### Q4: 续签接口的 Lua 脚本在防什么？

> 防 RefreshToken 重放攻击。续签的本质是"用 RT 换新 Token"，RT 应该是一次性的。如果分成 `GET RT` + `比对` + `DEL RT` 三步，攻击者可以在 GET 和 DEL 之间的窗口内并发提交同一个 RT，拿到多组有效 Token。
>
> Lua 脚本把"比对 + 删除"合成一个原子操作，Redis 单线程执行，杜绝了并发窗口。

### Q5: 一致性哈希的 160 个虚拟节点是怎么回事？

> 如果只有 3 个物理 Netty 节点，直接映射到哈希环上只有 3 个点，用户分配会严重不均匀。每个物理节点映射 160 个虚拟节点（用 `host:port#0` ~ `host:port#159` 做 key），总共 480 个点均匀散布在哈希环上，数据分布接近均匀。
>
> 虚拟节点越多分布越均匀，但内存占用和重建成本也越高。160 是业界常用的经验值。

### Q6: Gateway 是 WebFlux 响应式的，为什么 Redisson 调用要用 `Schedulers.boundedElastic()`？

> Spring Cloud Gateway 底层是 Netty EventLoop，线程数很少（默认 = CPU 核心数）。Redisson 的 `tryAcquire` 是阻塞式的 Redis 调用，如果直接在 EventLoop 上执行，会阻塞整个事件循环，导致所有请求卡住。
>
> `Schedulers.boundedElastic()` 把阻塞调用调度到一个弹性线程池（专门用来处理阻塞 IO），EventLoop 保持非阻塞。这是 WebFlux 中处理阻塞依赖的标准模式。

---

## 八、技术栈速查

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.6.13 | 基础框架 |
| Spring Cloud Gateway | 2021.0.5 | API 网关 (WebFlux) |
| Spring Cloud Alibaba | 2021.0.5.0 | Nacos 服务发现 |
| Redisson | 3.16.0 (Auth) / 3.23.5 (Gateway) | 分布式锁、限流 |
| JJWT | 0.9.1 (Auth) / 0.11.5 (Gateway) | JWT 签发与验签 |
| MyBatis-Plus | 3.5.2 | ORM |
| BCrypt | Hutool 5.8.25 | 密码加密 |
| MinIO | 8.2.1 | 对象存储 (头像) |
| Fastjson | 1.2.76 | JSON 序列化 |
| MySQL | - | 用户数据持久化 |
| Redis | - | 缓存、锁、限流、Pub/Sub |
| Nacos | - | 服务注册与发现 |
