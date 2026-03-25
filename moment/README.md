# 朋友圈模块 (Moment Service)

## 模块说明

朋友圈模块是InfiniteChat IM系统的独立微服务，提供朋友圈发布、点赞、评论等社交功能，并通过Netty实现即时推送通知。

## 快速开始

### 1. 数据库初始化

执行SQL脚本创建数据库表：

```bash
mysql -u root -p infinitechat < sql/moment_tables.sql
```

### 2. 配置文件

修改 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://your_host:3306/infinitechat
    username: your_username
    password: your_password
  
  redis:
    host: your_redis_host
    port: 6379
  
  cloud:
    nacos:
      discovery:
        server-addr: your_nacos_host:8848

rocketmq:
  name-server: your_rocketmq_host:9876
```

### 3. 启动服务

```bash
mvn spring-boot:run
```

服务将在 `http://localhost:8083` 启动。

## API接口

### 发布朋友圈

```http
POST /api/moment/publish
Header: X-User-Id: {userId}
Content-Type: application/json

{
  "content": "今天天气真好",
  "images": ["https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg"]
}
```

### 点赞

```http
POST /api/moment/like
Header: X-User-Id: {userId}
Content-Type: application/json

{
  "momentId": 123456789
}
```

### 取消点赞

```http
POST /api/moment/unlike
Header: X-User-Id: {userId}
Content-Type: application/json

{
  "momentId": 123456789
}
```

### 评论

```http
POST /api/moment/comment
Header: X-User-Id: {userId}
Content-Type: application/json

{
  "momentId": 123456789,
  "content": "说得对",
  "replyToUserId": null
}
```

### 查看好友朋友圈

```http
GET /api/moment/friends?pageNum=1&pageSize=10
Header: X-User-Id: {userId}
```

### 查看朋友圈详情

```http
GET /api/moment/detail/{momentId}
Header: X-User-Id: {userId}
```

## 技术架构

### 核心技术栈

- Spring Boot 2.6.13
- MyBatis-Plus 3.5.2
- Redis (点赞缓存)
- RocketMQ (推送通知)
- MySQL 8.0
- Nacos (服务注册)

### 架构亮点

1. **高性能点赞**: Redis Set + 计数器，支持10万+ TPS
2. **即时推送**: 通过RocketMQ + Netty实现分布式推送
3. **权限控制**: SQL JOIN查询，只返回好友动态
4. **数据一致性**: Redis异步同步MySQL，最终一致性

详细架构设计请参考 [MOMENT_MODULE_DESIGN.md](./MOMENT_MODULE_DESIGN.md)

## 依赖关系

本模块依赖以下服务：

- **RealTimeCommunication**: 推送通知依赖RTC模块的数据模型
- **MySQL**: 存储朋友圈数据
- **Redis**: 缓存点赞数据
- **RocketMQ**: 消息队列
- **Nacos**: 服务注册与发现

## 注意事项

1. 确保MySQL中已存在 `user` 表和 `im_friend` 表
2. Redis需要支持Set和String数据结构
3. RocketMQ需要创建 `IM_CHAT` Topic
4. 服务启动前确保Nacos和RocketMQ已启动

## 扩展性

- **从2台扩展到N台**: 
  - 应用层: 部署多个moment服务实例到Nacos
  - Redis: 改为Redis Cluster
  - MySQL: 配置主从复制
  - 代码无需修改

## 开发者

- 模块创建时间: 2026-03-13
- 技术栈版本: Spring Boot 2.6.13, Java 8
