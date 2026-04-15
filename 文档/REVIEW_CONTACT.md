# InfiniteChat — Contact 模块复盘

## 一、模块定位

Contact 是 IM 系统的**联系人/好友关系服务**，管理用户之间的社交拓扑——好友申请、同意、拒绝、删除、拉黑，以及 AI 助手的自动创建。它是 IM 消息能力的前置依赖：只有建立好友关系后，才能创建单聊会话、发送消息。

```
                        用户注册
                           │
             RocketMQ (USER_EVENT:REGISTER)
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│                   ContactService (8086)                       │
│                                                              │
│  ┌───────────────┐  ┌───────────────┐  ┌─────────────────┐  │
│  │ 好友申请管理   │  │ 好友关系管理   │  │ AI 助手创建     │  │
│  │ 发送/同意/拒绝 │  │ 列表/删除/拉黑 │  │ 注册自动触发    │  │
│  └───────────────┘  └───────────────┘  └─────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐   │
│  │               Feign 跨服务调用                        │   │
│  │  Messaging → 创建单聊会话                             │   │
│  │  RTC       → 推送好友申请 / 新会话通知                │   │
│  └───────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

---

## 二、整体架构

### 2.1 分层结构

```
┌──────────────────────────────────────────────────────────────────┐
│                    ContactController (REST API)                   │
│  addContact · acceptContact · rejectContact · deleteContact      │
│  blockContact · unblockContact · getContactList · searchContact  │
│  getPendingRequests                                              │
└─────────────────────────────┬────────────────────────────────────┘
                              │
┌─────────────────────────────▼────────────────────────────────────┐
│              ContactServiceImpl (核心业务逻辑)                    │
│  事务 · Redisson 分布式锁 · Redis 缓存 · Feign 外部调用          │
└──────┬───────────┬───────────┬──────────────┬────────────────────┘
       │           │           │              │
┌──────▼──────┐ ┌──▼────────┐ ┌▼───────────┐ ┌▼─────────────────┐
│ContactMapper│ │ContactReq │ │AiAssistant │ │ Feign Clients    │
│  好友关系   │ │Mapper     │ │Mapper      │ │ MessagingClient  │
│             │ │好友申请   │ │AI 助手     │ │ RtcPushClient    │
└─────────────┘ └───────────┘ └────────────┘ └──────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                  UserRegisterListener (RocketMQ)                  │
│  topic = USER_EVENT, tag = REGISTER                              │
│  → 消费用户注册事件 → 自动创建 AI 助手                            │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 外部依赖关系

```
Contact 模块
    │
    ├──Feign──▶ MessagingService
    │           POST /api/message/session/createSingle
    │           → 好友同意后创建单聊会话
    │
    ├──Feign──▶ RealTimeCommunicationService
    │           POST /api/v1/chat/push/friendApplication/{userId}
    │           POST /api/v1/chat/push/newSession/{userId}
    │           → 实时推送好友申请通知 / 新会话通知
    │
    └──MQ────◀ AuthenticationService (生产者)
               USER_EVENT:REGISTER → 触发 AI 助手创建
```

---

## 三、中间件一览

```
┌──────────────────────────────────────────────────────────────────┐
│                            Redis                                  │
│                                                                   │
│  联系人缓存:                                                      │
│    contact:list:{userId}               ← String (JSON)  TTL 1h   │
│    contact:list:{userId}:{contactType} ← String (JSON)  TTL 1h   │
│                                                                   │
│  分布式锁 (Redisson):                                             │
│    lock:contact:{minId}:{maxId}        ← RLock                   │
│    等待 3s, 持有 10s                                              │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                           MySQL                                   │
│                                                                   │
│  contact 表:                                                      │
│    id, userId, contactId, contactType, remark,                   │
│    status (0=已删除, 1=正常, 2=已拉黑),                          │
│    isPinned, createdAt, updatedAt                                │
│                                                                   │
│  contact_request 表:                                              │
│    id, fromUserId, toUserId, remark,                             │
│    status (0=待处理, 1=已同意, 2=已拒绝),                        │
│    createdAt, updatedAt                                          │
│                                                                   │
│  ai_assistant 表:                                                 │
│    id, userId, assistantId, assistantName, modelType,            │
│    personality, contextLimit, createdAt                           │
└──────────────────────────────────────────────────────────────────┘

┌────────────────────────┐  ┌─────────────────┐
│ RocketMQ               │  │  Nacos          │
│ USER_EVENT:REGISTER    │  │  服务注册发现   │
│ (消费者)               │  └─────────────────┘
└────────────────────────┘
```

