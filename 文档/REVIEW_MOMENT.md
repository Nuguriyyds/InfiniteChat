# InfiniteChat — Moment 模块复盘

## 一、模块定位

Moment 是 IM 系统的**朋友圈微服务**，提供类似微信朋友圈的社交动态能力——发布动态（文字 + 图片）、点赞、评论、好友动态流。它是 IM 核心通信能力之外的社交扩展模块，通过 Feign 调用 RTC 实现实时通知推送。

```
┌──────────────────────────────────────────────────────────────┐
│                  MomentService (8085)                         │
│                                                              │
│  ┌───────────────┐  ┌─────────────┐  ┌──────────────────┐   │
│  │ 动态发布      │  │ 点赞系统    │  │ 评论系统         │   │
│  │ 文字+图片     │  │ Redis+MySQL │  │ MySQL            │   │
│  │ 雪花ID       │  │ 高并发去重  │  │ 支持回复         │   │
│  └───────────────┘  └─────────────┘  └──────────────────┘   │
│                                                              │
│  ┌───────────────┐  ┌──────────────────────────────────┐    │
│  │ 好友动态流    │  │ 通知推送                          │    │
│  │ JOIN好友表    │  │ Feign → RTC → WebSocket          │    │
│  │ 分页查询      │  │ 点赞/评论实时通知                 │    │
│  └───────────────┘  └──────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

---

## 二、整体架构

### 2.1 分层结构

```
┌──────────────────────────────────────────────────────────────────┐
│                    MomentController (REST API)                    │
│  publishMoment · likeMoment · unlikeMoment                       │
│  commentMoment · getFriendMoments · getMomentDetail              │
└─────────────────────────────┬────────────────────────────────────┘
                              │
┌─────────────────────────────▼────────────────────────────────────┐
│                MomentServiceImpl (核心业务)                        │
│  发布 · 点赞/取消 · 评论 · 好友动态流 · 详情                      │
├─────────────────────────────┬────────────────────────────────────┤
│         MomentNotifyServiceImpl (通知推送)                        │
│  pushLikeNotification · pushCommentNotification                  │
└──────┬───────────┬──────────┬───────────┬────────────────────────┘
       │           │          │           │
┌──────▼──────┐ ┌──▼────────┐ ┌▼─────────┐ ┌▼─────────────────┐
│MomentMapper │ │MomentLike │ │MomentCmt │ │ RtcPushClient   │
│  动态主表   │ │Mapper     │ │Mapper    │ │ Feign → RTC     │
│             │ │点赞表     │ │评论表    │ │ 推送通知        │
└─────────────┘ └───────────┘ └──────────┘ └─────────────────┘
                                   │
                              ┌────▼─────┐
                              │UserMapper│
                              │用户信息  │
                              └──────────┘
```

### 2.2 数据流概览

```
发布动态:  Controller → Service → MomentMapper → MySQL
点赞:      Controller → Service → Redis Set/Counter → MySQL → Feign(RTC)
评论:      Controller → Service → MySQL → Feign(RTC)
好友动态流: Controller → Service → MomentMapper(JOIN im_friend) → 补全用户信息 → Redis查点赞状态
```

---

## 三、中间件一览

```
┌──────────────────────────────────────────────────────────────────┐
│                            Redis                                  │
│                                                                   │
│  点赞用户集合 (去重):                                             │
│    moment:like:{momentId}         ← Set (存放 userId)  TTL 24h   │
│                                                                   │
│  点赞计数器:                                                      │
│    moment:like:count:{momentId}   ← String (数字)      TTL 24h   │
│                                                                   │
│  作用:                                                            │
│    - Set 保证同一用户不能重复点赞 (SADD 幂等)                     │
│    - 计数器避免每次 COUNT(*) 查 MySQL                             │
│    - 24 小时后过期, 降级为 MySQL like_count 字段                  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                           MySQL                                   │
│                                                                   │
│  moment 表 (动态主表):                                            │
│    moment_id (雪花ID), user_id, content(≤1000字),                │
│    images (JSON数组, ≤9张), like_count, comment_count,           │
│    status (0=正常, 1=删除), created_at, updated_at               │
│                                                                   │
│  moment_like 表 (点赞表):                                         │
│    like_id (雪花ID), moment_id, user_id,                         │
│    created_at                                                     │
│    唯一索引: uk_moment_user (moment_id, user_id)                 │
│                                                                   │
│  moment_comment 表 (评论表):                                      │
│    comment_id (雪花ID), moment_id, user_id,                      │
│    content(≤500字), reply_to_user_id (回复目标),                 │
│    created_at                                                     │
│                                                                   │
│  依赖的跨模块表:                                                  │
│    user 表     → 查昵称、头像                                     │
│    im_friend 表 → 好友关系, 用于权限过滤                         │
└──────────────────────────────────────────────────────────────────┘

