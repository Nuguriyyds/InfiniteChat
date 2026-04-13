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

import javax.annotation.Resource;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executor;
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

    /** MQ 发送专用线程池，防止 asyncSendOrderly 在 producer 队列满时阻塞 Tomcat 请求线程 */
    @Resource(name = "mqSendExecutor")
    private Executor mqSendExecutor;
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
    private static final long DEDUP_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final DefaultRedisScript<Long> DEDUP_AND_SEQ_SCRIPT = createDedupAndSeqScript();

    @Override
    public ResponseMsgVo sendMessage(SendMsgRequest request) {

        Long sendUserId = request.getSendUserId();
        String sessionId = request.getSessionId();

        String clientMsgId = request.getClientMsgId();
        if (clientMsgId == null || clientMsgId.isEmpty()) {
            throw new ServiceException("缺少客户端消息唯一标识");
        }

        String dedupKey = "im:msg:dedup:" + sessionId + ":" + clientMsgId;
        String seqKey = "im:seq:" + sessionId;
        User senderUser;
        User receiverUser = null;
        Friend friend = null;
        List<Long> groupMemberIds = null;
        if (request.getSessionType() == SessionType.SINGLE.getValue()) {
            SingleChatCacheSnapshot cacheSnapshot = getSingleChatCacheSnapshot(sendUserId, request.getReceiveUserId());
            senderUser = cacheSnapshot.getSenderUser();
            receiverUser = cacheSnapshot.getReceiverUser();
            friend = cacheSnapshot.getFriend();
        } else {
            senderUser = getUserWithCache(sendUserId);
            groupMemberIds = this.getGroupMemberIdsWithCache(sessionId);
        }

        try {
            validateSenderAndReceiver(request, sendUserId, senderUser, receiverUser, friend, groupMemberIds);

            Long seq = redisTemplate.execute(
                    DEDUP_AND_SEQ_SCRIPT,
                    Arrays.asList(dedupKey, seqKey),
                    String.valueOf(DEDUP_TTL_MILLIS)
            );
            if (seq == null) {
                throw new ServiceException("Redis seq 鐢熸垚澶辫触");
            }
            if (seq < 0) {
                ResponseMsgVo fakeResponse = new ResponseMsgVo();
                fakeResponse.setClientMsgId(clientMsgId);
                fakeResponse.setStatus(1);
                return fakeResponse;
            }
            String messageId = "msg_" + idGenerator.nextId();
            Date now = new Date();
            ResponseMsgVo responseVo = new ResponseMsgVo();
            responseVo.setMessageId(messageId);
            responseVo.setClientMsgId(clientMsgId);
            responseVo.setSeq(seq);
            responseVo.setStatus(1);

            AppMessage appMessage = new AppMessage();
            mapRequestToAppMessage(request, appMessage);
            appMessage.setClientMsgId(clientMsgId);
            appMessage.setMessageId(messageId);
            appMessage.setSeq(seq);
            appMessage.setCreatedAt(formatDate(now));
            fillUserInfo(appMessage, request, senderUser);

            List<Long> targetUserIds = getTargetUserIds(request, groupMemberIds, sendUserId);
            appMessage.setReceiveUserIds(targetUserIds);

            String messageJson = JSON.toJSONString(appMessage);
            GatewayPushPacket failoverPacket = new GatewayPushPacket(targetUserIds, messageJson);
            org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                    .withPayload(messageJson)
                    .setHeader("KEYS", messageId)
                    .build();

            mqSendExecutor.execute(() ->
                rocketMQTemplate.asyncSendOrderly("IM_MSG_STORE", mqMessage, sessionId, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.debug("send to IM_MSG_STORE success, clientMsgId={}, sendStatus={}",
                                clientMsgId, sendResult.getSendStatus());
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("send to IM_MSG_STORE failed, clientMsgId={}", clientMsgId, e);
                        MsgFailover failover = new MsgFailover()
                                .setClientMsgId(clientMsgId)
                                .setSessionId(sessionId)
                                .setPayload(JSON.toJSONString(failoverPacket))
                                .setStatus(0)
                                .setCreateTime(now)
                                .setUpdateTime(now);
                        msgFailoverMapper.insert(failover);
                    }
                }, 2000L)
            );

            triggerAiAgentIfNeeded(request, messageId, sendUserId);
            return responseVo;

        } catch (Exception e) {
            redisTemplate.delete(dedupKey);
            throw new RuntimeException("sendMessage failed", e);
        }
    }

    @NotNull
    private static List<Long> getTargetUserIds(SendMsgRequest request, List<Long> groupMemberIds, Long sendUserId) {
        List<Long> targetUserIds = new ArrayList<>();
        if (request.getSessionType() == SessionType.SINGLE.getValue()) {
            targetUserIds.add(request.getReceiveUserId());
        } else {
            List<Long> memberIds = groupMemberIds != null ? new ArrayList<>(groupMemberIds) : new ArrayList<>();
            if (!memberIds.isEmpty()) {
                if (request.getType() == null || request.getType() != MESSAGE_TYPE_RED_PACKET) {
                    memberIds.remove(Long.valueOf(sendUserId));
                }
                targetUserIds.addAll(memberIds);
            }
        }
        return targetUserIds;
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

    private Map<Long, User> getUsersWithCache(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> distinctUserIds = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (distinctUserIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> keys = distinctUserIds.stream()
                .map(userId -> CACHE_USER_PREFIX + userId)
                .collect(Collectors.toList());
        List<String> cachedJsonList = redisTemplate.opsForValue().multiGet(keys);

        Map<Long, User> result = new HashMap<>(distinctUserIds.size());
        List<Long> missingUserIds = new ArrayList<>();
        for (int i = 0; i < distinctUserIds.size(); i++) {
            String cachedJson = cachedJsonList == null ? null : cachedJsonList.get(i);
            if (org.apache.commons.lang3.StringUtils.isNotBlank(cachedJson)) {
                result.put(distinctUserIds.get(i), JSON.parseObject(cachedJson, User.class));
            } else {
                missingUserIds.add(distinctUserIds.get(i));
            }
        }

        if (missingUserIds.isEmpty()) {
            return result;
        }

        Collection<User> missingUsers = userService.listByIds(missingUserIds);
        for (User user : missingUsers) {
            if (user == null || user.getUserId() == null) {
                continue;
            }
            result.put(user.getUserId(), user);
            long expire = 24L + ThreadLocalRandom.current().nextInt(24);
            redisTemplate.opsForValue().set(
                    CACHE_USER_PREFIX + user.getUserId(),
                    JSON.toJSONString(user),
                    expire,
                    TimeUnit.HOURS
            );
        }
        return result;
    }

    private SingleChatCacheSnapshot getSingleChatCacheSnapshot(Long sendUserId, Long receiveUserId) {
        SingleChatCacheSnapshot snapshot = new SingleChatCacheSnapshot();
        if (sendUserId == null || receiveUserId == null) {
            return snapshot;
        }

        String senderKey = CACHE_USER_PREFIX + sendUserId;
        String receiverKey = CACHE_USER_PREFIX + receiveUserId;
        String friendKey = CACHE_FRIEND_PREFIX + sendUserId + ":" + receiveUserId;
        List<String> cachedValues = redisTemplate.opsForValue().multiGet(Arrays.asList(senderKey, receiverKey, friendKey));

        if (cachedValues != null && cachedValues.size() >= 3) {
            if (org.apache.commons.lang3.StringUtils.isNotBlank(cachedValues.get(0))) {
                snapshot.setSenderUser(JSON.parseObject(cachedValues.get(0), User.class));
            }
            if (org.apache.commons.lang3.StringUtils.isNotBlank(cachedValues.get(1))) {
                snapshot.setReceiverUser(JSON.parseObject(cachedValues.get(1), User.class));
            }
            if (org.apache.commons.lang3.StringUtils.isNotBlank(cachedValues.get(2))) {
                snapshot.setFriend(JSON.parseObject(cachedValues.get(2), Friend.class));
            }
        }

        if (snapshot.getSenderUser() == null || snapshot.getReceiverUser() == null) {
            Map<Long, User> userMap = getUsersWithCache(Arrays.asList(sendUserId, receiveUserId));
            snapshot.setSenderUser(userMap.get(sendUserId));
            snapshot.setReceiverUser(userMap.get(receiveUserId));
        }

        if (snapshot.getFriend() == null) {
            Friend loadedFriend = friendMapper.selectFriendship(sendUserId, receiveUserId);
            if (loadedFriend != null) {
                snapshot.setFriend(loadedFriend);
                long expire = 24L + ThreadLocalRandom.current().nextInt(24);
                redisTemplate.opsForValue().set(friendKey, JSON.toJSONString(loadedFriend), expire, TimeUnit.HOURS);
            }
        }

        return snapshot;
    }

    private void mapRequestToAppMessage(SendMsgRequest request, AppMessage appMessage) {
        appMessage.setSessionId(request.getSessionId());
        appMessage.setSessionType(request.getSessionType());
        appMessage.setType(request.getType());
        appMessage.setSendUserId(request.getSendUserId());
        appMessage.setBody(request.getBody());
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
    private void validateSenderAndReceiver(
            SendMsgRequest request,
            Long sendUserId,
            User senderUser,
            User receiverUser,
            Friend friend,
            List<Long> groupMemberIds
    ) {
        if (senderUser == null || senderUser.getStatus() != 1) {
            throw new ServiceException("发送者状态异常");
        }

        if (request.getSessionType() == SessionType.SINGLE.getValue()) {
            if (receiverUser == null || receiverUser.getStatus() != 1) {
                throw new ServiceException("接收者状态异常");
            }
            if (friend == null || friend.getStatus() != 1) {
                throw new ServiceException("非好友关系，无法发送消息");
            }
        } else if (request.getSessionType() == SessionType.GROUP.getValue()) {
            if (groupMemberIds == null || !groupMemberIds.contains(sendUserId)) {
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

                // 使用 sessionId 作为 hashKey 保证有序；同样走专用线程池，不阻塞请求线程
                mqSendExecutor.execute(() ->
                    rocketMQTemplate.asyncSendOrderly(
                        "AI_AGENT_REQUEST:" + tag,
                        mqMessage,
                        request.getSessionId(),
                        new SendCallback() {
                            @Override
                            public void onSuccess(SendResult sendResult) {
                                log.info("AI Agent 请求发送成功, messageId={}", messageId);
                            }

                            @Override
                            public void onException(Throwable e) {
                                log.error("AI Agent 请求发送失败, messageId={}", messageId, e);
                            }
                        },
                        2000L
                    )
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

    private static DefaultRedisScript<Long> createDedupAndSeqScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(
                "if redis.call('set', KEYS[1], '1', 'PX', ARGV[1], 'NX') then " +
                        "return redis.call('incr', KEYS[2]) " +
                        "end " +
                        "return -1"
        );
        return script;
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
    private static class SingleChatCacheSnapshot {
        private User senderUser;
        private User receiverUser;
        private Friend friend;
    }

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