---

## 四、API 接口总览

| 接口 | 方法 | 功能 |
|------|------|------|
| `/api/contact/add` | POST | 发送好友申请 |
| `/api/contact/accept` | POST | 同意好友申请 |
| `/api/contact/reject` | POST | 拒绝好友申请 |
| `/api/contact/pending` | GET | 获取待处理的好友申请列表 |
| `/api/contact/delete` | POST | 删除好友（软删除） |
| `/api/contact/block` | POST | 拉黑好友 |
| `/api/contact/unblock` | POST | 取消拉黑 |
| `/api/contact/list` | GET | 获取联系人列表 |
| `/api/contact/search` | GET | 搜索联系人 |

---

## 五、核心流程详解

### 5.1 发送好友申请

```
用户A
  │  POST /api/contact/add
  │  { userId: A, contactId: B, remark: "你好" }
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ ContactServiceImpl.addContact()                                  │
│                                                                  │
│ 1. 校验: A ≠ B (不能加自己)                                      │
│                                                                  │
│ 2. 查 contact 表: A→B 是否已存在且 status=1                      │
│    → 已是好友 → 抛异常                                           │
│                                                                  │
│ 3. 查 contact_request 表: A→B 是否有 status=0 的待处理申请       │
│    → 重复申请 → 抛异常                                           │
│                                                                  │
│ 4. INSERT contact_request (fromUserId=A, toUserId=B, status=0)  │
│                                                                  │
│ 5. Feign → RtcPushClient.pushFriendApplication(B)               │
│    向 B 推送好友申请通知                                          │
└──────────────────────────────────────────────────────────────────┘
```

### 5.2 同意好友申请（核心流程）

```
用户B
  │  POST /api/contact/accept
  │  { requestId: 123 }
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. 查 contact_request: id=123, status 须=0, toUserId 须=B       │
│    校验不通过 → 抛异常                                           │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. 更新 contact_request: status = 1 (已同意)                    │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 3. 获取 Redisson 分布式锁                                        │
│                                                                  │
│    lockKey = "lock:contact:{min(A,B)}:{max(A,B)}"               │
│                                                                  │
│    为什么取 min/max？                                             │
│    保证 A→B 和 B→A 的操作使用同一把锁                            │
│    避免 A 同意 B 的申请时, B 也同时在同意 A 的申请               │
│                                                                  │
│    tryLock(3s, 10s) → 超时则重试                                 │
└──────────────────────────────────────────────────────────────────┘
  │ 拿到锁
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 4. @Transactional 事务:                                          │
│                                                                  │
│    4a. 再次校验 contact 表中 A→B 是否已存在 (防并发重复)         │
│                                                                  │
│    4b. 双向插入 contact:                                         │
│        INSERT (userId=A, contactId=B, status=1)                  │
│        INSERT (userId=B, contactId=A, status=1)                  │
│                                                                  │
│    4c. 清除双方 Redis 缓存:                                      │
│        DELETE contact:list:A*                                    │
│        DELETE contact:list:B*                                    │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 5. Feign → MessagingClient.createSingleSession(A, B)            │
│    为双方创建单聊会话                                            │
│                                                                  │
│ 6. Feign → RtcPushClient.pushNewSession(A)                      │
│    Feign → RtcPushClient.pushNewSession(B)                      │
│    向双方推送新会话通知                                          │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
释放 Redisson 锁
```

