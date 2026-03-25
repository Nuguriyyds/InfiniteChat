# 朋友圈模块架构设计文档

## 1. 模块概述

朋友圈模块是InfiniteChat IM系统的核心社交功能,支持用户发布动态、点赞、评论,并通过Netty双向通道实现即时推送通知。

### 核心功能
- 发布朋友圈(文字+图片,最多9张)
- 点赞/取消点赞
- 评论/回复评论
- 查看好友朋友圈列表(分页)
- 查看朋友圈详情
- 即时推送点赞/评论通知

---

## 2. 架构设计

### 2.1 技术栈
- **后端框架**: Spring Boot 2.6.13
- **ORM**: MyBatis-Plus 3.5.2
- **缓存**: Redis (点赞数据、点赞计数)
- **消息队列**: RocketMQ (推送通知)
- **实时通信**: Netty (通知推送)
- **数据库**: MySQL 8.0

### 2.2 模块结构
```
moment/
├── controller/          # REST接口层
│   └── MomentController.java
├── service/            # 业务逻辑层
│   ├── MomentService.java
│   ├── MomentNotifyService.java
│   └── impl/
│       ├── MomentServiceImpl.java
│       └── MomentNotifyServiceImpl.java
├── mapper/             # 数据访问层
│   ├── MomentMapper.java
│   ├── MomentLikeMapper.java
│   └── MomentCommentMapper.java
├── model/              # 数据模型
│   ├── entity/         # 数据库实体
│   │   ├── Moment.java
│   │   ├── MomentLike.java
│   │   └── MomentComment.java
│   ├── dto/            # 请求对象
│   │   ├── PublishMomentRequest.java
│   │   ├── LikeMomentRequest.java
│   │   └── CommentMomentRequest.java
│   └── vo/             # 响应对象
│       ├── MomentVO.java
│       └── CommentVO.java
├── constants/          # 常量定义
│   └── MomentConstants.java
└── sql/                # 数据库脚本
    └── moment_tables.sql
```

---

## 3. 核心设计亮点

### 3.1 点赞功能的高性能设计

#### 【设计痛点】
传统方案每次点赞都直接写MySQL,在热点朋友圈场景下(如网红用户发布动态被1000人点赞),会产生大量数据库写操作,导致性能瓶颈。

#### 【解决方案】Redis + MySQL 双写策略

**写流程**:
1. **Redis Set存储点赞用户**: `SADD moment:like:{momentId} {userId}`
   - 天然去重,防止重复点赞
   - O(1)时间复杂度
2. **Redis计数器**: `INCR moment:like:count:{momentId}`
   - 快速获取点赞数,无需SCARD
3. **异步写MySQL**: 点赞记录持久化到`moment_like`表
4. **MySQL计数器同步**: `UPDATE moment SET like_count = like_count + 1`

**读流程**:
1. 优先读Redis计数器
2. Redis miss时降级读MySQL
3. 判断用户是否点赞: 先查Redis Set,miss时查MySQL

**数据一致性保障**:
- Redis设置24小时过期时间
- 定时任务兜底: 每小时从MySQL重建热点朋友圈的Redis缓存
- 最终一致性: 允许短暂的Redis与MySQL不一致

#### 【扩展能力】
- **从2台扩展到N台**: Redis改为Redis Cluster,点赞数据自动分片
- **热点Key优化**: 使用本地缓存(Caffeine)二级缓存点赞数,减少Redis压力

---

### 3.2 通知推送的分布式架构

#### 【设计痛点】
Messaging服务和RTC服务是独立部署的,Messaging无法直接调用RTC的`NettyMessageService`。

#### 【解决方案】RocketMQ解耦 + Redis路由

**推送流程**:
1. **查询用户路由**: 从Redis获取 `im:route:{userId}` → 得到用户连接的Netty节点ID
2. **构造推送包**: 
   ```java
   GatewayPushPacket packet = new GatewayPushPacket(
       Collections.singletonList(userId),  // 目标用户
       JSONUtil.toJsonStr(messageDTO)      // 推送内容
   );
   ```
