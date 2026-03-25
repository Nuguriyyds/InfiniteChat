# InfiniteChat 压测方案

> 环境：8G 业务服务器 `47.98.243.139`，4G 中间件服务器 `47.97.100.24`（MySQL + Redis + RocketMQ + Nacos）
> 网关入口：`http://47.98.243.139:10010`

---

## 一、压测目标

| 接口 | 实际路径 | 目标 QPS | P99 延迟 | 成功率 |
|------|---------|---------|---------|-------|
| 密码登录 | POST /api/v1/user/noToken/loginPwd | 500 QPS | < 300ms | > 99% |
| 单聊发消息 | POST /api/message/send | 1000 QPS | < 100ms | > 99% |
| 消息同步拉取 | GET /api/message/sync | 2000 QPS | < 80ms | > 99% |
| 抢红包 | POST /api/message/redpacket/receive | 300 QPS | < 150ms | > 99% |

> 注：2核机器跑全套中间件，QPS 目标已按实际硬件降档，先摸底再逐步加压。

---

## 二、压测工具选型

推荐 **wrk2**（恒定速率控制精准），辅以 **JMeter**（抢红包等有状态场景）。

```bash
# 在 8G 业务服务器上安装
apt-get install -y wrk
# wrk2（支持 -R 恒定速率）
git clone https://github.com/giltene/wrk2 && cd wrk2 && make
```

---

## 三、测试数据准备（压测前必做）

```bash
# 1. 直接在 MySQL 里执行 SQL 脚本（无需 Python）
mysql -h47.97.100.24 -P49152 -uroot -p'gK3T9n%q2M@j7Z4' InfiniteChat < generate_data.sql

# 2. 验证数据
mysql -h47.97.100.24 -P49152 -uroot -p'gK3T9n%q2M@j7Z4' InfiniteChat \
  -e "SELECT COUNT(*) AS users FROM user WHERE user_id BETWEEN 1000000001 AND 1000001000;
      SELECT COUNT(*) AS friends FROM im_friend WHERE user_id BETWEEN 1000000001 AND 1000001000;
      SELECT COUNT(*) AS messages FROM im_message WHERE sender_id LIKE '10000%';"

# 3. Redis 预热（在 MySQL 所在机器执行，或任意能连 Redis 的机器）
# 设置 Redis 内存上限（4G 机器跑 MySQL+Redis+RMQ+Nacos，Redis 限 768MB）
redis-cli -h 47.97.100.24 -p 6399 -a e65K4t8w2 CONFIG SET maxmemory 768mb
redis-cli -h 47.97.100.24 -p 6399 -a e65K4t8w2 CONFIG SET maxmemory-policy allkeys-lru

# 4. 测试账号信息
# user_id 范围: 1000000001 ~ 1000001000
# 用户名: testuser_0001 ~ testuser_1000
# 统一密码: Test@123456
```

---

## 四、压测脚本

### 4.1 登录接口

路径：`POST /api/v1/user/noToken/loginPwd`
请求体：`{"phone":"xxx","password":"Test@123456"}`

```bash
# 在 8G 服务器上创建 lua 脚本
cat > /opt/infinitechat/login.lua << 'LUAEOF'
wrk.method  = "POST"
wrk.headers["Content-Type"] = "application/json"
local phones = {}
function init(args)
  local f = io.open("/opt/infinitechat/phones.txt", "r")
  if f then
    for line in f:lines() do table.insert(phones, line) end
    f:close()
  end
end
local idx = 0
function request()
  idx = (idx % #phones) + 1
  wrk.body = '{"phone":"' .. phones[idx] .. '","password":"Test@123456"}'
  return wrk.format(nil)
end
LUAEOF

# 压测
wrk2 -t4 -c50 -d60s -R100 -L -s /opt/infinitechat/login.lua http://127.0.0.1:10010/api/v1/user/noToken/loginPwd
```

### 4.2 压测前准备：批量登录获取 Token

消息接口需要多用户并发，不能用单个固定 Token。先执行批量登录脚本：

```bash
# 将脚本上传到 8G 业务服务器
scp scripts/batch_login.sh root@47.98.243.139:/opt/infinitechat/
scp scripts/send_msg.lua   root@47.98.243.139:/opt/infinitechat/
scp scripts/sync_msg.lua   root@47.98.243.139:/opt/infinitechat/

# 在 8G 服务器上执行（默认登录 100 个用户，可传参调整）
bash /opt/infinitechat/batch_login.sh 100

# 输出文件:
#   tokens.txt        -> userId|token
#   send_msg_data.txt -> userId|token|sessionId|receiveUserId
#   session_ids.txt   -> 去重的 sessionId 列表
```

