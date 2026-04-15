# InfiniteChat — 本地功能验证手册

## 前置：环境准备

### 1. 启动中间件 (Docker Compose)

在任意目录创建 `docker-compose.yml`，一键拉起所有中间件：

```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: infinite_chat
    command: --innodb-buffer-pool-size=256M --max-connections=200 --character-set-server=utf8mb4
    volumes:
      - mysql_data:/var/lib/mysql

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru

  nacos:
    image: nacos/nacos-server:v2.2.3
    ports:
      - "8848:8848"
      - "9848:9848"
    environment:
      MODE: standalone
      JVM_XMS: 256m
      JVM_XMX: 256m

  namesrv:
    image: apache/rocketmq:5.1.0
    ports:
      - "9876:9876"
    command: sh mqnamesrv
    environment:
      JAVA_OPT_EXT: "-Xms128m -Xmx256m"

  broker:
    image: apache/rocketmq:5.1.0
    ports:
      - "10911:10911"
      - "10909:10909"
    command: sh mqbroker -n namesrv:9876 --enable-proxy
    environment:
      JAVA_OPT_EXT: "-Xms256m -Xmx512m"
    depends_on:
      - namesrv

volumes:
  mysql_data:
```

```bash
docker-compose up -d
# 等待 30 秒让所有服务就绪
docker-compose ps   # 确认全部 running
```

### 2. 初始化数据库

连接 MySQL，执行建表脚本：

```bash
# 用你项目里的 SQL 脚本建表
# 例如 Messaging 模块:
mysql -h127.0.0.1 -uroot -proot123 infinite_chat < Messaging/src/main/resources/db/migration.sql
# Moment 模块:
mysql -h127.0.0.1 -uroot -proot123 infinite_chat < moment/sql/moment_tables.sql
# 其他模块如果有 SQL 也执行
# AgentService:
mysql -h127.0.0.1 -uroot -proot123 infinite_chat < AgentService/src/main/resources/init_agent.sql
```

> 如果部分模块没有建表 SQL，启动服务后 MyBatis-Plus 可能不会自动建表。需要根据实体类手动补建表语句。

### 3. 启动业务服务

按依赖顺序启动（每个开一个终端窗口）：

```bash
# 确保各模块 application.yml 里的 MySQL/Redis/RocketMQ/Nacos 地址正确

# 1. Gateway (其他服务的入口)
cd Gateway && mvn spring-boot:run

# 2. AuthenticationService
cd AuthenticationService && mvn spring-boot:run

# 3. Contact
cd contact && mvn spring-boot:run

# 4. Messaging
cd Messaging && mvn spring-boot:run

# 5. RTC
cd RealTimeCommunication && mvn spring-boot:run

# 6. OfflineService
cd OfflineService && mvn spring-boot:run

# 7. Moment
cd moment && mvn spring-boot:run
```

启动后在 Nacos 控制台 http://localhost:8848/nacos (nacos/nacos) 确认 7 个服务都已注册。

---

## 验证流程：从注册到收消息

以下所有请求通过 Gateway (8080) 发出，模拟真实客户端。

> 提示：每一步的响应中提取下一步需要的字段（token、userId 等），替换后续命令中的占位符。

---

### 第一步：注册两个用户

```bash
# 注册用户 A
curl -X POST http://localhost:8080/api/v1/user/noToken/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "alice",
    "password": "123456",
    "nickname": "Alice"
  }'

# 注册用户 B
curl -X POST http://localhost:8080/api/v1/user/noToken/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "bob",
    "password": "123456",
    "nickname": "Bob"
  }'
```

**预期**：返回成功，各自包含 userId。记录下来：
```
ALICE_ID=<返回的userId>
BOB_ID=<返回的userId>
```

**验证点**：
- [ ] 两个用户都注册成功
- [ ] Contact 模块日志中看到消费 USER_EVENT:REGISTER，自动创建了 AI 助手

---

### 第二步：登录获取 Token

```bash
# 登录 Alice
curl -X POST http://localhost:8080/api/v1/user/noToken/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "alice",
    "password": "123456"
  }'

# 登录 Bob
curl -X POST http://localhost:8080/api/v1/user/noToken/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "bob",
    "password": "123456"
  }'
```

**预期**：返回 accessToken 和 refreshToken。记录下来：
```
ALICE_TOKEN=<Alice的accessToken>
BOB_TOKEN=<Bob的accessToken>
```

