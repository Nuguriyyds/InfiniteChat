package com.wangyutao.offlineservice.service.impl;

import com.alibaba.fastjson.JSON;
import com.wangyutao.offlineservice.exception.ServiceException;
import com.wangyutao.offlineservice.mapper.OfflineMessageMapper;
import com.wangyutao.offlineservice.model.dto.PullOfflineMessageRequest;
import com.wangyutao.offlineservice.model.dto.PullOfflineMessageResponse;
import com.wangyutao.offlineservice.model.entity.AppMessage;
import com.wangyutao.offlineservice.model.entity.OfflineMessage;
import com.wangyutao.offlineservice.service.OfflineMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineMessageServiceImpl implements OfflineMessageService {

    private final OfflineMessageMapper offlineMessageMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String OFFLINE_MSG_PREFIX = "im:offline:";
    private static final String OFFLINE_SESSIONS_PREFIX = "im:offline:sessions:";
    private static final String PULL_LIMIT_KEY_PREFIX = "im:pull:limit:";
    private static final int HOT_DATA_DAYS = 3;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int PULL_LIMIT_PER_SECOND = 10;
    private static final double SCORE_EPSILON = 0.000001d;

    @Override
    public PullOfflineMessageResponse pullOfflineMessages(PullOfflineMessageRequest request) {
        Long userId = request.getUserId();
        int pageSize = Math.min(request.getPageSize(), MAX_PAGE_SIZE);

        if (!checkPullLimit(userId)) {
            throw new ServiceException("拉取过于频繁，请稍后重试");
        }

        List<AppMessage> messages;
        if (request.getSessionId() != null) {
            messages = pullSingleSession(userId, request.getSessionId(), request.getLastSeq(), pageSize);
        } else {
            messages = pullAllSessions(userId, request.getSessionSeqMap(), pageSize);
        }

        return new PullOfflineMessageResponse(
                messages,
                messages.size() >= pageSize,
                (long) messages.size(),
                System.currentTimeMillis()
        );
    }

    private List<AppMessage> pullSingleSession(Long userId, String sessionId, Long lastSeq, int pageSize) {
        long effectiveLastSeq = normalizeSeq(lastSeq);
        if (isRedisHotRangeCovered(userId, sessionId, effectiveLastSeq)) {
            List<AppMessage> hotMessages = pullFromRedisForSession(userId, sessionId, effectiveLastSeq, pageSize);
            if (!hotMessages.isEmpty()) {
                return hotMessages;
            }
        }
        return pullFromMySQLBySession(userId, sessionId, effectiveLastSeq, pageSize);
    }

    private List<AppMessage> pullAllSessions(Long userId, Map<String, Long> seqMap, int pageSize) {
        if (seqMap == null || seqMap.isEmpty()) {
            return new ArrayList<>();
        }

        List<AppMessage> all = new ArrayList<>();
        for (Map.Entry<String, Long> entry : seqMap.entrySet()) {
            String sessionId = entry.getKey();
            long lastSeq = normalizeSeq(entry.getValue());
            if (isRedisHotRangeCovered(userId, sessionId, lastSeq)) {
                all.addAll(pullFromRedisForSession(userId, sessionId, lastSeq, pageSize));
            } else {
                all.addAll(pullFromMySQLBySession(userId, sessionId, lastSeq, pageSize));
            }
        }

        all.sort(buildOfflineMessageComparator());
        return all.size() > pageSize ? new ArrayList<>(all.subList(0, pageSize)) : all;
    }

    private List<AppMessage> pullFromMySQLBySession(Long userId, String sessionId, long lastSeq, int limit) {
        try {
            return offlineMessageMapper.selectBySessionAndSeq(userId, sessionId, lastSeq, limit)
                    .stream()
                    .map(this::convertToAppMessage)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("从 MySQL 拉取离线消息失败, userId={}, sessionId={}", userId, sessionId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void storeOfflineMessage(Long receiverId, String messageJson) {
        try {
            AppMessage appMessage = JSON.parseObject(messageJson, AppMessage.class);
            if (!isValidOfflineAppMessage(appMessage) || appMessage.getMessageId() == null) {
                throw new ServiceException("离线消息内容非法，缺少 sessionId 或 messageId");
            }

            storeToRedis(receiverId, appMessage);
            storeToMySQL(receiverId, appMessage);

            log.info("离线消息存储成功, receiverId={}, messageId={}, sessionId={}",
                    receiverId, appMessage.getMessageId(), appMessage.getSessionId());
        } catch (Exception e) {
            log.error("存储离线消息失败, receiverId={}", receiverId, e);
            throw new ServiceException("存储离线消息失败", e);
        }
    }

    private void storeToRedis(Long receiverId, AppMessage appMessage) {
        String sessionId = appMessage.getSessionId();
        String zsetKey = buildZSetKey(receiverId, sessionId);
        String sessionsKey = OFFLINE_SESSIONS_PREFIX + receiverId;

        String rawMessage = JSON.toJSONString(appMessage);
        double score = appMessage.getSeq() != null ? appMessage.getSeq().doubleValue() : System.currentTimeMillis();

        redisTemplate.opsForZSet().add(zsetKey, rawMessage, score);
        redisTemplate.expire(zsetKey, Duration.ofDays(HOT_DATA_DAYS));

        redisTemplate.opsForSet().add(sessionsKey, sessionId);
        redisTemplate.expire(sessionsKey, Duration.ofDays(HOT_DATA_DAYS));
    }

    private List<AppMessage> pullFromRedisForSession(Long userId, String sessionId, Long lastSeq, int limit) {
        String zsetKey = buildZSetKey(userId, sessionId);
        Set<String> rawMessages = redisTemplate.opsForZSet()
                .rangeByScore(zsetKey, minScoreExclusive(lastSeq), Double.POSITIVE_INFINITY, 0, limit);
        if (rawMessages == null || rawMessages.isEmpty()) {
            return new ArrayList<>();
        }

        List<AppMessage> result = new ArrayList<>();
        for (String raw : new ArrayList<>(rawMessages)) {
            AppMessage message = parseOfflineAppMessage(raw);
            if (isValidOfflineAppMessage(message)) {
                result.add(message);
            } else {
                log.warn("丢弃无效离线 ZSet 条目并删除, userId={}, sessionId={}, snippet={}",
                        userId, sessionId, snippet(raw));
            }
            redisTemplate.opsForZSet().remove(zsetKey, raw);
        }

        Long remaining = redisTemplate.opsForZSet().zCard(zsetKey);
        if (remaining != null && remaining == 0) {
            redisTemplate.opsForSet().remove(OFFLINE_SESSIONS_PREFIX + userId, sessionId);
        }
        return result;
    }

    private AppMessage parseOfflineAppMessage(String json) {
        if (json == null) {
            return null;
        }
        String text = json.trim();
        if (text.isEmpty() || "{}".equals(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return JSON.parseObject(json, AppMessage.class);
        } catch (Exception e) {
            log.warn("解析离线消息 JSON 失败: {}", snippet(json), e);
            return null;
        }
    }

    private boolean isValidOfflineAppMessage(AppMessage message) {
        if (message == null) {
            return false;
        }
        if (message.getSessionId() == null || message.getSessionId().isEmpty()) {
            return false;
        }
        return (message.getMessageId() != null && !message.getMessageId().isEmpty())
                || (message.getClientMsgId() != null && !message.getClientMsgId().isEmpty());
    }

    private void storeToMySQL(Long receiverId, AppMessage appMessage) {
        Long existed = offlineMessageMapper.countByReceiverAndMessageId(receiverId, appMessage.getMessageId());
        if (existed != null && existed > 0) {
            log.info("离线消息已存在，跳过重复落库, receiverId={}, messageId={}",
                    receiverId, appMessage.getMessageId());
            return;
        }

        OfflineMessage offlineMessage = new OfflineMessage();
        offlineMessage.setMessageId(appMessage.getMessageId());
        offlineMessage.setReceiverId(receiverId);
        offlineMessage.setSessionId(appMessage.getSessionId());
        offlineMessage.setSeq(appMessage.getSeq());
        offlineMessage.setMessageType(appMessage.getType());
        offlineMessage.setContent(JSON.toJSONString(appMessage));
        offlineMessage.setSenderId(appMessage.getSendUserId());
        offlineMessage.setSenderName(appMessage.getUserName());
        offlineMessage.setSenderAvatar(appMessage.getAvatar());
        offlineMessage.setCreatedAt(LocalDateTime.now());
        offlineMessage.setExpireAt(LocalDateTime.now().plusDays(7));
        offlineMessage.setStatus(0);

        offlineMessageMapper.insert(offlineMessage);
    }

    private AppMessage convertToAppMessage(OfflineMessage offlineMessage) {
        AppMessage appMessage = JSON.parseObject(offlineMessage.getContent(), AppMessage.class);
        if (appMessage != null) {
            return appMessage;
        }

        appMessage = new AppMessage();
        appMessage.setMessageId(offlineMessage.getMessageId());
        appMessage.setSessionId(offlineMessage.getSessionId());
        appMessage.setSeq(offlineMessage.getSeq());
        appMessage.setType(offlineMessage.getMessageType());
        appMessage.setBody(JSON.parseObject(offlineMessage.getContent()));
        appMessage.setSendUserId(offlineMessage.getSenderId());
        appMessage.setUserName(offlineMessage.getSenderName());
        appMessage.setAvatar(offlineMessage.getSenderAvatar());
        return appMessage;
    }

    private boolean checkPullLimit(Long userId) {
        String key = PULL_LIMIT_KEY_PREFIX + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == 1) {
                redisTemplate.expire(key, 1, TimeUnit.SECONDS);
            }
            return count <= PULL_LIMIT_PER_SECOND;
        } catch (Exception e) {
            log.error("检查离线拉取限流失败, userId={}", userId, e);
            return true;
        }
    }

    private boolean isRedisHotRangeCovered(Long userId, String sessionId, long lastSeq) {
        Long minHotSeq = getMinHotSeq(userId, sessionId);
        return minHotSeq != null && lastSeq >= minHotSeq - 1;
    }

    private Long getMinHotSeq(Long userId, String sessionId) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().rangeWithScores(buildZSetKey(userId, sessionId), 0, 0);
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }

        ZSetOperations.TypedTuple<String> tuple = tuples.iterator().next();
        Double score = tuple != null ? tuple.getScore() : null;
        return score != null ? score.longValue() : null;
    }

    private long normalizeSeq(Long seq) {
        return seq != null ? seq : 0L;
    }

    private double minScoreExclusive(Long lastSeq) {
        return lastSeq == null ? Double.NEGATIVE_INFINITY : lastSeq.doubleValue() + SCORE_EPSILON;
    }

    private Comparator<AppMessage> buildOfflineMessageComparator() {
        return Comparator
                .comparing(AppMessage::getCreatedAt, Comparator.nullsLast(String::compareTo))
                .thenComparing(AppMessage::getSessionId, Comparator.nullsLast(String::compareTo))
                .thenComparingLong(msg -> msg.getSeq() != null ? msg.getSeq() : 0L);
    }

    private String buildZSetKey(Long userId, String sessionId) {
        return OFFLINE_MSG_PREFIX + userId + ":" + sessionId;
    }

    private static String snippet(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= 120 ? raw : raw.substring(0, 120) + "...";
    }
}
