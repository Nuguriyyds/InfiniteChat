package com.wangyutao.messaging.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.exception.ServiceException;
import com.wangyutao.messaging.mapper.FriendMapper;
import com.wangyutao.messaging.mapper.MessageMapper;
import com.wangyutao.messaging.mapper.MsgFailoverMapper;
import com.wangyutao.messaging.model.dto.SendMsgRequest;
import com.wangyutao.messaging.model.entity.*;
import com.wangyutao.messaging.model.enums.SessionType;
import com.wangyutao.messaging.model.vo.ResponseMsgVo;
import com.wangyutao.messaging.model.vo.SyncMessageVo;
import com.wangyutao.messaging.service.MessageService;
import com.wangyutao.messaging.service.SessionService;
import com.wangyutao.messaging.service.UserService;
import com.wangyutao.messaging.service.UserSessionService;
import com.wangyutao.messaging.utils.IdGenerator;
import com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author yutao
 * @description 针对表【im_message(IM核心聊天消息表)】的数据库操作Service实现
 * @createDate 2026-03-10 11:36:08
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    // private final ImIdGenerator idGenerator;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final UserSessionService userSessionService;
    private final UserService userService;
    private final IdGenerator idGenerator;
    private final SessionService sessionService;
    private final FriendMapper friendMapper;
    private final MsgFailoverMapper msgFailoverMapper;

    // 🚀 定义标准的缓存 Key 前缀
    private static final String CACHE_USER_PREFIX = "im:cache:user:";
    private static final String CACHE_FRIEND_PREFIX = "im:cache:friend:";
    private static final String CACHE_SESSION_PREFIX = "im:cache:session:";
    // 🚀 新增：群聊成员列表缓存 Key
    private static final String CACHE_SESSION_MEMBERS_PREFIX = "im:cache:session:members:";

    // 🤖 AI 助手相关常量
    private static final Long AI_BOT_USER_ID = 10000L;
    private static final String AI_BOT_NAME = "AI助手";

    /** 与 RedPacketServiceImpl 中红包消息 type 一致；红包需推送给发送者，否则无法在群里点开自己的红包 */
    private static final int MESSAGE_TYPE_RED_PACKET = 5;

    @Override
    public ResponseMsgVo sendMessage(SendMsgRequest request) {

        Long sendUserId = request.getSendUserId();
        String sessionId = request.getSessionId();

        // 🌟 核心前提：请求中必须带有客户端生成的唯一 ID，用于防重试
        String clientMsgId = request.getClientMsgId();
        if (clientMsgId == null || clientMsgId.isEmpty()) {
            throw new ServiceException("缺少客户端消息唯一标识");
        }

        // ==========================================
        // 🛡️ 阶段零：可靠性第三层 - 幂等性兜底 (Exactly-Once)
        // ==========================================
        String dedupKey = "im:msg:dedup:" + sessionId + ":" + clientMsgId;
        // 🌟 P2-7: 缩短幂等 Key 过期时间为 5 分钟，节省 Redis 内存
        Boolean isFirstTime = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 5, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(isFirstTime)) {
            log.warn("检测到客户端重复投递，触发幂等拦截: {}", dedupKey);
            // 💡 架构师注：直接返回成功假象。既然是重复重试，说明之前的已经落库了   ，
            // 只需要再告诉客户端一次“成功”，让它停止重试阶梯即可。
            ResponseMsgVo fakeResponse = new ResponseMsgVo();
            fakeResponse.setClientMsgId(clientMsgId);
            fakeResponse.setStatus(1); // 1表示成功
            return fakeResponse;
        }

        try {
            // 1. 提前查一次发送者信息，后续 validate 和 fillUserInfo 复用
            User senderUser = getUserWithCache(sendUserId);

            // 2. 强校验：发送者与接收方状态/关系拦截
            validateSenderAndReceiver(request, sendUserId, senderUser);

            // ==========================================
            // 🔢 阶段一：有序性保障 - 会话级局部发号
            // ==========================================
            // 每个会话配一个独立计数器，每来一条消息就+1，生成唯一SEQ
            Long seq = redisTemplate.opsForValue().increment("im:seq:" + sessionId);

            String messageId = "msg_" + idGenerator.nextId(); // 服务端依然可以生成自己的主键
            Date now = new Date();

            // ==========================================
            // 🚀 阶段三：有序性保障 - MQ 哈希路由投递
            // ==========================================
            AppMessage appMessage = new AppMessage();
            BeanUtils.copyProperties(request, appMessage);
            appMessage.setClientMsgId(clientMsgId);
            appMessage.setMessageId(messageId);
            appMessage.setSeq(seq); // 🌟 必须把官方身份证发给前端，前端只认这个 [cite: 2, 311, 355]
            appMessage.setCreatedAt(formatDate(now));

            // 组装头像昵称逻辑... (与原代码一致)
            fillUserInfo(appMessage, request, senderUser);

            String messageJson = JSON.toJSONString(appMessage);
            long mqTimeout = 2000L;

            // 🌟 架构核心：无论是单聊还是群聊，都使用 asyncSendOrderly
            // 并使用 sessionId 作为 hashKey，确保同一会话路由到同一节点，避免跨节点乱序 [cite: 2, 301, 302, 303, 330]
            if (request.getSessionType() == SessionType.SINGLE.getValue()) {
                String targetNodeId = redisTemplate.opsForValue().get("im:route:" + request.getReceiveUserId());
                String mqTag = (targetNodeId == null || targetNodeId.isEmpty()) ? "OFFLINE" : targetNodeId;
                log.info("单聊路由决策: receiveUserId={}, targetNodeId={}, mqTag={}", request.getReceiveUserId(), targetNodeId, mqTag);

                // 🌟 修复：把 AppMessage 转为字符串作为 wsPayload，装进纸箱！
                String wsPayload = JSON.toJSONString(appMessage);
                GatewayPushPacket packet = new GatewayPushPacket(
                        Collections.singletonList(request.getReceiveUserId()), // 目标用户
                        wsPayload // 真实要发给前端的内容
                );

                org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                        .withPayload(JSON.toJSONString(packet)) // 扔进 MQ 的是纸箱的 JSON！
                        .setHeader("KEYS", messageId).build();

                rocketMQTemplate.asyncSendOrderly("IM_CHAT:" + mqTag, mqMessage, sessionId, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.debug("单聊MQ投递成功, messageId={}", messageId);
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("单聊推送异常", e);
                        // MQ 发送失败时，写入本地兜底表，等待补偿任务重放
                        MsgFailover failover = new MsgFailover()
                                .setClientMsgId(clientMsgId)
                                .setSessionId(sessionId)
                                .setPayload(JSON.toJSONString(packet))
                                .setStatus(0)
                                .setCreateTime(now)
                                .setUpdateTime(now);
                        msgFailoverMapper.insert(failover);
                    }
                }, mqTimeout);

            } else {
                // 群聊投递逻辑：扩散写
                List<Long> memberIds = this.getGroupMemberIdsWithCache(sessionId);

                if (memberIds != null && !memberIds.isEmpty()) {
                    // 普通群消息不推送给发送者（避免回声）；红包消息必须包含发送者，否则本人会话里无红包卡片、无法抢自己的拼手气等
                    if (request.getType() == null || request.getType() != MESSAGE_TYPE_RED_PACKET) {
                        memberIds.remove(Long.valueOf(sendUserId));
                    }
                    // 💡 优化 1：放弃 for 循环逐个查 Redis，使用 mget 批量获取路由节点
                    // 将 O(N) 的网络 I/O 压缩为 O(1)
                    List<String> routeKeys = memberIds.stream()
                            .map(id -> "im:route:" + id)
                            .collect(Collectors.toList());
                    List<String> targetNodes = redisTemplate.opsForValue().multiGet(routeKeys);

                    // 💡 优化 2：按目标 Netty 节点进行用户分组 (Map<节点ID, 目标用户列表>)
                    // 极大减少 MQ 的发送次数
                    Map<String, List<Long>> nodeUserMap = new HashMap<>();
                    List<Long> offlineUserIds = new ArrayList<>();

                    for (int i = 0; i < memberIds.size(); i++) {
                        String nodeId = targetNodes.get(i);
                        if (nodeId != null && !nodeId.isEmpty() && !"OFFLINE".equals(nodeId)) {
                            // 将属于同一个 Netty 节点的用户归拢到一起
                            nodeUserMap.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(memberIds.get(i));
                        } else {
                            // 🌟 收集离线用户，稍后统一发送 OFFLINE 消息
                            offlineUserIds.add(memberIds.get(i));
                        }
                    }

                    // 💡 优化 3：按节点打包发送 MQ (将 N 次 MQ 投递压缩为 M 次，M为实际存活的网关节点数)

                    // 🌟 处理在线用户：按节点批量发送
                    for (Map.Entry<String, List<Long>> entry : nodeUserMap.entrySet()) {
                        String targetNode = entry.getKey();
                        List<Long> targetUserIds = entry.getValue();

                        // 🌟 修复优化：不用把 targetUserIds 塞给 appMessage 了，省带宽！
                        appMessage.setReceiveUserIds(null);
                        String wsPayload = JSON.toJSONString(appMessage);

                        // 🌟 套上网关标准纸箱！
                        GatewayPushPacket packet = new GatewayPushPacket(
                                targetUserIds, // 告诉 Netty 监听器，把包裹分发给这批人
                                wsPayload      // 纯净版的聊天数据
                        );

                        org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                                .withPayload(JSON.toJSONString(packet))
                                .setHeader("KEYS", messageId)
                                .build();

                        // 同一个群的消息，依然使用 sessionId 保证在同一个 Queue 里排队
                        rocketMQTemplate.asyncSendOrderly("IM_CHAT:" + targetNode, mqMessage, sessionId, new SendCallback() {
                            @Override
                            public void onSuccess(SendResult sendResult) {
                                log.debug("群聊MQ投递成功, messageId={}", messageId);
                            }

                            @Override
                            public void onException(Throwable e) {
                                log.error("群聊批量推送异常", e);
                                MsgFailover failover = new MsgFailover()
                                        .setClientMsgId(clientMsgId)
                                        .setSessionId(sessionId)
                                        .setPayload(JSON.toJSONString(packet))
                                        .setStatus(0)
                                        .setCreateTime(now)
                                        .setUpdateTime(now);
                                msgFailoverMapper.insert(failover);
                            }
                        }, mqTimeout);
                    }

                    // 🌟 处理离线用户：统一发送 OFFLINE 消息
                    if (!offlineUserIds.isEmpty()) {
                        appMessage.setReceiveUserIds(null);
                        String wsPayload = JSON.toJSONString(appMessage);

                        GatewayPushPacket offlinePacket = new GatewayPushPacket(
                                offlineUserIds, // 所有离线用户
                                wsPayload
                        );

                        org.springframework.messaging.Message<String> offlineMqMessage = MessageBuilder
                                .withPayload(JSON.toJSONString(offlinePacket))
                                .setHeader("KEYS", messageId)
                                .build();

                        // 发送到 OFFLINE Tag，由 OfflineService 的 OfflineMessageListener 处理
                        rocketMQTemplate.asyncSendOrderly("IM_CHAT:OFFLINE", offlineMqMessage, sessionId, new SendCallback() {
                            @Override
                            public void onSuccess(SendResult sendResult) {
                                log.info("群聊离线消息发送成功, offlineUserCount={}, messageId={}",
                                    offlineUserIds.size(), messageId);
                            }

                            @Override
                            public void onException(Throwable e) {
                                log.error("群聊离线消息发送失败, offlineUserIds={}", offlineUserIds, e);
                                MsgFailover failover = new MsgFailover()
                                        .setClientMsgId(clientMsgId)
                                        .setSessionId(sessionId)
                                        .setPayload(JSON.toJSONString(offlinePacket))
                                        .setStatus(0)
                                        .setCreateTime(now)
                                        .setUpdateTime(now);
                                msgFailoverMapper.insert(failover);
                            }
                        }, mqTimeout);
                    }
                }
            }

            // ==========================================
            // ✅ 阶段四：收尾响应 (ACK)
            // 使用 CompletableFuture 假同步、真异步等待 MQ 结果
            // ==========================================
            // ✅ 阶段四：MQ 已异步投递，直接返回受理结果
            // MQ 发送失败由 onException 回调写入 msg_failover 兜底
            // ==========================================
            ResponseMsgVo responseVo = new ResponseMsgVo();
            responseVo.setMessageId(messageId);
            responseVo.setClientMsgId(clientMsgId);
            responseVo.setSeq(seq);
            responseVo.setStatus(1);

            // 🤖 阶段五：AI 助手触发判断
            triggerAiAgentIfNeeded(request, messageId, sendUserId);

            return responseVo;

        } catch (Exception e) {
            // 🚨 极端异常兜底：如果落库失败或抛出异常，必须把防重 Key 删掉，否则客户端再重试就被永远死锁拦截了！
            redisTemplate.delete(dedupKey);
            throw e;
        }
    }

    private String formatDate(Date date) {
        if (date == null) return null;
        // 🌟 Hutool 的 DateUtil，默认格式化为 "yyyy-MM-dd HH:mm:ss"
        return cn.hutool.core.date.DateUtil.formatDateTime(date);
    }

    /**
     * 强校验：发送者与接收方状态、关系拦截
     * * @param request    发送消息请求体
     *
     * @param sendUserId 发送者ID
     */

    // =========================================================================
    // 🌟 架构师级别：极速缓存读取方法 (Cache-Aside + 防雪崩)
    // =========================================================================

    /**
     * 极速获取用户信息
     */
    private User getUserWithCache(Long userId) {
        if (userId == null) return null;
        String key = CACHE_USER_PREFIX + userId;
        String json = redisTemplate.opsForValue().get(key);
        if (org.apache.commons.lang3.StringUtils.isNotBlank(json)) {
            return JSON.parseObject(json, User.class);
        }
        User user = userService.getById(userId);
        if (user != null) {
            // 设置 24~48 小时随机过期，防止雪崩
            long expire = 24L + ThreadLocalRandom.current().nextInt(24);
            redisTemplate.opsForValue().set(key, JSON.toJSONString(user), expire, TimeUnit.HOURS);
        }
        return user;
    }

    /**
     * 极速获取好友关系
     */
    private Friend getFriendWithCache(Long userId, Long friendId) {
        if (userId == null || friendId == null) return null;
        // 保证方向一致，或者双向各自存一份，这里用 sender:receiver 作为 key
        String key = CACHE_FRIEND_PREFIX + userId + ":" + friendId;
        String json = redisTemplate.opsForValue().get(key);
        if (org.apache.commons.lang3.StringUtils.isNotBlank(json)) {
            return JSON.parseObject(json, Friend.class);
        }
        Friend friend = friendMapper.selectFriendship(userId, friendId);
        if (friend != null) {
            long expire = 24L + ThreadLocalRandom.current().nextInt(24);
            redisTemplate.opsForValue().set(key, JSON.toJSONString(friend), expire, TimeUnit.HOURS);
        }
        return friend;
    }

    /**
     * 极速获取会话信息
     */
    private Session getSessionWithCache(String sessionId) {
        if (sessionId == null) return null;
        String key = CACHE_SESSION_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (org.apache.commons.lang3.StringUtils.isNotBlank(json)) {
            return JSON.parseObject(json, Session.class);
        }
        Session session = sessionService.getById(sessionId);
        if (session != null) {
            long expire = 24L + ThreadLocalRandom.current().nextInt(24);
            redisTemplate.opsForValue().set(key, JSON.toJSONString(session), expire, TimeUnit.HOURS);
        }
        return session;
    }

    // =========================================================================
    // 🚀 替换慢 SQL 为极速缓存读取
    // =========================================================================
    private void validateSenderAndReceiver(SendMsgRequest request, Long sendUserId, User senderUser) {
        // 🌟 1. 发送者状态拦截（senderUser 由调用方传入，避免重复查 Redis）
        if (senderUser == null || senderUser.getStatus() != 1) {
            throw new ServiceException("发送者状态异常");
        }

        if (request.getSessionType() == SessionType.SINGLE.getValue()) {
            // 🌟 2. 单聊：校验接收者状态及双向好友关系 (使用缓存)
            Long receiveUserId = request.getReceiveUserId();
            User receiverUser = getUserWithCache(receiveUserId);
            if (receiverUser == null || receiverUser.getStatus() != 1) {
                throw new ServiceException("接收者状态异常");
            }

            Friend friend = getFriendWithCache(sendUserId, receiveUserId);
            if (friend == null || friend.getStatus() != 1) {
                throw new ServiceException("非好友关系，无法发送消息");
            }

        } else if (request.getSessionType() == SessionType.GROUP.getValue()) {
            List<Long> memberIds =  this.getGroupMemberIdsWithCache(request.getSessionId());
            if (memberIds == null || !memberIds.contains(sendUserId)) {
                throw new ServiceException("发送者不在该群聊内，无法发送消息");
            }
        } else {
            throw new ServiceException("未知的会话类型");
        }
    }

    /**
     * 组装前端强依赖的展示信息 (头像、昵称、会话名等)
     * * @param appMessage 准备发往 MQ 的消息实体
     *
     * @param request    前端传来的原始请求
     * @param senderUser 发送者ID
     */
    private void fillUserInfo(AppMessage appMessage, SendMsgRequest request, User senderUser) {
        // 🌟 1. 组装发送者基本信息（senderUser 由调用方传入，避免重复查 Redis）
        if (senderUser != null) {
            appMessage.setAvatar(senderUser.getAvatar());
            appMessage.setUserName(senderUser.getUserName());
        }

        if (request.getSessionType() == SessionType.SINGLE.getValue()) {
            appMessage.setSessionAvatar(null);
            appMessage.setSessionName(null);
            appMessage.setReceiveUserIds(java.util.Collections.singletonList(request.getReceiveUserId()));

        } else if (request.getSessionType() == SessionType.GROUP.getValue()) {
            // 🌟 2. 组装群聊特定的展示信息 (使用缓存)
            Session session = getSessionWithCache(request.getSessionId());

            appMessage.setSessionAvatar("http://47.115.130.44/img/avatar/IM_GROUP.jpg");
            appMessage.setSessionName(session != null ? session.getName() : "未命名群聊");
        }
    }

    /**
     * 极速获取群聊成员 ID 列表
     */
    private List<Long> getGroupMemberIdsWithCache(String sessionId) {
        if (sessionId == null) return new ArrayList<>();
        String key = CACHE_SESSION_MEMBERS_PREFIX + sessionId;

        // 1. 查 Redis
        String json = redisTemplate.opsForValue().get(key);
        if (org.apache.commons.lang3.StringUtils.isNotBlank(json)) {
            // 命中缓存，把 JSON 数组转回 List<Long>
            return JSON.parseArray(json, Long.class);
        }

        // 2. 没命中，老老实实查 MySQL
        List<Long> memberIds = userSessionService.getUserIdsBySessionId(sessionId);

        // 3. 查到了，写进 Redis，设置 24~48 小时随机过期防雪崩
        if (memberIds != null && !memberIds.isEmpty()) {
            long expire = 24L + ThreadLocalRandom.current().nextInt(24);
            redisTemplate.opsForValue().set(key, JSON.toJSONString(memberIds), expire, TimeUnit.HOURS);
        }

        return memberIds != null ? memberIds : new ArrayList<>();
    }

    /**
     * 🤖 AI 助手触发判断逻辑
     * 判断是否需要触发 AI 助手,如果需要则发送 MQ 到 AI_AGENT_REQUEST Topic
     */
    private void triggerAiAgentIfNeeded(SendMsgRequest request, String messageId, Long sendUserId) {
        try {
            boolean shouldTriggerAi = false;
            String messageContent = extractMessageContent(request.getBody());

            // 场景 1: 单聊向 AI 助手发送消息
            if (request.getSessionType() == SessionType.SINGLE.getValue()
                && AI_BOT_USER_ID.equals(request.getReceiveUserId())) {
                shouldTriggerAi = true;
                log.info("🤖 检测到单聊向 AI 助手发送消息, userId={}, messageId={}", sendUserId, messageId);
            }

            // 场景 2: 群聊中 @ AI 助手
            if (request.getSessionType() == SessionType.GROUP.getValue()
                && messageContent != null
                && messageContent.contains("@" + AI_BOT_NAME)) {
                shouldTriggerAi = true;
                log.info("🤖 检测到群聊 @ AI 助手, sessionId={}, userId={}, messageId={}",
                    request.getSessionId(), sendUserId, messageId);
            }

            if (shouldTriggerAi) {
                // 组装 AI Agent 请求消息
                AgentMessageRequest agentRequest = new AgentMessageRequest();
                agentRequest.setUserId(sendUserId);
                agentRequest.setSessionId(request.getSessionId());
                agentRequest.setMessageId(messageId);
                agentRequest.setContent(messageContent);
                agentRequest.setSessionType(request.getSessionType());
                agentRequest.setTimestamp(System.currentTimeMillis());
                if (request.getSessionType() == SessionType.GROUP.getValue()) {
                    agentRequest.setMentionedBotName(AI_BOT_NAME);
                }

                // 发送到 AI_AGENT_REQUEST Topic
                String tag = request.getSessionType() == SessionType.SINGLE.getValue()
                    ? "SINGLE_CHAT" : "GROUP_CHAT";

                org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                    .withPayload(JSON.toJSONString(agentRequest))
                    .setHeader("KEYS", messageId)
                    .build();

                // 使用 sessionId 作为 hashKey 保证有序
                rocketMQTemplate.asyncSendOrderly(
                    "AI_AGENT_REQUEST:" + tag,
                    mqMessage,
                    request.getSessionId(),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            log.info("🤖 AI Agent 请求发送成功, messageId={}", messageId);
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("🤖 AI Agent 请求发送失败, messageId={}", messageId, e);
                        }
                    },
                    2000L
                );
            }
        } catch (Exception e) {
            log.error("🤖 AI 助手触发判断异常, messageId={}", messageId, e);
        }
    }

    /**
     * 从消息体中提取文本内容
     */
    private String extractMessageContent(Object body) {
        if (body == null) {
            return null;
        }

        try {
            if (body instanceof String) {
                return (String) body;
            }

            String jsonStr = JSON.toJSONString(body);
            com.alibaba.fastjson.JSONObject jsonObject = JSON.parseObject(jsonStr);

            if (jsonObject.containsKey("content")) {
                return jsonObject.getString("content");
            }

            if (jsonObject.containsKey("text")) {
                return jsonObject.getString("text");
            }

            return jsonStr;
        } catch (Exception e) {
            log.warn("提取消息内容失败", e);
            return null;
        }
    }

    @Override
    public List<SyncMessageVo> syncMessages(String sessionId, Long beginSeq, Long endSeq) {
        long actualEndSeq = Math.min(endSeq, beginSeq + 500);

        List<Message> messages = baseMapper.selectBySeqRange(sessionId, beginSeq, actualEndSeq);
        Map<Long, Message> seqMap = messages.stream()
            .collect(Collectors.toMap(Message::getSeq, m -> m));

        List<SyncMessageVo> result = new ArrayList<>();
        for (long seq = beginSeq + 1; seq <= actualEndSeq; seq++) {
            Message msg = seqMap.get(seq);
            if (msg != null) {
                SyncMessageVo vo = new SyncMessageVo()
                    .setMessageId(msg.getMessageId())
                    .setSessionId(msg.getSessionId())
                    .setSeq(msg.getSeq())
                    .setSenderId(msg.getSenderId())
                    .setMessageType(msg.getMessageType())
                    .setContent(msg.getContent())
                    .setCreatedAt(msg.getCreateTime() != null ? msg.getCreateTime().toString() : null)
                    .setStatus(msg.getStatus())
                    .setIsTombstone(false);
                result.add(vo);
            } else {
                SyncMessageVo tombstone = new SyncMessageVo()
                    .setSessionId(sessionId)
                    .setSeq(seq)
                    .setStatus(-1)
                    .setIsTombstone(true);
                result.add(tombstone);
            }
        }
        return result;
    }

    @Override
    public Long getMaxSeq(String sessionId) {
        String val = redisTemplate.opsForValue().get("im:seq:" + sessionId);
        return val != null ? Long.parseLong(val) : 0L;
    }

    @Override
    public void ackSeq(Long userId, String sessionId, Long seq) {
        userSessionService.updateLastAckSeq(userId, sessionId, seq);
    }

    @Override
    public void ackMessage(Long userId, String messageId) {
        Message message = this.getById(messageId);
        if (message == null) {
            log.warn("ACK 回查消息为空, userId={}, messageId={}", userId, messageId);
            return;
        }
        if (message.getSessionId() == null || message.getSeq() == null) {
            log.warn("ACK 回查消息缺少 sessionId/seq, userId={}, messageId={}", userId, messageId);
            return;
        }
        userSessionService.updateLastAckSeq(userId, message.getSessionId(), message.getSeq());
    }

    @Override
    public Map<String, Long> getSessionAckSeqMap(Long userId) {
        return userSessionService.getLastAckSeqMap(userId);
    }

    /**
     * AI Agent 请求消息 DTO (内部类)
     */
    @Data
    private static class AgentMessageRequest {
        private Long userId;
        private String sessionId;
        private String messageId;
        private String content;
        private Integer sessionType;
        private Long timestamp;
        private String mentionedBotName;
    }
}
