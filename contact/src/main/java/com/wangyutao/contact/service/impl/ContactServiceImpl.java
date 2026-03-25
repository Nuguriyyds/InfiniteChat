package com.wangyutao.contact.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wangyutao.contact.exception.ServiceException;
import com.wangyutao.contact.mapper.AiAssistantMapper;
import com.wangyutao.contact.mapper.ContactMapper;
import com.wangyutao.contact.mapper.ContactRequestMapper;
import com.wangyutao.contact.model.dto.AddContactRequest;
import com.wangyutao.contact.model.dto.ContactVO;
import com.wangyutao.contact.model.entity.AiAssistant;
import com.wangyutao.contact.model.entity.Contact;
import com.wangyutao.contact.model.entity.ContactRequest;
import com.wangyutao.contact.feign.MessagingClient;
import com.wangyutao.contact.feign.RtcPushClient;
import com.wangyutao.contact.service.ContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactServiceImpl implements ContactService {

    private final ContactMapper contactMapper;
    private final ContactRequestMapper contactRequestMapper;
    private final AiAssistantMapper aiAssistantMapper;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final RtcPushClient rtcPushClient;
    private final MessagingClient messagingClient;

    private static final String CONTACT_CACHE_PREFIX = "contact:list:";
    private static final Long AI_ASSISTANT_BASE_ID = 1000000000L;

    @Override
    public void addContact(AddContactRequest request) {
        Long userId = request.getUserId();
        Long contactId = request.getContactId();

        if (userId.equals(contactId)) {
            throw new ServiceException("不能添加自己为好友");
        }

        // 检查是否已经是好友
        Contact exist = contactMapper.selectOne(new QueryWrapper<Contact>()
                .eq("user_id", userId)
                .eq("contact_id", contactId)
                .eq("status", 1));
        if (exist != null) {
            throw new ServiceException("已经是好友了");
        }

        // 检查是否已经发过申请且待处理
        ContactRequest pending = contactRequestMapper.selectOne(new QueryWrapper<ContactRequest>()
                .eq("from_user_id", userId)
                .eq("to_user_id", contactId)
                .eq("status", 0));
        if (pending != null) {
            throw new ServiceException("已发送过好友申请，请等待对方处理");
        }

        // 创建好友申请
        ContactRequest cr = new ContactRequest();
        cr.setFromUserId(userId);
        cr.setToUserId(contactId);
        cr.setRemark(request.getRemark());
        cr.setStatus(0);
        cr.setCreatedAt(LocalDateTime.now());
        cr.setUpdatedAt(LocalDateTime.now());
        contactRequestMapper.insert(cr);

        log.info("好友申请已发送, from={}, to={}", userId, contactId);

        pushFriendApplicationNotification(cr);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptContact(Long userId, Long requestId) {
        ContactRequest cr = contactRequestMapper.selectById(requestId);
        if (cr == null) {
            throw new ServiceException("申请不存在");
        }
        if (!cr.getToUserId().equals(userId)) {
            throw new ServiceException("无权操作此申请");
        }
        if (cr.getStatus() != 0) {
            throw new ServiceException("该申请已处理");
        }

        // 更新申请状态
        cr.setStatus(1);
        cr.setUpdatedAt(LocalDateTime.now());
        contactRequestMapper.updateById(cr);

        // 双向创建好友关系
        String lockKey = getLockKey(cr.getFromUserId(), cr.getToUserId());
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                throw new ServiceException("系统繁忙，请稍后重试");
            }

            LocalDateTime now = LocalDateTime.now();

            Contact contactAB = new Contact();
            contactAB.setUserId(cr.getFromUserId());
            contactAB.setContactId(cr.getToUserId());
            contactAB.setContactType(0);
            contactAB.setRemark(cr.getRemark());
            contactAB.setStatus(1);
            contactAB.setIsPinned(0);
            contactAB.setCreatedAt(now);
            contactAB.setUpdatedAt(now);
            contactMapper.insert(contactAB);

            Contact contactBA = new Contact();
            contactBA.setUserId(cr.getToUserId());
            contactBA.setContactId(cr.getFromUserId());
            contactBA.setContactType(0);
            contactBA.setStatus(1);
            contactBA.setIsPinned(0);
            contactBA.setCreatedAt(now);
            contactBA.setUpdatedAt(now);
            contactMapper.insert(contactBA);

            clearCache(cr.getFromUserId());
            clearCache(cr.getToUserId());

            log.info("好友申请已同意, from={}, to={}", cr.getFromUserId(), cr.getToUserId());

            createSingleSession(cr.getFromUserId(), cr.getToUserId());

            pushNewSessionNotification(cr.getFromUserId(), cr.getToUserId());
            pushNewSessionNotification(cr.getToUserId(), cr.getFromUserId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("操作失败");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void rejectContact(Long userId, Long requestId) {
        ContactRequest cr = contactRequestMapper.selectById(requestId);
        if (cr == null) {
            throw new ServiceException("申请不存在");
        }
        if (!cr.getToUserId().equals(userId)) {
            throw new ServiceException("无权操作此申请");
        }
        if (cr.getStatus() != 0) {
            throw new ServiceException("该申请已处理");
        }

        cr.setStatus(2);
        cr.setUpdatedAt(LocalDateTime.now());
        contactRequestMapper.updateById(cr);

        log.info("好友申请已拒绝, from={}, to={}", cr.getFromUserId(), cr.getToUserId());
    }

    @Override
    public List<ContactRequest> getPendingRequests(Long userId) {
        return contactRequestMapper.selectList(new QueryWrapper<ContactRequest>()
                .eq("to_user_id", userId)
                .eq("status", 0)
                .orderByDesc("created_at"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteContact(Long userId, Long contactId) {
        contactMapper.update(null, new UpdateWrapper<Contact>()
                .eq("user_id", userId)
                .eq("contact_id", contactId)
                .set("status", 0)
                .set("updated_at", LocalDateTime.now()));

        contactMapper.update(null, new UpdateWrapper<Contact>()
                .eq("user_id", contactId)
                .eq("contact_id", userId)
                .set("status", 0)
                .set("updated_at", LocalDateTime.now()));

        clearCache(userId);
        clearCache(contactId);
        log.info("删除好友成功, userId={}, contactId={}", userId, contactId);
    }

    @Override
    public void blockContact(Long userId, Long contactId) {
        contactMapper.update(null, new UpdateWrapper<Contact>()
                .eq("user_id", userId)
                .eq("contact_id", contactId)
                .set("status", 2)
                .set("updated_at", LocalDateTime.now()));

        clearCache(userId);
        log.info("拉黑好友成功, userId={}, contactId={}", userId, contactId);
    }

    @Override
    public void unblockContact(Long userId, Long contactId) {
        contactMapper.update(null, new UpdateWrapper<Contact>()
                .eq("user_id", userId)
                .eq("contact_id", contactId)
                .set("status", 1)
                .set("updated_at", LocalDateTime.now()));

        clearCache(userId);
        log.info("取消拉黑成功, userId={}, contactId={}", userId, contactId);
    }

    @Override
    public List<ContactVO> getContactList(Long userId, Integer contactType) {
        String cacheKey = CONTACT_CACHE_PREFIX + userId;
        if (contactType != null) {
            cacheKey += ":" + contactType;
        }

        String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            return JSON.parseArray(cachedJson, ContactVO.class);
        }

        QueryWrapper<Contact> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .in("status", 1, 2)
                .orderByDesc("is_pinned")
                .orderByDesc("updated_at");

        if (contactType != null) {
            wrapper.eq("contact_type", contactType);
        }

        List<Contact> contacts = contactMapper.selectList(wrapper);

        List<ContactVO> voList = contacts.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(voList), Duration.ofHours(1));
        return voList;
    }

    @Override
    public List<ContactVO> searchContact(Long userId, String keyword) {
        List<ContactVO> allContacts = getContactList(userId, null);

        if (keyword == null || keyword.trim().isEmpty()) {
            return allContacts;
        }

        return allContacts.stream()
                .filter(vo -> (vo.getRemark() != null && vo.getRemark().contains(keyword))
                        || (vo.getNickname() != null && vo.getNickname().contains(keyword)))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createAiAssistant(Long userId) {
        Long assistantId = AI_ASSISTANT_BASE_ID + userId;

        AiAssistant existAssistant = aiAssistantMapper.selectOne(
                new QueryWrapper<AiAssistant>().eq("user_id", userId));
        if (existAssistant != null) {
            log.info("AI 助手已存在, userId={}", userId);
            return;
        }

        AiAssistant aiAssistant = new AiAssistant();
        aiAssistant.setUserId(userId);
        aiAssistant.setAssistantId(assistantId);
        aiAssistant.setAssistantName("AI 小助手");
        aiAssistant.setAssistantAvatar("http://47.115.130.44/img/avatar/AI_ASSISTANT.jpg");
        aiAssistant.setModelType("gpt-3.5-turbo");
        aiAssistant.setPersonality("{\"role\":\"helpful_assistant\"}");
        aiAssistant.setContextLimit(10);
        aiAssistant.setStatus(1);
        aiAssistant.setCreatedAt(LocalDateTime.now());
        aiAssistant.setUpdatedAt(LocalDateTime.now());
        aiAssistantMapper.insert(aiAssistant);

        Contact contact = new Contact();
        contact.setUserId(userId);
        contact.setContactId(assistantId);
        contact.setContactType(1);
        contact.setStatus(1);
        contact.setIsPinned(1);
        contact.setCreatedAt(LocalDateTime.now());
        contact.setUpdatedAt(LocalDateTime.now());
        contactMapper.insert(contact);

        log.info("创建 AI 助手成功, userId={}, assistantId={}", userId, assistantId);
    }

    @Override
    public Map<Long, Boolean> checkFriends(Long userId, List<Long> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return new HashMap<>();
        }
        List<Contact> friends = contactMapper.selectList(new QueryWrapper<Contact>()
                .eq("user_id", userId)
                .in("contact_id", contactIds)
                .eq("status", 1));
        java.util.Set<Long> friendIdSet = friends.stream()
                .map(Contact::getContactId)
                .collect(Collectors.toSet());
        Map<Long, Boolean> result = new HashMap<>();
        for (Long cid : contactIds) {
            result.put(cid, friendIdSet.contains(cid));
        }
        return result;
    }

    private String getLockKey(Long userId1, Long userId2) {
        Long minId = Math.min(userId1, userId2);
        Long maxId = Math.max(userId1, userId2);
        return "lock:contact:" + minId + ":" + maxId;
    }

    private void clearCache(Long userId) {
        String pattern = CONTACT_CACHE_PREFIX + userId + "*";
        redisTemplate.delete(redisTemplate.keys(pattern));
    }

    private ContactVO convertToVO(Contact contact) {
        ContactVO vo = new ContactVO();
        vo.setContactId(contact.getContactId());
        vo.setContactType(contact.getContactType());
        vo.setRemark(contact.getRemark());
        vo.setStatus(contact.getStatus());
        vo.setIsPinned(contact.getIsPinned());

        if (contact.getContactType() == 1) {
            vo.setNickname("AI 小助手");
            vo.setAvatar("http://47.115.130.44/img/avatar/AI_ASSISTANT.jpg");
        } else {
            vo.setNickname("用户" + contact.getContactId());
            vo.setAvatar("http://47.115.130.44/img/avatar/DEFAULT.jpg");
        }

        return vo;
    }

    private void pushFriendApplicationNotification(ContactRequest cr) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("fromUserId", cr.getFromUserId());
            notification.put("applyUserName", "用户" + cr.getFromUserId());
            notification.put("avatar", "http://47.115.130.44/img/avatar/DEFAULT.jpg");
            notification.put("remark", cr.getRemark());
            notification.put("requestId", cr.getId());

            rtcPushClient.pushFriendApplication(cr.getToUserId(), notification);
            log.info("好友申请推送成功, to={}", cr.getToUserId());
        } catch (Exception e) {
            log.warn("好友申请推送失败（不影响申请本身）, to={}", cr.getToUserId(), e);
        }
    }

    private void pushNewSessionNotification(Long userId, Long contactId) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("userId", contactId);
            notification.put("sessionType", 0);
            notification.put("sessionName", "用户" + contactId);
            notification.put("avatar", "http://47.115.130.44/img/avatar/DEFAULT.jpg");

            rtcPushClient.pushNewSession(userId, notification);
            log.info("新会话通知推送成功, userId={}, contactId={}", userId, contactId);
        } catch (Exception e) {
            log.warn("新会话通知推送失败（不影响好友关系）, userId={}", userId, e);
        }
    }

    private void createSingleSession(Long userIdA, Long userIdB) {
        try {
            Map<String, Object> result = messagingClient.createSingleSession(userIdA, userIdB);
            if (result != null) {
                log.info("单聊会话创建成功, userA={}, userB={}, result={}", userIdA, userIdB, result);
            }
        } catch (Exception e) {
            log.warn("单聊会话创建失败（不影响好友关系）, userA={}, userB={}", userIdA, userIdB, e);
        }
    }
}