**验证点**：
- [ ] 登录成功，返回双 Token
- [ ] Redis 中可以看到路由信息 `im:route:{userId}`

---

### 第三步：添加好友

```bash
# Alice 向 Bob 发送好友申请
curl -X POST http://localhost:8080/api/contact/add \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -d '{
    "userId": '${ALICE_ID}',
    "contactId": '${BOB_ID}',
    "remark": "我是Alice"
  }'
```

**验证点**：
- [ ] 返回成功
- [ ] MySQL contact_request 表中有一条 status=0 的记录

```bash
# Bob 查看待处理的好友申请
curl -X GET "http://localhost:8080/api/contact/pending" \
  -H "Authorization: Bearer ${BOB_TOKEN}"
```

**验证点**：
- [ ] 返回包含 Alice 的申请，记录 requestId

```bash
# Bob 同意好友申请
curl -X POST http://localhost:8080/api/contact/accept \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -d '{
    "requestId": <上一步返回的requestId>
  }'
```

**验证点**：
- [ ] 返回成功
- [ ] MySQL contact 表中有两条记录（A→B 和 B→A，status=1）
- [ ] Messaging 模块日志中看到创建了单聊会话
- [ ] MySQL im_session 表中有对应会话记录，记录 `SESSION_ID`

---

### 第四步：查看联系人列表

```bash
# Alice 查看联系人
curl -X GET "http://localhost:8080/api/contact/list" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
```

**验证点**：
- [ ] 返回列表中包含 Bob
- [ ] 列表中包含 AI 助手（contactType=1）

---

### 第五步：WebSocket 连接（RTC）

用两个终端窗口分别连接：

```bash
# 安装 wscat (如果没有)
npm install -g wscat

# 终端1: Alice 连接 WebSocket
wscat -c "ws://localhost:9090/ws?token=${ALICE_TOKEN}"

# 终端2: Bob 连接 WebSocket
wscat -c "ws://localhost:9090/ws?token=${BOB_TOKEN}"
```

**验证点**：
- [ ] 两个连接都成功建立
- [ ] RTC 日志中看到两个用户的路由注册
- [ ] Redis `im:route:{userId}` 更新为当前 RTC 节点

连接后发送心跳测试：
```
# 在 wscat 中输入:
{"type":"ping"}
```

**验证点**：
- [ ] 收到 pong 响应

---

### 第六步：发送消息（核心链路）

保持两个 WebSocket 连接开着，在新终端发送消息：

```bash
# Alice 给 Bob 发消息
curl -X POST http://localhost:8080/api/message/send \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -d '{
    "sessionId": "'${SESSION_ID}'",
    "content": "你好 Bob！",
    "clientMsgId": "test-msg-001",
    "messageType": 1
  }'
```

**验证点**：
- [ ] curl 返回成功（消息 ACK）
- [ ] Bob 的 wscat 终端收到推送消息 ← **最关键的验证**
- [ ] MySQL im_message 表中有这条消息（异步写入，可能有几秒延迟）
- [ ] 再发一次相同的 clientMsgId，应返回"重复消息"（幂等校验）

```bash
# Bob 回复 Alice
curl -X POST http://localhost:8080/api/message/send \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -d '{
    "sessionId": "'${SESSION_ID}'",
    "content": "你好 Alice！",
    "clientMsgId": "test-msg-002",
    "messageType": 1
  }'
```

**验证点**：
- [ ] Alice 的 wscat 终端收到推送消息

---

### 第七步：离线消息验证

```bash
# 1. 关掉 Bob 的 wscat 连接 (Ctrl+C)

# 2. Alice 再发一条消息给 Bob
curl -X POST http://localhost:8080/api/message/send \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -d '{
    "sessionId": "'${SESSION_ID}'",
    "content": "Bob 你在吗？",
    "clientMsgId": "test-msg-003",
    "messageType": 1
  }'
```

等待 3-5 秒让消息走完 RTC推送失败 → MQ回流 → Offline存储 的链路。

```bash
# 3. Bob 查看未读数
curl -X GET "http://localhost:8080/api/offline/unread" \
  -H "Authorization: Bearer ${BOB_TOKEN}"

# 4. Bob 拉取离线消息
curl -X POST http://localhost:8080/api/offline/pull \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -d '{
    "sessionId": "'${SESSION_ID}'",
    "lastSeq": 0,
    "pageSize": 50
  }'

# 5. Bob 标记已读
curl -X POST http://localhost:8080/api/offline/read \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -d '{
    "sessionId": "'${SESSION_ID}'"
  }'
```