> 注意：BCrypt 验证很吃 CPU，脚本内置了 50ms 间隔控制速率。100 个用户约需 10 秒。

### 4.3 单聊发消息

路径：`POST /api/message/send`
需要 Header：`Authorization: Bearer <token>` + `X-User-Id: <userId>`
请求体字段：`clientMsgId, sessionId, sessionType, type, body, receiveUserId`

脚本文件：`send_msg.lua`（多用户轮询版，每个请求自动切换不同用户的 Token 和会话）

核心逻辑：
- 从 `send_msg_data.txt` 加载 `userId|token|sessionId|receiveUserId`
- 每个请求轮询不同用户，模拟真实多用户并发
- `clientMsgId` 包含 userId + 时间戳 + 随机数，保证全局唯一（幂等防重）

```bash
# 阶梯式加压（每轮 60 秒）
# 第 1 轮：摸底
wrk2 -t2 -c20  -d60s -R100  -L -s /opt/infinitechat/send_msg.lua http://127.0.0.1:10010/api/message/send

# 第 2 轮：中等压力
wrk2 -t4 -c50  -d60s -R300  -L -s /opt/infinitechat/send_msg.lua http://127.0.0.1:10010/api/message/send

# 第 3 轮：目标压力
wrk2 -t4 -c100 -d60s -R500  -L -s /opt/infinitechat/send_msg.lua http://127.0.0.1:10010/api/message/send

# 第 4 轮：极限探测
wrk2 -t8 -c200 -d60s -R1000 -L -s /opt/infinitechat/send_msg.lua http://127.0.0.1:10010/api/message/send
```

关注指标：
- P99 延迟是否 < 100ms
- Redis `im:seq:*` 自增是否成为热点（`redis-cli --latency`）
- RocketMQ 消费积压（`mqadmin consumerProgress`）
- MySQL `im_message` 插入 IOPS（`SHOW GLOBAL STATUS LIKE 'Innodb_rows_inserted'`）

### 4.4 消息同步拉取

路径：`GET /api/message/sync?sessionId=xxx&beginSeq=0&endSeq=50`
需要 Header：`Authorization: Bearer <token>` + `X-User-Id: <userId>`

脚本文件：`sync_msg.lua`（复用 `send_msg_data.txt`，随机 beginSeq 模拟不同拉取位置）

```bash
# 第 1 轮：摸底
wrk2 -t2 -c20  -d60s -R200  -L -s /opt/infinitechat/sync_msg.lua http://127.0.0.1:10010/api/message/sync

# 第 2 轮：中等压力
wrk2 -t4 -c50  -d60s -R500  -L -s /opt/infinitechat/sync_msg.lua http://127.0.0.1:10010/api/message/sync

# 第 3 轮：目标压力
wrk2 -t4 -c100 -d60s -R1000 -L -s /opt/infinitechat/sync_msg.lua http://127.0.0.1:10010/api/message/sync

# 第 4 轮：极限探测
wrk2 -t8 -c200 -d60s -R2000 -L -s /opt/infinitechat/sync_msg.lua http://127.0.0.1:10010/api/message/sync
```

关注指标：
- P99 延迟是否 < 80ms
- MySQL `idx_session_time` 索引命中率（`EXPLAIN` 确认走索引）
- 慢查询日志（`SHOW PROCESSLIST` 看是否有全表扫描）

### 4.5 抢红包（群聊拼手气红包）

路径：`POST /api/message/redpacket/receive`
需要 Header：`Authorization: Bearer <token>` + `X-User-Id: <userId>`
请求体：`{"redPacketId": 6000000000000}`

#### 4.5.1 数据准备

```bash
# 1. 生成群聊红包测试数据（10 个群 × 500 人 + 100 个拼手气红包，每个 50 名额）
mysql -h47.97.100.24 -P49152 -uroot -p'gK3T9n%q2M@j7Z4' InfiniteChat < generate_redpacket_data.sql

# 2. 验证数据
mysql -h47.97.100.24 -P49152 -uroot -p'gK3T9n%q2M@j7Z4' InfiniteChat \
  -e "SELECT COUNT(*) AS '群聊红包数' FROM red_packet WHERE red_packet_id BETWEEN 6000000000000 AND 6000000000099;
      SELECT COUNT(*) AS '群成员数' FROM im_user_session WHERE session_id LIKE 'group_redpacket_%';"

# 3. Redis 预热（二倍均值法预计算金额，修正 key 前缀与代码对齐）
scp scripts/redis_warmup_redpacket.sh root@47.98.243.139:/opt/infinitechat/
bash /opt/infinitechat/redis_warmup_redpacket.sh

# 4. 验证 Redis 数据
redis-cli -h 47.97.100.24 -p 6399 -a e65K4t8w2 GET "red_packet:count:6000000000000"
redis-cli -h 47.97.100.24 -p 6399 -a e65K4t8w2 LLEN "red_packet:amounts:6000000000000"
```