┌────────────────────┐  ┌─────────────────┐
│ Nacos              │  │  Feign → RTC    │
│ 服务注册发现       │  │  推送通知       │
└────────────────────┘  └─────────────────┘
```

---

## 四、API 接口总览

| 接口 | 方法 | 功能 |
|------|------|------|
| `/api/moment/publish` | POST | 发布朋友圈动态 |
| `/api/moment/like` | POST | 点赞 |
| `/api/moment/unlike` | POST | 取消点赞 |
| `/api/moment/comment` | POST | 评论/回复 |
| `/api/moment/friends` | GET | 好友朋友圈列表（分页） |
| `/api/moment/detail/{momentId}` | GET | 动态详情 |

---

## 五、核心流程详解

### 5.1 发布朋友圈

```
用户
  │  POST /api/moment/publish
  │  { content: "今天天气真好", images: ["url1", "url2"] }
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ MomentServiceImpl.publishMoment()                                │
│                                                                  │
│ 1. 校验:                                                         │
│    - content 和 images 至少有一个非空                             │
│    - content.length() ≤ 1000                                     │
│    - images.size() ≤ 9                                           │
│                                                                  │
│ 2. 生成雪花 ID: momentId = IdGenerator.nextId()                  │
│                                                                  │
│ 3. INSERT moment:                                                │
│    (momentId, userId, content, images=JSON序列化,                │
│     likeCount=0, commentCount=0, status=0)                       │
│                                                                  │
│ 4. 返回 momentId                                                 │
└──────────────────────────────────────────────────────────────────┘
```

### 5.2 点赞（高并发核心）

```
用户A
  │  POST /api/moment/like
  │  { momentId: 123, userId: A }
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. Redis Set 去重                                                │
│                                                                  │
│    SADD moment:like:123 A                                        │
│    → 返回 0 (已存在) → 抛异常 "已点赞"                           │
│    → 返回 1 (新增成功) → 继续                                    │
│                                                                  │
│    EXPIRE moment:like:123 24h                                    │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. Redis 计数器 +1                                               │
│                                                                  │
│    INCR moment:like:count:123                                    │
│    EXPIRE moment:like:count:123 24h                              │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 3. MySQL 持久化                                                  │
│                                                                  │
│    INSERT moment_like (likeId=雪花ID, momentId=123, userId=A)   │
│    UPDATE moment SET like_count = like_count + 1                 │
│    WHERE moment_id = 123                                         │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 4. 推送通知 (非自己的动态)                                       │
│                                                                  │
│    查 moment.userId → 如果 ≠ A:                                  │
│    Feign → RtcPushClient.pushMomentNotification(动态作者)        │
│    payload: { type: "like", momentId, fromUserId, nickname }     │
└──────────────────────────────────────────────────────────────────┘
```

面试要点：
- **先 Redis 后 MySQL 的写入顺序**：Redis Set 的 `SADD` 天然幂等，用它做第一道去重。即使 MySQL 插入成功但 Redis 失败（极端场景），唯一索引 `uk_moment_user` 也能兜底。
- **为什么不直接用 MySQL 唯一索引做去重？** 可以，但每次点赞都走 MySQL 写入，在高并发场景（热门动态几千人同时点赞）会成为瓶颈。Redis 在前面挡一层，绝大多数重复点赞请求在内存中就被拒绝了，不会打到数据库。
- **Redis 计数器 24 小时过期后怎么办？** 降级为 MySQL 的 `moment.like_count` 字段。查询点赞数时优先取 Redis，miss 则用 MySQL 字段。这个字段通过 `like_count = like_count + 1` 原子更新，保证准确。

### 5.3 取消点赞

```
用户A → POST /api/moment/unlike { momentId: 123, userId: A }
    │
    ▼