**验证点**：
- [ ] 未读数返回 1（或 >0）
- [ ] 拉取到 "Bob 你在吗？" 这条消息
- [ ] 标记已读后再查未读数为 0
- [ ] Redis 中 `im:offline:{bobId}:{sessionId}` ZSet 有数据
- [ ] MySQL offline_message 表有对应记录

---

### 第八步：朋友圈验证

```bash
# Alice 发朋友圈
curl -X POST http://localhost:8080/api/moment/publish \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -d '{
    "content": "今天天气真好！"
  }'
# 记录返回的 MOMENT_ID

# Bob 查看好友朋友圈
curl -X GET "http://localhost:8080/api/moment/friends?page=1&size=20" \
  -H "Authorization: Bearer ${BOB_TOKEN}"

# Bob 点赞
curl -X POST http://localhost:8080/api/moment/like \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -d '{
    "momentId": '${MOMENT_ID}',
    "userId": '${BOB_ID}'
  }'

# Bob 评论
curl -X POST http://localhost:8080/api/moment/comment \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -d '{
    "momentId": '${MOMENT_ID}',
    "userId": '${BOB_ID}',
    "content": "确实！"
  }'

# 重复点赞应失败
curl -X POST http://localhost:8080/api/moment/like \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -d '{
    "momentId": '${MOMENT_ID}',
    "userId": '${BOB_ID}'
  }'
```

**验证点**：
- [ ] 发布成功
- [ ] Bob 能看到 Alice 的动态
- [ ] 点赞成功，Alice 的 wscat 收到点赞通知
- [ ] 评论成功
- [ ] 重复点赞被拒绝（幂等）
- [ ] Redis `moment:like:{momentId}` Set 中有 Bob

---

### 第九步：登出与 Token 失效验证

```bash
# Alice 登出
curl -X POST http://localhost:8080/api/v1/user/logout \
  -H "Authorization: Bearer ${ALICE_TOKEN}"

# 用旧 Token 访问任意接口，应被拒绝
curl -X GET "http://localhost:8080/api/contact/list" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
```

**验证点**：
- [ ] 登出成功
- [ ] 旧 Token 访问返回 401（登出时间戳机制生效）
- [ ] Redis `jwt:logout:{aliceId}` 有值

---

## 验证清单汇总

```
基础设施:
  [ ] MySQL 连接正常，表已创建
  [ ] Redis 连接正常
  [ ] RocketMQ NameSrv + Broker 启动正常
  [ ] Nacos 启动正常，所有服务已注册

认证链路:
  [ ] 注册成功
  [ ] 登录返回双 Token
  [ ] 登出后旧 Token 失效
  [ ] 无 Token 访问被 Gateway 拦截

联系人:
  [ ] 好友申请 → 同意 → 双向关系建立
  [ ] 联系人列表包含好友和 AI 助手
  [ ] 注册自动创建 AI 助手 (MQ 事件驱动)

消息核心链路:
  [ ] 发消息返回 ACK
  [ ] 对方 WebSocket 实时收到消息
  [ ] clientMsgId 重复发送被拦截
  [ ] MySQL 异步落库成功

离线消息:
  [ ] 对方不在线 → 消息回流离线服务
  [ ] 未读数正确
  [ ] 拉取离线消息正确
  [ ] 标记已读后未读数清零

朋友圈:
  [ ] 发布动态
  [ ] 好友可见
  [ ] 点赞 / 取消点赞 / 重复点赞拦截
  [ ] 评论

WebSocket:
  [ ] 连接成功
  [ ] 心跳正常
  [ ] 消息推送正常
  [ ] 断连后路由清除
```

---

## 常见问题排查

| 现象 | 可能原因 | 排查方法 |
|------|----------|----------|
| 服务启动失败 | 中间件没起来 / 配置地址错 | 检查 application.yml 中的 host:port |
| 注册成功但 AI 助手没创建 | RocketMQ Topic 不存在 | RocketMQ 控制台查 USER_EVENT topic |
| 消息发送成功但对方没收到 | 路由不对 / RTC 没消费到 | 检查 Redis `im:route:{userId}` |
| 离线消息拉不到 | OFFLINE tag 没消费 | 查 Offline 服务日志和 MQ 消费组 |
| Gateway 502 | 下游服务没注册到 Nacos | Nacos 控制台确认服务列表 |