> ⚠️ 注意：旧的 `redis_warmup.sh` 使用了错误的 key 前缀 `red_packet:{id}`，
> 代码中实际使用的是 `red_packet:count:{id}`。新脚本 `redis_warmup_redpacket.sh` 已修正。

#### 4.5.2 压测脚本

脚本文件：`grab_redpacket.lua`（wrk2 多用户轮询版）

核心逻辑：
- 从 `tokens.txt` 加载已登录用户的 userId + Token
- 每个请求轮询不同用户，模拟多人并发抢同一个红包
- 检测响应中 `status:2`（已领完）自动切换下一个红包
- 100 个红包 × 50 名额 = 5000 次成功抢红包机会

```bash
# 上传脚本
scp scripts/grab_redpacket.lua root@47.98.243.139:/opt/infinitechat/

# 确保 tokens.txt 已存在（batch_login.sh 生成）
wc -l /opt/infinitechat/tokens.txt

# 阶梯式加压
# 第 1 轮：摸底（10 QPS，验证链路通畅）
wrk2 -t2 -c10  -d30s -R10   -L -s /opt/infinitechat/grab_redpacket.lua http://127.0.0.1:10010/api/message/redpacket/receive

# 第 2 轮：低压（50 QPS，观察 Lua 脚本 + MQ 事务是否正常）
wrk2 -t2 -c20  -d60s -R50   -L -s /opt/infinitechat/grab_redpacket.lua http://127.0.0.1:10010/api/message/redpacket/receive

# 第 3 轮：中等压力（150 QPS，观察 DB 落库是否积压）
wrk2 -t4 -c50  -d60s -R150  -L -s /opt/infinitechat/grab_redpacket.lua http://127.0.0.1:10010/api/message/redpacket/receive

# 第 4 轮：目标压力（300 QPS）
wrk2 -t4 -c100 -d60s -R300  -L -s /opt/infinitechat/grab_redpacket.lua http://127.0.0.1:10010/api/message/redpacket/receive

# 第 5 轮：极限探测（500 QPS）
wrk2 -t8 -c200 -d60s -R500  -L -s /opt/infinitechat/grab_redpacket.lua http://127.0.0.1:10010/api/message/redpacket/receive
```

> ⚠️ 每轮压测前需要重新执行 `generate_redpacket_data.sql` + `redis_warmup_redpacket.sh` 重置红包数据！
> 因为红包抢完后 Redis 库存归零，无法复用。

#### 4.5.3 压测后验证

```bash
# 检查数据一致性：Redis 扣减 vs DB 落库
mysql -h47.97.100.24 -P49152 -uroot -p'gK3T9n%q2M@j7Z4' InfiniteChat -e "
  -- 每个红包的领取记录数应 <= 50
  SELECT red_packet_id, COUNT(*) AS receive_count
  FROM red_packet_receive
  WHERE red_packet_id BETWEEN 6000000000000 AND 6000000000099
  GROUP BY red_packet_id
  ORDER BY receive_count DESC
  LIMIT 10;

  -- 检查是否有重复领取（应为 0）
  SELECT red_packet_id, receiver_id, COUNT(*) AS cnt
  FROM red_packet_receive
  WHERE red_packet_id BETWEEN 6000000000000 AND 6000000000099
  GROUP BY red_packet_id, receiver_id
  HAVING cnt > 1;

  -- 金额一致性：每个红包领取总额应 = 50.00
  SELECT rp.red_packet_id,
         rp.total_amount,
         IFNULL(SUM(rpr.amount), 0) AS received_total,
         rp.remaining_amount
  FROM red_packet rp
  LEFT JOIN red_packet_receive rpr ON rpr.red_packet_id = rp.red_packet_id
  WHERE rp.red_packet_id BETWEEN 6000000000000 AND 6000000000099
  GROUP BY rp.red_packet_id
  HAVING received_total != rp.total_amount - rp.remaining_amount
  LIMIT 10;
"

# 检查 Redis pending hash 是否已清理（落库完成后应为空）
redis-cli -h 47.97.100.24 -p 6399 -a e65K4t8w2 HLEN "red_packet:pending:6000000000000"
```