SREM moment:like:123 A
  → 返回 0 (不存在) → 抛异常 "未点赞"
  → 返回 1 (删除成功) → 继续
    │
    ▼
DECR moment:like:count:123
    │
    ▼
DELETE FROM moment_like WHERE moment_id=123 AND user_id=A
UPDATE moment SET like_count = like_count - 1 WHERE moment_id=123
```

### 5.4 评论 / 回复

```
用户A
  │  POST /api/moment/comment
  │  { momentId: 123, userId: A, content: "太棒了",
  │    replyToUserId: null }     ← null=直接评论, 非null=回复某人
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. 校验: content 非空且 ≤ 500 字                                │
│                                                                  │
│ 2. INSERT moment_comment:                                        │
│    (commentId=雪花ID, momentId=123, userId=A,                   │
│     content="太棒了", replyToUserId=null)                        │
│                                                                  │
│ 3. UPDATE moment SET comment_count = comment_count + 1          │
│    WHERE moment_id = 123                                         │
│                                                                  │
│ 4. 推送通知 (非自己的动态):                                      │
│    Feign → RTC.pushMomentNotification(动态作者)                  │
│    payload: { type: "comment", momentId, fromUserId,             │
│               nickname, content }                                │
└──────────────────────────────────────────────────────────────────┘
```

### 5.5 好友朋友圈列表（Feed 流）

```
用户A
  │  GET /api/moment/friends?page=1&size=20
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. 查好友动态 (SQL JOIN)                                         │
│                                                                  │
│    SELECT m.* FROM moment m                                      │
│    INNER JOIN im_friend f                                        │
│      ON m.user_id = f.friend_id                                  │
│    WHERE f.user_id = #{userId}                                   │
│      AND f.status = 1                                            │
│      AND m.status = 0                                            │
│    ORDER BY m.created_at DESC                                    │
│    LIMIT #{offset}, #{size}                                      │
│                                                                  │
│    → 拉模型 (Pull): 每次查询实时 JOIN 好友表                     │
│    → 适合好友数不大的场景                                         │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. 批量补全用户信息                                              │
│                                                                  │
│    收集所有 userId → 批量查 user 表                               │
│    → 填充 username, avatar 到 MomentVO                           │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ 3. 每条动态补全交互数据                                          │
│                                                                  │
│    for each moment:                                              │
│      a. 是否已点赞:                                              │
│         Redis SISMEMBER moment:like:{id} {userId}                │
│         → miss → MySQL moment_like.existsLike(momentId, userId)  │
│                                                                  │
│      b. 点赞数:                                                  │
│         Redis GET moment:like:count:{id}                         │
│         → miss → 用 moment.like_count                            │
│                                                                  │
│      c. 前 3 条评论:                                             │
│         MySQL moment_comment                                     │
│         ORDER BY created_at ASC LIMIT 3                          │
│         → 补全评论者 username/avatar                              │
└──────────────────────────────────────────────────────────────────┘
  │
  ▼
返回 List<MomentVO>
```

面试要点：
- **为什么用拉模型（Pull）而不是推模型（Push / Fan-out-on-write）？** 推模型在用户发动态时需要写入所有好友的收件箱，好友数 * 动态数 的写放大严重。拉模型每次查询都是实时的，好友关系变化（删好友、新增好友）立刻生效，不需要处理复杂的收件箱维护。对于好友数在几百以内的场景，JOIN 查询的性能完全可以接受。
- **N+1 查询问题？** 用户信息是批量查询的（一次查出所有 userId），避免了 N+1。但评论目前是逐条动态查询的，如果一页 20 条动态就是 20 次评论查询，可以优化为批量 IN 查询。

### 5.6 动态详情

```
GET /api/moment/detail/{momentId}
    │
    ▼