面试要点：
- **为什么需要分布式锁？** 双向插入好友关系需要操作两条记录。如果 A 和 B 同时同意对方的申请（或网络重试），不加锁可能插入重复记录。锁 key 用 `min:max` 保证双方使用同一把锁。
- **为什么锁外还要再查一次 contact 表？** 锁只保证串行执行，不保证幂等。可能第一个请求已经插入成功，第二个请求拿到锁后需要再校验以避免重复。
- **为什么 Feign 调用放在事务之后？** 好友关系的创建是核心操作，会话创建和通知推送是附属操作。即使 Feign 调用失败，好友关系已经正确持久化，用户下次打开 App 会通过其他方式看到新好友。

### 5.3 拒绝好友申请

```
用户B → POST /api/contact/reject { requestId: 123 }
    │
    ▼
校验: id=123, status=0, toUserId=B
    │
    ▼
UPDATE contact_request SET status = 2 (已拒绝)
```

### 5.4 删除好友

```
用户A → POST /api/contact/delete { contactId: B }
    │
    ▼
UPDATE contact SET status = 0  WHERE userId=A AND contactId=B
UPDATE contact SET status = 0  WHERE userId=B AND contactId=A
    │
    ▼
清除双方 Redis 联系人缓存
```

### 5.5 拉黑 / 取消拉黑

```
拉黑:   UPDATE contact SET status = 2 WHERE userId=当前用户 AND contactId=目标
取消拉黑: UPDATE contact SET status = 1 WHERE userId=当前用户 AND contactId=目标
    │
    ▼
清除当前用户 Redis 缓存
```

注意：拉黑是**单向**的——只修改当前用户视角的记录，对方的 status 不变。

### 5.6 联系人列表（Cache-Aside）

```
客户端
  │  GET /api/contact/list?contactType=0
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. Redis 查询                                                    │
│                                                                  │
│    key = contact:list:{userId}                                   │
│    (或 contact:list:{userId}:{contactType})                      │
│                                                                  │
│    → 命中 → 反序列化 JSON → 直接返回                             │
└──────────────────────────────────────────────────────────────────┘
  │ 未命中
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. MySQL 查询                                                    │
│                                                                  │
│    SELECT * FROM contact                                         │
│    WHERE userId = ? AND status IN (1, 2)                         │
│    [AND contactType = ?]                                         │
│    ORDER BY isPinned DESC, updatedAt DESC                        │
│                                                                  │
│ 3. 转为 ContactVO (补 nickname, avatar 等)                       │
│                                                                  │
│ 4. 写入 Redis, TTL = 1 小时                                     │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
返回 List<ContactVO>
```

### 5.7 搜索联系人

```
GET /api/contact/search?keyword=xxx
    │
    ▼
调用 getContactList() 获取全量联系人
    │
    ▼
内存中按 remark / nickname 过滤 keyword
```

面试要点：内存过滤对少量好友（几十到几百）是合理的，但如果好友数达到数千，应该考虑 MySQL LIKE 查询或 ES 全文检索。

### 5.8 用户注册自动创建 AI 助手

```
AuthenticationService (注册成功)
    │
    │  RocketMQ: USER_EVENT:REGISTER  body = userId
    │
    ▼
UserRegisterListener.onMessage()
    │
    │  解析 userId
    │
    ▼
ContactServiceImpl.createAiAssistant(userId)
    │
    │  1. 查 ai_assistant: 是否已存在 → 存在则跳过
    │  2. 生成 assistantId = 1_000_000_000 + userId
    │  3. INSERT ai_assistant (默认人设: modelType=qwen, personality=默认)
    │  4. INSERT contact (userId=userId, contactId=assistantId, contactType=1)
    │
    ▼
用户打开 App → 联系人列表中自动出现 "AI 助手"
```