关注指标：
- P99 延迟是否 < 150ms
- Redis Lua 脚本执行耗时（`redis-cli --latency` + `SLOWLOG GET 20`）
- RocketMQ `IM_RED_PACKET_RECEIVE` 消费积压
- MySQL `red_packet` 行锁等待（`SELECT * FROM information_schema.INNODB_TRX\G`）
- 数据一致性：无重复领取、金额对账无差异

---

## 五、核心监控指标

### 5.1 业务服务器（8G, 47.98.243.139）

```bash
# CPU / 内存 / Load
watch -n2 'uptime && free -h && top -bn1 | head -20'

# JVM GC
jstat -gcutil <PID> 2000

# 网络连接数
ss -s
```

### 5.2 中间件服务器（4G, 47.97.100.24）

```bash
# MySQL QPS
mysqladmin -uroot -p'gK3T9n%q2M@j7Z4' extended-status -i2 | grep Questions

# MySQL 慢查询 / 锁
mysql -uroot -p'gK3T9n%q2M@j7Z4' -e "SHOW PROCESSLIST"

# Redis 内存（重点关注 used_memory 不超过 768MB）
redis-cli -p 6399 -a e65K4t8w2 info memory | grep used_memory_human

# RocketMQ 消费积压
# 通过 RocketMQ Dashboard 或命令行
mqadmin consumerProgress -n 172.30.233.168:9876 -g <消费者组名>

# Docker 全局资源
docker stats --no-stream
```

### 5.3 关键指标阈值

| 指标 | 警戒线 | 说明 |
|------|--------|------|
| CPU (业务 8G) | > 80% | 2核瓶颈，考虑限流 |
| JVM Old Gen | > 75% | Full GC 风险 |
| MySQL QPS | > 5000 | 2核 + 共享机器，保守估计 |
| MySQL 连接数 | > 100 | HikariCP max=30 × 多服务 |
| Redis used_memory | > 700MB | 768MB 上限，留 buffer |
| Redis rejected_connections | > 0 | 连接池耗尽 |
| RMQ 消费积压 | > 10000 | 消费者处理不过来 |
| 4G 机器总内存 | > 3.5GB | 留 500MB 给 OS |

---

## 六、性能拐点预测与排查思路

### 拐点 1：登录接口延迟飙升（QPS > 300）
**根因**：BCrypt 验证 CPU 密集，cost=10 单次约 100~200ms，2核很快打满。

```bash
top -H -p <AuthService_PID>
```
优化：压测时临时降 BCrypt cost 到 4；或 Redis 缓存 Token 跳过重复验证。

### 拐点 2：发消息 P99 升高（QPS > 800）
**根因**：RocketMQ 异步发送积压 / MySQL im_message 插入 IOPS 打满。

```bash
# RocketMQ 积压检查
mqadmin consumerProgress -n 172.30.233.168:9876 -g <group>
# MySQL insert 速率
mysql -e "SHOW GLOBAL STATUS LIKE 'Innodb_rows_inserted'"
```
优化：消息异步批量落库；im_message 按 session_id 分区。

### 拐点 3：抢红包并发失败（并发 > 100）
**根因**：Redis 分布式锁竞争 / DB 行锁等待。

```bash
redis-cli -p 6399 -a e65K4t8w2 slowlog get 20
mysql -e "SELECT * FROM information_schema.INNODB_TRX\G"
```
优化：你的代码已经用了预分配金额 List + LPOP，这条路是对的。重点看 DB 扣款是否成为瓶颈。

### 拐点 4：4G 中间件机器内存耗尽
**根因**：MySQL buffer pool + Redis + RocketMQ Broker + Nacos 争抢 4GB 内存。

```bash
docker stats --no-stream
free -h
```
优化：
- MySQL `innodb_buffer_pool_size` 限制到 512MB
- Redis `maxmemory` 768MB + allkeys-lru
- RocketMQ Broker JVM 堆限制到 512MB
- Nacos JVM 堆限制到 256MB

### 拐点 5：网关 Gateway 吞吐瓶颈
**根因**：WebFlux 默认 Netty worker = 2×CPU = 4 线程。

```bash
jstack <GatewayPID> | grep "reactor-http-nio"
```
优化：`-Dreactor.netty.ioWorkerCount=8`

---

## 七、压测执行检查清单

### 7.1 通用准备
- [ ] `generate_data.sql` 执行成功，DB 有 1000 用户
- [ ] Redis maxmemory 设为 768MB，策略 allkeys-lru
- [ ] wrk2 编译安装完成
- [ ] 监控脚本就绪（top / jstat / docker stats）