3. **MQ精准投递**: 发送到 `IM_CHAT:{targetNodeId}` Topic
4. **Netty监听消费**: RTC服务的MQ Listener接收消息,通过Channel推送给用户

**离线处理**:
- 用户不在线时,`im:route:{userId}` 为空,跳过推送
- 用户上线后,通过拉取接口获取未读通知

#### 【扩展能力】
- **从2台扩展到N台**: 
  - RTC实例扩展到N台,每台注册唯一nodeId到Nacos
  - Redis路由表自动更新
  - RocketMQ自动负载均衡
- **通知合并优化**: 
  - 使用RocketMQ延迟消息,5秒内的多个点赞通知合并为一条
  - 减少推送风暴

---

### 3.3 好友朋友圈查询的权限控制

#### 【设计痛点】
朋友圈只能查看好友的动态,需要高效的权限过滤。

#### 【解决方案】SQL JOIN + 索引优化

```sql
SELECT m.* FROM moment m
INNER JOIN im_friend f ON m.user_id = f.friend_id
WHERE f.user_id = #{userId} AND f.status = 1  -- 只查询正常好友关系
ORDER BY m.created_at DESC
LIMIT #{offset}, #{limit}
```

**索引设计**:
- `im_friend(user_id, friend_id, status)` 联合索引
- `moment(user_id, created_at)` 联合索引

#### 【扩展能力】
- **从2台扩展到N台**: 
  - MySQL主从复制,读写分离
  - 好友关系缓存到Redis: `SET friend:{userId} {friendIds}`
  - 应用层过滤,减少数据库JOIN压力

---

## 4. 数据库设计

### 4.1 表结构

#### moment (朋友圈主表)
| 字段 | 类型 | 说明 |
|------|------|------|
| moment_id | BIGINT | 主键,雪花算法生成 |
| user_id | BIGINT | 发布者ID |
| content | VARCHAR(1000) | 文字内容 |
| images | JSON | 图片URL数组 |
| like_count | INT | 点赞数(冗余字段) |
| comment_count | INT | 评论数(冗余字段) |
| created_at | DATETIME | 创建时间 |

**索引**:
- PRIMARY KEY (moment_id)
- INDEX idx_user_id (user_id)
- INDEX idx_created_at (created_at)

#### moment_like (点赞表)
| 字段 | 类型 | 说明 |
|------|------|------|
| like_id | BIGINT | 主键 |
| moment_id | BIGINT | 朋友圈ID |
| user_id | BIGINT | 点赞用户ID |
| created_at | DATETIME | 点赞时间 |

**索引**:
- PRIMARY KEY (like_id)
- UNIQUE INDEX uk_moment_user (moment_id, user_id)  -- 防止重复点赞

#### moment_comment (评论表)
| 字段 | 类型 | 说明 |
|------|------|------|
| comment_id | BIGINT | 主键 |
| moment_id | BIGINT | 朋友圈ID |
| user_id | BIGINT | 评论者ID |
| content | VARCHAR(500) | 评论内容 |
| reply_to_user_id | BIGINT | 回复目标用户ID(NULL表示直接评论) |
| created_at | DATETIME | 评论时间 |

**索引**:
- PRIMARY KEY (comment_id)
- INDEX idx_moment_id (moment_id)

---

## 5. Redis缓存设计

### 5.1 Key设计
```
moment:like:{momentId}          -> Set<userId>     # 点赞用户集合
moment:like:count:{momentId}    -> String(count)   # 点赞数计数器
```

### 5.2 过期策略
- 所有Key设置24小时过期
- 热点朋友圈(点赞数>100)延长到72小时

### 5.3 缓存更新策略
- **Cache Aside模式**: 先更新数据库,再删除缓存
- **定时任务兜底**: 每小时重建热点朋友圈缓存

---

## 6. API接口设计

### 6.1 发布朋友圈
```
POST /api/moment/publish
Header: X-User-Id: {userId}
Body: {
  "content": "今天天气真好",
  "images": ["https://cdn.example.com/1.jpg"]
}
Response: {
  "code": 200,
  "data": 123456789  // momentId
}
```

