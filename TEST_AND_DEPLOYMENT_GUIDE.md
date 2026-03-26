# InfiniteChat 完整测试与部署指南

## 目录
1. [环境准备](#1-环境准备)
2. [接口测试](#2-接口测试)
3. [压力测试](#3-压力测试)
4. [部署流程](#4-部署流程)

---

## 1. 环境准备

### 1.1 基础设施依赖

项目依赖以下中间件，测试和部署前必须确保全部启动：

| 组件 | 地址 | 用途 |
|------|------|------|
| Nacos | 172.30.233.168:18375 | 服务注册与配置中心 |
| Redis | 172.30.233.168:59000 | 缓存、分布式锁、在线状态 |
| MySQL | 172.30.233.168:49152 | 持久化存储 |
| RocketMQ | 172.30.233.168:9876 | 消息队列 |
| MinIO | 172.30.233.168:9000 | 对象存储 |

### 1.2 服务端口清单

| 服务名称 | 端口 | 说明 |
|---------|------|------|
| Gateway | 10010 | 统一网关入口 |
| AuthenticationService | 8082 | 认证服务 |
| RealTimeCommunicationService | 8083 (HTTP) / 9101 (WebSocket) | 实时通信 |
| MessagingService | 8084 | 消息服务 |
| ContactService | 待确认 | 联系人服务 |
| MomentService | 待确认 | 朋友圈服务 |

### 1.3 环境检查脚本

```bash
# 检查所有基础设施是否启动
echo "=== 检查 Nacos ==="
curl -s http://172.30.233.168:18375/nacos/v1/console/health/readiness

echo "=== 检查 Redis ==="
redis-cli -h 172.30.233.168 -p 59000 -a e65K4t8w2 ping

echo "=== 检查 MySQL ==="
mysql -h 172.30.233.168 -P 49152 -u root -p'gK3T9n%q2M@j7Z4' -e "SELECT 1"

echo "=== 检查 RocketMQ ==="
curl -s http://172.30.233.168:9876/

echo "=== 检查 MinIO ==="
curl -s http://172.30.233.168:9000/minio/health/live
```

---

## 2. 接口测试

### 2.1 测试工具准备

推荐使用以下工具进行接口测试：
- **Postman**: 适合手动测试和接口调试
- **JMeter**: 适合自动化测试和压力测试
- **curl**: 适合快速验证

### 2.2 核心接口测试流程

#### 2.2.1 启动所有服务

```bash
# 方式1: 使用 Maven 分别启动各个服务
cd AuthenticationService
mvn spring-boot:run

cd ../Gateway
mvn spring-boot:run

cd ../RealTimeCommunicationService
mvn spring-boot:run

cd ../MessagingService
mvn spring-boot:run

# 方式2: 使用 IDEA 批量启动
# 在 IDEA 中配置 Compound Run Configuration，一键启动所有服务
```

#### 2.2.2 用户注册与登录测试

**1. 用户注册**

```bash
curl -X POST http://172.30.233.168:10010/api/v1/user/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser001",
    "password": "Test@123456",
    "email": "testuser001@example.com",
    "phone": "13800138000"
  }'
```

**2. 用户登录**

```bash
curl -X POST http://172.30.233.168:10010/api/v1/user/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser001",
    "password": "Test@123456"
  }'
```

**预期响应**:
```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "userId": "1001",
    "username": "testuser001"
  }
}
```

**3. 保存 Token**

将返回的 token 保存到环境变量，后续请求都需要携带：

```bash
export TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

#### 2.2.3 WebSocket 连接测试

**1. 建立 WebSocket 连接**

使用 WebSocket 客户端工具（如 wscat）：

```bash
# 安装 wscat
npm install -g wscat

# 连接到 WebSocket 服务
wscat -c "ws://172.30.233.168:9101/api/v1/chat/message?token=${TOKEN}"
```

**2. 发送消息**

连接成功后，发送消息：

```json
{
  "type": "CHAT",
  "toUserId": "1002",
  "content": "Hello, this is a test message",
  "messageType": "TEXT"
}
```

#### 2.2.4 消息服务测试

**1. 发送消息（HTTP 方式）**

```bash
curl -X POST http://172.30.233.168:10010/api/message/send \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "toUserId": "1002",
    "content": "Hello from HTTP",
    "messageType": "TEXT"
  }'
```

**2. 拉取离线消息**

```bash
curl -X POST http://172.30.233.168:10010/api/message/offline/pull \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "lastMessageId": 0,
    "limit": 50
  }'
```

**3. 标记消息已读**

```bash
curl -X POST http://172.30.233.168:10010/api/message/read \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "messageIds": [1, 2, 3]
  }'
```

### 2.3 Postman 测试集合

创建 Postman Collection，包含以下测试用例：

1. **认证模块**
   - 用户注册
   - 用户登录
   - Token 刷新
   - 用户登出

2. **消息模块**
   - 发送文本消息
   - 发送图片消息
   - 拉取离线消息
   - 标记已读
   - 查询未读数

3. **联系人模块**
   - 添加好友
   - 删除好友
   - 查询好友列表

4. **朋友圈模块**
   - 发布动态
   - 查看动态
   - 点赞评论

### 2.4 集成测试检查清单

- [ ] 用户注册成功，数据库有记录
- [ ] 用户登录成功，返回有效 Token
- [ ] Token 鉴权正常，无效 Token 被拦截
- [ ] WebSocket 连接建立成功
- [ ] 在线用户能实时收到消息
- [ ] 离线用户消息正确存储到离线表
- [ ] 离线消息拉取正常
- [ ] 消息已读状态更新正确
- [ ] Redis 缓存命中率正常
- [ ] RocketMQ 消息消费正常，无积压
- [ ] Nacos 服务注册正常，心跳健康

---

## 3. 压力测试

### 3.1 JMeter 压测方案

#### 3.1.1 安装 JMeter

```bash
# 下载 JMeter
wget https://dlcdn.apache.org//jmeter/binaries/apache-jmeter-5.6.3.zip
unzip apache-jmeter-5.6.3.zip
cd apache-jmeter-5.6.3/bin
./jmeter
```

#### 3.1.2 压测场景设计

**场景1: 登录接口压测**

- 并发用户数: 100 / 500 / 1000
- 持续时间: 5 分钟
- 目标 TPS: 1000+
- 成功率: > 99%
- 响应时间: P95 < 200ms

**场景2: 消息发送压测**

- 并发用户数: 500
- 持续时间: 10 分钟
- 目标 TPS: 5000+
- 成功率: > 99.9%
- 响应时间: P95 < 100ms

**场景3: WebSocket 长连接压测**

- 并发连接数: 10000
- 消息频率: 每秒 1 条
- 持续时间: 30 分钟
- 连接稳定性: 断线率 < 0.1%

#### 3.1.3 JMeter 测试计划配置

创建 `InfiniteChat_LoadTest.jmx`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="InfiniteChat 压测">
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments">
        <collectionProp name="Arguments.arguments">
          <elementProp name="BASE_URL" elementType="Argument">
            <stringProp name="Argument.name">BASE_URL</stringProp>
            <stringProp name="Argument.value">http://172.30.233.168:10010</stringProp>
          </elementProp>
        </collectionProp>
      </elementProp>
    </TestPlan>
    
    <!-- 线程组配置 -->
    <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="用户登录压测">
      <stringProp name="ThreadGroup.num_threads">1000</stringProp>
      <stringProp name="ThreadGroup.ramp_time">60</stringProp>
      <stringProp name="ThreadGroup.duration">300</stringProp>
    </ThreadGroup>
  </hashTree>
</jmeterTestPlan>
```

### 3.2 性能监控

#### 3.2.1 JVM 监控

使用 JVisualVM 或 Arthas 监控：

```bash
# 使用 Arthas
curl -O https://arthas.aliyun.com/arthas-boot.jar
java -jar arthas-boot.jar

# 监控 JVM 指标
dashboard
thread
jvm
```

#### 3.2.2 系统资源监控

```bash
# CPU 使用率
top -p $(pgrep -f "AuthenticationService")

# 内存使用
free -h

# 网络连接数
netstat -an | grep ESTABLISHED | wc -l

# Redis 监控
redis-cli -h 172.30.233.168 -p 59000 -a e65K4t8w2 info stats

# MySQL 慢查询
mysql -h 172.30.233.168 -P 49152 -u root -p'gK3T9n%q2M@j7Z4' \
  -e "SELECT * FROM mysql.slow_log ORDER BY start_time DESC LIMIT 10"
```

### 3.3 压测结果分析

关注以下指标：

1. **吞吐量 (TPS/QPS)**
   - 目标: 登录 > 1000 TPS，消息发送 > 5000 TPS

2. **响应时间**
   - P50 < 50ms
   - P95 < 200ms
   - P99 < 500ms

3. **错误率**
   - < 0.1%

4. **系统资源**
   - CPU < 80%
   - 内存 < 80%
   - 网络带宽充足

5. **中间件状态**
   - Redis 连接池无耗尽
   - MySQL 连接池无耗尽
   - RocketMQ 无消息积压

---

## 4. 部署流程

### 4.1 本地部署（开发环境）

#### 4.1.1 编译打包

```bash
# 在项目根目录执行
mvn clean package -DskipTests

# 检查打包结果
ls -lh */target/*.jar
```

#### 4.1.2 启动服务

```bash
# 启动 Gateway
java -jar Gateway/target/Gateway-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev &

# 启动 AuthenticationService
java -jar AuthenticationService/target/AuthenticationService-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev &

# 启动 RealTimeCommunicationService
java -jar RealTimeCommunicationService/target/RealTimeCommunicationService-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev &

# 启动 MessagingService
java -jar Messaging/target/Messaging-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev &
```

### 4.2 Docker 部署

#### 4.2.1 创建 Dockerfile

为每个服务创建 Dockerfile（以 Gateway 为例）：

```dockerfile
FROM openjdk:8-jre-alpine

WORKDIR /app

COPY target/Gateway-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 10010

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

#### 4.2.2 构建镜像

```bash
# 构建各服务镜像
docker build -t infinitechat/gateway:latest ./Gateway
docker build -t infinitechat/auth:latest ./AuthenticationService
docker build -t infinitechat/rtc:latest ./RealTimeCommunicationService
docker build -t infinitechat/messaging:latest ./Messaging
```

#### 4.2.3 创建 docker-compose.yml

```yaml
version: '3.8'

services:
  nacos:
    image: nacos/nacos-server:v2.2.3
    ports:
      - "18375:8848"
    environment:
      - MODE=standalone
      - SPRING_DATASOURCE_PLATFORM=mysql
    volumes:
      - ./nacos/logs:/home/nacos/logs

  redis:
    image: redis:7-alpine
    ports:
      - "59000:6379"
    command: redis-server --requirepass e65K4t8w2
    volumes:
      - ./redis/data:/data

  mysql:
    image: mysql:8.0
    ports:
      - "49152:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=gK3T9n%q2M@j7Z4
      - MYSQL_DATABASE=InfiniteChat
    volumes:
      - ./mysql/data:/var/lib/mysql
      - ./mysql/init:/docker-entrypoint-initdb.d

  rocketmq-namesrv:
    image: apache/rocketmq:4.9.4
    ports:
      - "9876:9876"
    command: sh mqnamesrv

  rocketmq-broker:
    image: apache/rocketmq:4.9.4
    ports:
      - "10911:10911"
      - "10909:10909"
    depends_on:
      - rocketmq-namesrv
    environment:
      - NAMESRV_ADDR=rocketmq-namesrv:9876
    command: sh mqbroker -n rocketmq-namesrv:9876

  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      - MINIO_ROOT_USER=minioadmin
      - MINIO_ROOT_PASSWORD=minioadmin
    command: server /data --console-address ":9001"
    volumes:
      - ./minio/data:/data

  gateway:
    image: infinitechat/gateway:latest
    ports:
      - "10010:10010"
    depends_on:
      - nacos
      - redis
    environment:
      - SPRING_PROFILES_ACTIVE=prod

  auth-service:
    image: infinitechat/auth:latest
    ports:
      - "8082:8082"
    depends_on:
      - nacos
      - redis
      - mysql
    environment:
      - SPRING_PROFILES_ACTIVE=prod

  rtc-service:
    image: infinitechat/rtc:latest
    ports:
      - "8083:8083"
      - "9101:9101"
    depends_on:
      - nacos
      - redis
      - rocketmq-broker
    environment:
      - SPRING_PROFILES_ACTIVE=prod

  messaging-service:
    image: infinitechat/messaging:latest
    ports:
      - "8084:8084"
    depends_on:
      - nacos
      - redis
      - mysql
      - rocketmq-broker
    environment:
      - SPRING_PROFILES_ACTIVE=prod

networks:
  default:
    name: infinitechat-network
```

#### 4.2.4 启动容器

```bash
# 启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f gateway
```

### 4.3 生产环境部署

#### 4.3.1 服务器配置建议

**最小配置（支持 1000 并发）**:
- CPU: 4 核
- 内存: 8GB
- 磁盘: 100GB SSD
- 网络: 10Mbps

**推荐配置（支持 10000 并发）**:
- CPU: 16 核
- 内存: 32GB
- 磁盘: 500GB SSD
- 网络: 100Mbps

#### 4.3.2 JVM 参数优化

```bash
JAVA_OPTS="
  -Xms2g -Xmx2g
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/infinitechat/heapdump.hprof
  -Dserver.port=8082
  -Dspring.profiles.active=prod
"

java $JAVA_OPTS -jar AuthenticationService.jar
```

#### 4.3.3 Nginx 反向代理配置

```nginx
upstream infinitechat_gateway {
    server 172.30.233.168:10010 weight=1 max_fails=3 fail_timeout=30s;
}

upstream infinitechat_ws {
    server 172.30.233.168:9101 weight=1 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    server_name infinitechat.example.com;

    location /api/ {
        proxy_pass http://infinitechat_gateway;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    location /api/v1/chat/message {
        proxy_pass http://infinitechat_ws;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_connect_timeout 7d;
        proxy_send_timeout 7d;
        proxy_read_timeout 7d;
    }
}
```

#### 4.3.4 数据库初始化脚本

确保执行以下 SQL 脚本：

```sql
-- 创建数据库
CREATE DATABASE IF NOT EXISTS InfiniteChat 
  DEFAULT CHARACTER SET utf8mb4 
  COLLATE utf8mb4_unicode_ci;

USE InfiniteChat;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `username` VARCHAR(50) UNIQUE NOT NULL,
  `password` VARCHAR(255) NOT NULL,
  `email` VARCHAR(100),
  `phone` VARCHAR(20),
  `avatar` VARCHAR(255),
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_username (`username`),
  INDEX idx_phone (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消息表
CREATE TABLE IF NOT EXISTS `im_message` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `from_user_id` BIGINT NOT NULL,
  `to_user_id` BIGINT NOT NULL,
  `content` TEXT,
  `message_type` VARCHAR(20),
  `status` TINYINT DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_from_user (`from_user_id`),
  INDEX idx_to_user (`to_user_id`),
  INDEX idx_create_time (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 离线消息表
CREATE TABLE IF NOT EXISTS `offline_message` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `message_id` BIGINT NOT NULL,
  `status` TINYINT DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_status (`user_id`, `status`),
  INDEX idx_message (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 未读数表
CREATE TABLE IF NOT EXISTS `unread_count` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL UNIQUE,
  `count` INT DEFAULT 0,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.4 部署检查清单

部署完成后，执行以下检查：

- [ ] 所有服务在 Nacos 注册成功
- [ ] 服务健康检查通过
- [ ] 数据库连接正常
- [ ] Redis 连接正常
- [ ] RocketMQ 连接正常
- [ ] MinIO 连接正常
- [ ] 日志输出正常，无 ERROR
- [ ] 接口测试通过
- [ ] WebSocket 连接正常
- [ ] 监控告警配置完成

### 4.5 运维监控

#### 4.5.1 日志管理

```bash
# 配置 logback-spring.xml
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>/var/log/infinitechat/application.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>/var/log/infinitechat/application.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
  </appender>
</configuration>
```

#### 4.5.2 告警配置

配置 Prometheus + Grafana 监控：

- CPU 使用率 > 80% 告警
- 内存使用率 > 80% 告警
- 接口错误率 > 1% 告警
- 接口响应时间 P95 > 500ms 告警
- 服务下线告警

---

## 5. 常见问题排查

### 5.1 服务启动失败

**问题**: 服务无法启动

**排查步骤**:
1. 检查端口是否被占用: `netstat -ano | findstr 8082`
2. 检查 Nacos 是否启动
3. 检查配置文件是否正确
4. 查看启动日志

### 5.2 服务注册失败

**问题**: 服务无法注册到 Nacos

**排查步骤**:
1. 检查 Nacos 地址配置
2. 检查网络连通性
3. 检查 Nacos 用户名密码
4. 查看 Nacos 日志

### 5.3 消息发送失败

**问题**: 消息无法发送

**排查步骤**:
1. 检查 Token 是否有效
2. 检查接收方用户是否存在
3. 检查 RocketMQ 是否正常
4. 查看 Messaging 服务日志

### 5.4 WebSocket 连接断开

**问题**: WebSocket 频繁断开

**排查步骤**:
1. 检查网络稳定性
2. 检查 Nginx 超时配置
3. 检查心跳机制
4. 查看 Netty 服务日志

---

## 6. 总结

完整的测试和部署流程包括：

1. **环境准备**: 确保所有中间件正常运行
2. **接口测试**: 验证核心功能正常
3. **压力测试**: 确保系统性能达标
4. **部署上线**: 选择合适的部署方式
5. **监控运维**: 持续监控系统状态

建议按照本指南逐步执行，遇到问题及时排查日志。
