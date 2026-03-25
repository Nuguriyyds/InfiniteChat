package com.wangyutao.messaging.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wangyutao.messaging.common.Result;
import com.wangyutao.messaging.common.ResultGenerator;
import com.wangyutao.messaging.feign.ContactClient;
import com.wangyutao.messaging.model.dto.CreateGroupRequest;
import com.wangyutao.messaging.model.entity.Session;
import com.wangyutao.messaging.model.entity.User;
import com.wangyutao.messaging.model.entity.UserSession;
import com.wangyutao.messaging.service.SessionService;
import com.wangyutao.messaging.service.UserService;
import com.wangyutao.messaging.service.UserSessionService;
import com.wangyutao.messaging.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/message/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final UserSessionService userSessionService;
    private final UserService userService;
    private final ContactClient contactClient;
    private final IdGenerator idGenerator;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_SESSION_MEMBERS_PREFIX = "im:cache:session:members:";

    /**
     * 创建单聊会话（好友关系建立时由 Contact 模块调用）
     */
    @PostMapping("/createSingle")
    public Result<Map<String, String>> createSingleSession(
            @RequestParam("userIdA") Long userIdA,
            @RequestParam("userIdB") Long userIdB) {

        String sessionId = "single_" + Math.min(userIdA, userIdB) + "_" + Math.max(userIdA, userIdB);

        Session existing = sessionService.getById(sessionId);
        if (existing != null) {
            log.info("单聊会话已存在, sessionId={}", sessionId);
            Map<String, String> result = new HashMap<>();
            result.put("sessionId", sessionId);
            return ResultGenerator.genSuccessResult(result);
        }

        Date now = new Date();

        Session session = new Session()
                .setSessionId(sessionId)
                .setSessionType(1)
                .setCreatorId(userIdA)
                .setCreateTime(now)
                .setUpdateTime(now);
        sessionService.save(session);

        UserSession usA = new UserSession()
                .setId(idGenerator.nextId())
                .setSessionId(sessionId)
                .setUserId(userIdA)
                .setRoleType(3)
                .setStatus(1)
                .setCreateTime(now);
        userSessionService.save(usA);

        UserSession usB = new UserSession()
                .setId(idGenerator.nextId())
                .setSessionId(sessionId)
                .setUserId(userIdB)
                .setRoleType(3)
                .setStatus(1)
                .setCreateTime(now);
        userSessionService.save(usB);

        log.info("单聊会话创建成功, sessionId={}, userA={}, userB={}", sessionId, userIdA, userIdB);

        Map<String, String> result = new HashMap<>();
        result.put("sessionId", sessionId);
        return ResultGenerator.genSuccessResult(result);
    }

    /**
     * 创建群聊
     */
    @PostMapping("/createGroup")
    public Result<Map<String, Object>> createGroup(
            @RequestHeader(value = "X-User-Id") Long creatorId,
            @Valid @RequestBody CreateGroupRequest request) {

        List<Long> memberIds = request.getMemberIds();
        if (memberIds.contains(creatorId)) {
            memberIds = memberIds.stream().filter(id -> !id.equals(creatorId)).collect(Collectors.toList());
        }
        if (memberIds.isEmpty()) {
            return ResultGenerator.genFailResult("至少需要邀请一位成员");
        }

        // Feign 校验好友关系
        try {
            Map<String, Object> checkResult = contactClient.checkFriends(creatorId, memberIds);
            if (checkResult != null && checkResult.get("data") != null) {
                @SuppressWarnings("unchecked")
                Map<String, Boolean> friendMap = (Map<String, Boolean>) checkResult.get("data");
                List<Long> notFriends = new ArrayList<>();
                for (Long mid : memberIds) {
                    Boolean isFriend = friendMap.get(String.valueOf(mid));
                    if (isFriend == null || !isFriend) {
                        notFriends.add(mid);
                    }
                }
                if (!notFriends.isEmpty()) {
                    return ResultGenerator.genFailResult("以下用户不是你的好友: " + notFriends);
                }
            }
        } catch (Exception e) {
            log.warn("好友关系校验失败，降级跳过, creatorId={}, cause={}", creatorId, e.getMessage());
        }

        String sessionId = "group_" + idGenerator.nextId();

        // 群名处理
        String groupName = request.getName();
        if (groupName == null || groupName.trim().isEmpty()) {
            List<Long> allUserIds = new ArrayList<>();
            allUserIds.add(creatorId);
            allUserIds.addAll(memberIds);
            List<User> users = userService.listByIds(allUserIds);
            Map<Long, String> nameMap = users.stream()
                    .collect(Collectors.toMap(User::getUserId, u -> u.getUserName() != null ? u.getUserName() : "用户" + u.getUserId()));
            String creatorName = nameMap.getOrDefault(creatorId, "用户" + creatorId);
            String memberNames = memberIds.stream()
                    .map(id -> nameMap.getOrDefault(id, "用户" + id))
                    .collect(Collectors.joining("、"));
            groupName = creatorName + "、" + memberNames + "的群聊";
            if (groupName.length() > 30) {
                groupName = groupName.substring(0, 27) + "...";
            }
        }

        Date now = new Date();

        Session session = new Session()
                .setSessionId(sessionId)
                .setSessionType(2)
                .setName(groupName)
                .setCreatorId(creatorId)
                .setCreateTime(now)
                .setUpdateTime(now);
        sessionService.save(session);

        // 创建者 - 群主
        UserSession ownerUs = new UserSession()
                .setId(idGenerator.nextId())
                .setSessionId(sessionId)
                .setUserId(creatorId)
                .setRoleType(1)
                .setStatus(1)
                .setCreateTime(now);
        userSessionService.save(ownerUs);

        // 批量插入成员
        for (Long memberId : memberIds) {
            UserSession memberUs = new UserSession()
                    .setId(idGenerator.nextId())
                    .setSessionId(sessionId)
                    .setUserId(memberId)
                    .setRoleType(3)
                    .setStatus(1)
                    .setCreateTime(now);
            userSessionService.save(memberUs);
        }

        log.info("群聊创建成功, sessionId={}, creator={}, members={}", sessionId, creatorId, memberIds);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("name", groupName);
        return ResultGenerator.genSuccessResult(result);
    }

    /**
     * 加入群聊
     */
    @PostMapping("/joinGroup")
    public Result<Void> joinGroup(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("userId") Long userId) {

        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<UserSession>()
            .eq(UserSession::getSessionId, sessionId)
            .eq(UserSession::getUserId, userId);
        UserSession existing = userSessionService.getOne(wrapper);

        Date now = new Date();
        if (existing != null) {
            userSessionService.update(new LambdaUpdateWrapper<UserSession>()
                .eq(UserSession::getSessionId, sessionId)
                .eq(UserSession::getUserId, userId)
                .set(UserSession::getStatus, 1));
        } else {
            UserSession us = new UserSession()
                .setId(idGenerator.nextId())
                .setSessionId(sessionId)
                .setUserId(userId)
                .setRoleType(3)
                .setStatus(1)
                .setCreateTime(now);
            userSessionService.save(us);
        }
        redisTemplate.delete(CACHE_SESSION_MEMBERS_PREFIX + sessionId);
        log.info("加入群聊成功, sessionId={}, userId={}", sessionId, userId);
        return ResultGenerator.genSuccessResult(null);
    }

    /**
     * 退出群聊
     */
    @PostMapping("/leaveGroup")
    public Result<Void> leaveGroup(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("userId") Long userId) {

        userSessionService.update(new LambdaUpdateWrapper<UserSession>()
            .eq(UserSession::getSessionId, sessionId)
            .eq(UserSession::getUserId, userId)
            .set(UserSession::getStatus, 3));
        redisTemplate.delete(CACHE_SESSION_MEMBERS_PREFIX + sessionId);
        log.info("退出群聊成功, sessionId={}, userId={}", sessionId, userId);
        return ResultGenerator.genSuccessResult(null);
    }

    /**
     * 踢出群聊（仅群主/管理员可操作，权限校验由上层负责）
     */
    @PostMapping("/kickMember")
    public Result<Void> kickMember(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("operatorId") Long operatorId,
            @RequestParam("targetUserId") Long targetUserId) {

        LambdaQueryWrapper<UserSession> operatorWrapper = new LambdaQueryWrapper<UserSession>()
            .eq(UserSession::getSessionId, sessionId)
            .eq(UserSession::getUserId, operatorId)
            .eq(UserSession::getStatus, 1);
        UserSession operator = userSessionService.getOne(operatorWrapper);
        if (operator == null || operator.getRoleType() == 3) {
            return ResultGenerator.genFailResult("无权限执行此操作");
        }

        userSessionService.update(new LambdaUpdateWrapper<UserSession>()
            .eq(UserSession::getSessionId, sessionId)
            .eq(UserSession::getUserId, targetUserId)
            .set(UserSession::getStatus, 2));
        redisTemplate.delete(CACHE_SESSION_MEMBERS_PREFIX + sessionId);
        log.info("踢出群成员成功, sessionId={}, operatorId={}, targetUserId={}", sessionId, operatorId, targetUserId);
        return ResultGenerator.genSuccessResult(null);
    }
}