查 moment 表 → 查 user 表 (作者信息)
→ Redis 查是否点赞 → Redis 查点赞数
→ 查前 10 条评论 (补全评论者信息)
→ 返回 MomentVO
```

---

## 六、点赞系统数据一致性分析

```
┌────────────────────────────────────────────────────────────────┐
│                     写入路径                                   │
│                                                                │
│  Redis Set (SADD)  ──▶  Redis Counter (INCR)  ──▶  MySQL     │
│  (去重)                  (计数)                     (持久化)  │
│                                                                │
│  如果 MySQL 写失败:                                            │
│    - Redis 已经记录了点赞, 但 MySQL 没有                       │
│    - 24h 后 Redis 过期, 用户可以再次点赞                       │
│    - MySQL 唯一索引 uk_moment_user 兜底                        │
│                                                                │
│  如果 Redis 写成功但服务崩溃 (MySQL 未执行):                   │
│    - 同上, Redis 是临时状态, MySQL 是最终一致                  │
│                                                                │
│  结论: Redis 是"加速层", MySQL 是"真相源"                      │
│  最终一致, 非强一致                                             │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                     读取路径                                   │
│                                                                │
│  是否点赞: Redis Set SISMEMBER → miss → MySQL existsLike     │
│  点赞数:   Redis Counter GET → miss → MySQL like_count       │
│                                                                │
│  24h 内: Redis 命中, O(1) 极速                                │
│  24h 后: 降级 MySQL, 数据准确                                  │
└────────────────────────────────────────────────────────────────┘
```

---

## 七、雪花 ID 生成器

```
┌──────────────────────────────────────────────────────────────────┐
│ IdGenerator (Snowflake 变体)                                     │
│                                                                  │
│ 64 bit:                                                          │
│ ┌─────────┬──────────────────┬──────────┬──────────┬──────────┐ │
│ │ 1 bit   │ 41 bit           │ 5 bit    │ 5 bit    │ 12 bit   │ │
│ │ 符号    │ 时间戳           │ datacenter│ worker  │ sequence │ │
│ └─────────┴──────────────────┴──────────┴──────────┴──────────┘ │
│                                                                  │
│ workerId:     基于本机 IP 最后一段 % 32                          │
│ datacenterId: 基于 hostname hashCode % 32                        │
│                                                                  │
│ 时钟回拨防护:                                                    │
│   ≤ 5ms → Thread.sleep 等待                                     │
│   > 5ms → 抛 RuntimeException                                   │
│                                                                  │
│ 同一毫秒内 sequence 用完 (4096) → 自旋等到下一毫秒              │
└──────────────────────────────────────────────────────────────────┘
```

面试要点：雪花 ID 的 workerId 基于 IP 生成，在容器化部署（K8s Pod 每次 IP 不同）中可能出现冲突。更稳健的方案是通过注册中心或 Redis 动态分配 workerId。

---

## 八、完整数据流全景

### 8.1 用户发布动态 → 好友查看 → 点赞 → 通知

```
用户A (发布)          Moment Service               MySQL           Redis
  │                        │                         │               │
  │  POST /publish         │                         │               │
  │───────────────────────▶│                         │               │
  │                        │  INSERT moment          │               │
  │                        │────────────────────────▶│               │
  │◀───────────────────────│  返回 momentId          │               │
  │                        │                         │               │

用户B (查看好友动态)
  │                        │                         │               │
  │  GET /friends          │                         │               │
  │───────────────────────▶│                         │               │
  │                        │  SELECT JOIN im_friend  │               │
  │                        │────────────────────────▶│               │
  │                        │  ◀────────────────────│               │
  │                        │  查点赞状态            │               │
  │                        │──────────────────────────────────────▶│
  │                        │  ◀──────────────────────────────────│
  │◀───────────────────────│                         │               │
  │  [动态列表]            │                         │               │