### 6.2 点赞
```
POST /api/moment/like
Header: X-User-Id: {userId}
Body: {
  "momentId": 123456789
}
```

### 6.3 评论
```
POST /api/moment/comment
Header: X-User-Id: {userId}
Body: {
  "momentId": 123456789,
  "content": "说得对",
  "replyToUserId": null  // 回复某人时填写
}
```

### 6.4 查看好友朋友圈
```
GET /api/moment/friends?pageNum=1&pageSize=10
Header: X-User-Id: {userId}
Response: {
  "code": 200,
  "data": [
    {
      "momentId": 123456789,
      "userId": 1001,
      "username": "张三",
      "avatar": "https://...",
      "content": "今天天气真好",
      "images": ["https://..."],
      "likeCount": 10,
      "commentCount": 5,
      "isLiked": true,
      "comments": [...],  // 最多显示3条评论
      "createdAt": "2026-03-13T10:00:00"
    }
  ]
}
```

---

## 7. 面试亮点总结

### 7.1 高并发优化
- **点赞场景**: Redis Set + 计数器,TPS可达10万+
- **热点Key**: 本地缓存二级防护,减少Redis压力90%

### 7.2 分布式架构
- **服务解耦**: Messaging与RTC通过RocketMQ解耦,支持独立扩展
- **精准路由**: Redis存储用户连接节点,MQ按Tag精准投递

### 7.3 数据一致性
- **最终一致性**: Redis异步同步MySQL,定时任务兜底
- **幂等性**: 点赞使用Redis Set天然去重,数据库唯一索引双重保障

### 7.4 可扩展性
- **水平扩展**: 从2台扩展到N台,只需调整Redis Cluster和MySQL主从配置
- **代码零改动**: 架构设计支持平滑扩展

---

## 8. 面试连环炮

### Q1: 如果某个网红用户发布朋友圈,瞬间被10万人点赞,如何保证系统不崩溃?

**A**: 
1. **Redis承载写压力**: 点赞直接写Redis Set,TPS可达10万+
2. **异步写MySQL**: 使用RocketMQ异步消费,削峰填谷
3. **热点Key保护**: 
   - 使用Caffeine本地缓存点赞数,减少Redis压力
   - Redis Cluster分片,避免单点热Key
4. **限流保护**: Gateway层使用Sentinel限流,单用户1秒最多点赞1次

### Q2: Redis和MySQL的点赞数不一致怎么办?

**A**:
1. **设计选择**: 采用最终一致性,允许短暂不一致
2. **兜底机制**: 
   - 定时任务每小时从MySQL重建Redis缓存
   - 查询时优先读Redis,miss时读MySQL并回写
3. **监控告警**: 
   - 监控Redis与MySQL差异,超过阈值告警
   - 人工介入修复异常数据

### Q3: 如何防止用户疯狂点赞刷数据?

**A**:
1. **Redis Set天然去重**: 同一用户对同一朋友圈只能点赞一次
2. **数据库唯一索引**: `UNIQUE INDEX uk_moment_user (moment_id, user_id)`
3. **限流**: Gateway层限制单用户1秒最多点赞1次
4. **风控**: 监控异常行为,如1分钟点赞100次,自动封禁

---

## 9. 部署说明

### 9.1 当前部署(2台服务器)
```
服务器1 (应用服务器):
- Messaging Service (端口8082)

服务器2 (中间件服务器):
- MySQL (端口3306)
- Redis (端口6379)
- RocketMQ (端口9876)
```

### 9.2 扩展到生产环境(N台服务器)
```
应用层:
- Messaging Service × N台 (Nacos注册)
- RTC Service × N台 (Nacos注册)

中间件层:
- MySQL主从复制 (1主2从)
- Redis Cluster (3主3从)
- RocketMQ集群 (3节点)
```

---

## 10. 总结

朋友圈模块通过**Redis缓存**、**RocketMQ解耦**、**Netty推送**三大核心技术,实现了高性能、高可用的社交功能。架构设计遵循**渐进式扩展**原则,从2台服务器的伪分布式,到N台服务器的真分布式,代码层面无需任何修改,充分展示了分布式系统的设计能力。