### 7.2 登录接口压测
- [ ] 导出 phones.txt（`SELECT phone FROM user WHERE user_id BETWEEN ...`）
- [ ] 手动 curl 验证登录接口通畅

### 7.3 消息接口压测
- [ ] 上传脚本到服务器：`batch_login.sh`、`send_msg.lua`、`sync_msg.lua`
- [ ] 执行 `bash batch_login.sh 100`，确认输出文件非空
- [ ] 验证 `send_msg_data.txt` 行数 > 0（`wc -l /opt/infinitechat/send_msg_data.txt`）
- [ ] 手动 curl 验证发消息接口通畅（用 tokens.txt 中任一 token）
- [ ] 手动 curl 验证 sync 接口通畅

### 7.4 抢红包压测
- [ ] 执行 `generate_redpacket_data.sql`，DB 有 100 个群聊红包 + 10 个群（每群 500 人）
- [ ] 执行 `redis_warmup_redpacket.sh` 预热红包数据到 Redis
- [ ] 验证 Redis key：`redis-cli GET red_packet:count:6000000000000` 应返回 `50`
- [ ] 验证 Redis key：`redis-cli LLEN red_packet:amounts:6000000000000` 应返回 `50`
- [ ] 确保 `tokens.txt` 已存在（`batch_login.sh` 生成）
- [ ] 上传 `grab_redpacket.lua` 到服务器
- [ ] 手动 curl 验证抢红包接口通畅：
  ```bash
  TOKEN=$(head -1 /opt/infinitechat/tokens.txt | cut -d'|' -f2)
  UID=$(head -1 /opt/infinitechat/tokens.txt | cut -d'|' -f1)
  curl -s -X POST http://127.0.0.1:10010/api/message/redpacket/receive \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-User-Id: $UID" \
    -d '{"redPacketId":6000000000000}'
  ```
- [ ] 手动验证后重新执行 `generate_redpacket_data.sql` + `redis_warmup_redpacket.sh` 重置数据

---

## 八、阶梯式压测流程

### 8.1 登录接口

| 轮次 | QPS | 线程/连接 | 观察重点 |
|------|-----|----------|---------|
| 1 | 50 | -t2 -c20 | 基线，确认链路通畅 |
| 2 | 100 | -t4 -c50 | CPU 是否超 50% |
| 3 | 300 | -t4 -c100 | BCrypt 是否打满 CPU |
| 4 | 500 | -t8 -c200 | 找到极限拐点 |

### 8.2 单聊发消息

| 轮次 | QPS | 线程/连接 | 观察重点 |
|------|-----|----------|---------|
| 1 | 100 | -t2 -c20 | 基线，Redis seq 自增正常 |
| 2 | 300 | -t4 -c50 | RocketMQ 投递延迟 |
| 3 | 500 | -t4 -c100 | P99 是否 < 100ms |
| 4 | 1000 | -t8 -c200 | MySQL 插入 IOPS、MQ 积压 |

### 8.3 消息同步拉取

| 轮次 | QPS | 线程/连接 | 观察重点 |
|------|-----|----------|---------|
| 1 | 200 | -t2 -c20 | 基线，确认走索引 |
| 2 | 500 | -t4 -c50 | MySQL 慢查询 |
| 3 | 1000 | -t4 -c100 | P99 是否 < 80ms |
| 4 | 2000 | -t8 -c200 | 找到极限拐点 |

### 8.4 抢红包

| 轮次 | QPS | 线程/连接 | 观察重点 |
|------|-----|----------|---------|
| 1 | 10 | -t2 -c10 | 摸底，验证 Lua 脚本 + MQ 事务链路通畅 |
| 2 | 50 | -t2 -c20 | Redis Lua 执行耗时、MQ 事务提交延迟 |
| 3 | 150 | -t4 -c50 | DB 异步落库是否积压、行锁等待 |
| 4 | 300 | -t4 -c100 | P99 是否 < 150ms，目标压力 |
| 5 | 500 | -t8 -c200 | 极限探测，找到拐点 |

> ⚠️ 抢红包与其他接口不同：每轮压测前必须重置数据（SQL + Redis 预热），因为红包抢完不可复用。
> 每轮 60 秒（摸底轮 30 秒），轮次之间间隔 30 秒，让 JVM GC 和连接池回收。
> 建议按顺序压测：登录 → 发消息 → ~~同步拉取~~ → 抢红包，避免交叉干扰。