用户B (点赞)                                                    RTC
  │                        │                         │           │
  │  POST /like            │                         │           │
  │───────────────────────▶│                         │           │
  │                        │  SADD (去重)            │           │
  │                        │──────────────────────────────────▶│
  │                        │  INCR (计数)            │           │
  │                        │──────────────────────────────────▶│
  │                        │  INSERT moment_like     │           │
  │                        │  UPDATE like_count      │           │
  │                        │────────────────────────▶│           │
  │                        │                         │           │
  │                        │  Feign: 推送通知给A     │           │
  │                        │─────────────────────────────────▶│
  │◀───────────────────────│                         │       │  │
  │  "点赞成功"            │                         │       │  │
  │                        │                         │       ▼  │
                                                          用户A 收到
                                                          点赞通知
```

---

## 九、面试高频问题与回答思路

### Q1: 朋友圈动态流用拉模型还是推模型？各自的优缺点？

> 当前实现用的是**拉模型**——每次查询时实时 JOIN `im_friend` 表。优点是实现简单、好友关系变化实时生效、不需要维护收件箱。缺点是每次查询都走 MySQL JOIN，好友数多时查询变慢。
>
> 推模型（Fan-out-on-write）是在发布时写入所有好友的收件箱（如 Redis Sorted Set），查询时直接读收件箱，不需要 JOIN。优点是读快，缺点是写放大严重（1000 好友 = 写 1000 份），且好友关系变化需要处理收件箱同步。
>
> 微信朋友圈据传用的就是拉模型——好友数上限 5000，JOIN 查询完全可控。

### Q2: 点赞为什么要 Redis + MySQL 双写？

> Redis 做高并发去重和计数：`SADD` 是 O(1) 的幂等操作，热门动态几千人同时点赞也不会打到数据库。MySQL 是数据真相源，保证 Redis 过期后数据不丢失。写入顺序是先 Redis 后 MySQL，读取顺序是先 Redis 后 MySQL。最终一致而非强一致，对于点赞这种非金融场景完全够用。

### Q3: 如果 Redis 和 MySQL 的点赞数不一致怎么办？

> MySQL 的 `like_count` 通过 `like_count + 1` 原子更新，是准确的。Redis 计数器是缓存加速层，24 小时后过期就会降级为 MySQL。如果出现不一致（比如 Redis 写成功但 MySQL 失败），影响范围仅是 24 小时内该动态的点赞数可能偏差 1。可以通过定时任务扫描对齐，但通常不值得——代价大收益小。

### Q4: 评论为什么不用 Redis 缓存？

> 点赞是高频、幂等、低信息量的操作（只有"谁点了"），适合 Redis 加速。评论是低频、非幂等、高信息量的操作（有内容、有回复关系），缓存收益不大，且缓存一致性维护成本高。直接查 MySQL 加索引，性能足够。

### Q5: 好友动态流的分页有什么问题？

> 当前使用 `LIMIT offset, size` 分页，在深翻页（如第 100 页）时 MySQL 需要扫描前 `offset + size` 条记录再丢弃，性能下降。可以改为**游标分页**——`WHERE created_at < lastTime ORDER BY created_at DESC LIMIT size`，每次从上一页最后一条的时间开始查，避免深翻页的性能问题。

### Q6: 雪花 ID 在容器化部署中有什么隐患？

> 当前 `workerId` 基于 `IP % 32` 生成。K8s 环境下 Pod 重建后 IP 变化，可能出现两个 Pod 的 workerId 相同，导致 ID 冲突。更稳健的方案是通过 Redis `INCR` 或 Nacos 元数据动态分配 workerId，保证全局唯一。

---

## 十、技术栈速查

| 技术 | 用途 |
|------|------|
| Spring Boot 2.6.13 | 基础框架 |
| MyBatis-Plus 3.5.2 | ORM + 分页插件 |
| Redis (Set + String) | 点赞去重、点赞计数 |
| MySQL | 动态、点赞、评论持久化；好友关系 JOIN |
| OpenFeign + CircuitBreaker | 调用 RTC 推送通知 |
| Nacos | 服务注册发现 |
| Snowflake ID | 分布式唯一 ID 生成 |
| RocketMQ | 已引入但当前未使用（通知走 Feign 直推） |