面试要点：
- **assistantId = 1_000_000_000 + userId**：通过 ID 段隔离，保证 AI 助手的 ID 不会和真实用户 ID 冲突。
- **contactType=1** 区分人类好友 (0) 和 AI 助手 (1)。

---

## 六、好友关系状态机

```
           add            accept
  无关系 ─────▶ 待处理 ─────────▶ 好友 (status=1)
    ▲           (request          │
    │            status=0)        │ delete
    │                             ▼
    │ unblock               已删除 (status=0)
    │◀───────── 已拉黑 ◀────────
               (status=2)   block

  contact_request.status:  0=待处理, 1=已同意, 2=已拒绝
  contact.status:          0=已删除, 1=正常,   2=已拉黑
```

---

## 七、面试高频问题与回答思路

### Q1: 同意好友申请时为什么要加分布式锁？直接用数据库唯一索引不行吗？

> 数据库唯一索引 `(userId, contactId)` 确实能防止插入重复记录，但问题是好友关系是**双向**的——需要同时插入 A→B 和 B→A 两条记录。如果只靠唯一索引，可能出现 A→B 插入成功但 B→A 因为并发已被插入、导致一方多一方少的**不一致状态**。分布式锁保证整个"双向插入"操作是串行的，从根本上避免不一致。

### Q2: 锁 key 为什么用 `min(A,B):max(A,B)` 而不是 `A:B`？

> 如果用 `A:B` 作为 key，那么 A 操作 B 时锁的是 `A:B`，B 操作 A 时锁的是 `B:A`——两把不同的锁，无法互斥。取 `min:max` 保证无论谁发起操作，锁 key 都相同，形成有效互斥。

### Q3: 联系人搜索为什么在内存中过滤而不是查数据库？

> 当前设计下，联系人列表已经缓存在 Redis 中（或者刚从 MySQL 查出来）。好友数通常在几十到几百之间，内存过滤的开销可以忽略不计。如果走 MySQL `LIKE '%keyword%'`，会导致全表扫描（前置模糊查询无法利用索引），反而更慢。当然如果好友数达到数千级别，应该考虑引入 Elasticsearch。

### Q4: Feign 调用失败（创建会话/推送通知）会回滚好友关系吗？

> 不会。Feign 调用放在事务提交之后，降级工厂 `FallbackFactory` 只打日志返回 null。这是有意为之——好友关系是核心数据，会话创建和通知推送是可补偿的附属操作。用户下次打开 App 时客户端会检查并补建缺失的会话。如果要更强一致性，可以引入 RocketMQ 事务消息来替代 Feign 同步调用。

### Q5: 为什么用 `redisTemplate.keys()` 清缓存？有什么问题？

> `keys()` 底层是 Redis 的 `KEYS` 命令，在大数据量下会阻塞 Redis 主线程。当前场景下每个用户最多几个 key（按 contactType 分），影响有限。但生产环境建议改为 `SCAN` 或更细粒度的缓存 key 设计（比如固定枚举所有 contactType，逐个删除），避免 `KEYS` 的潜在风险。

### Q6: AI 助手的 ID 设计有什么讲究？

> `assistantId = 1_000_000_000 + userId`，利用 ID 段隔离，保证 AI 助手 ID 不会与真实用户 ID 冲突（真实用户 ID 远小于 10 亿）。同时通过 `contactType=1` 在联系人列表中区分人类和 AI。这种设计让 AI 助手可以复用整套联系人和消息体系，不需要单独的消息通道。

---

## 八、技术栈速查

| 技术 | 用途 |
|------|------|
| Spring Boot 2.6.13 | 基础框架 |
| OpenFeign + CircuitBreaker | 跨服务调用 + 降级 |
| Redisson | 分布式锁 |
| Redis (String) | 联系人列表缓存 |
| MySQL + MyBatis-Plus 3.5.2 | 好友关系、好友申请、AI 助手持久化 |
| RocketMQ 2.2.3 | 消费用户注册事件 |
| Nacos | 服务注册发现 |
