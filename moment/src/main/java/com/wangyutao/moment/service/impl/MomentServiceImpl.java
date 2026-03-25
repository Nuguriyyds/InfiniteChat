package com.wangyutao.moment.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.moment.constants.MomentConstants;
import com.wangyutao.moment.exception.ServiceException;
import com.wangyutao.moment.mapper.MomentCommentMapper;
import com.wangyutao.moment.mapper.MomentLikeMapper;
import com.wangyutao.moment.mapper.MomentMapper;
import com.wangyutao.moment.mapper.UserMapper;
import com.wangyutao.moment.model.dto.CommentMomentRequest;
import com.wangyutao.moment.model.dto.LikeMomentRequest;
import com.wangyutao.moment.model.dto.PublishMomentRequest;
import com.wangyutao.moment.model.entity.Moment;
import com.wangyutao.moment.model.entity.MomentComment;
import com.wangyutao.moment.model.entity.MomentLike;
import com.wangyutao.moment.model.entity.User;
import com.wangyutao.moment.model.vo.CommentVO;
import com.wangyutao.moment.model.vo.MomentVO;
import com.wangyutao.moment.service.MomentNotifyService;
import com.wangyutao.moment.service.MomentService;
import com.wangyutao.moment.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MomentServiceImpl extends ServiceImpl<MomentMapper, Moment> implements MomentService {

    private final MomentMapper momentMapper;
    private final MomentLikeMapper momentLikeMapper;
    private final MomentCommentMapper momentCommentMapper;
    private final UserMapper userMapper;
    private final IdGenerator idGenerator;
    private final StringRedisTemplate redisTemplate;
    private final MomentNotifyService momentNotifyService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishMoment(Long userId, PublishMomentRequest request) {
        validatePublishRequest(request);

        Moment moment = new Moment();
        moment.setMomentId(idGenerator.nextId());
        moment.setUserId(userId);
        moment.setContent(request.getContent());
        moment.setImages(request.getImages() != null ? JSONUtil.toJsonStr(request.getImages()) : null);
        moment.setLikeCount(0);
        moment.setCommentCount(0);
        moment.setCreatedAt(LocalDateTime.now());
        moment.setUpdatedAt(LocalDateTime.now());

        this.save(moment);
        log.info("用户 {} 发布朋友圈成功，momentId: {}", userId, moment.getMomentId());
        return moment.getMomentId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void likeMoment(LikeMomentRequest request) {
        Long momentId = request.getMomentId();
        Long userId = request.getUserId();

        String likeKey = MomentConstants.MOMENT_LIKE_KEY_PREFIX + momentId;
        String countKey = MomentConstants.MOMENT_LIKE_COUNT_KEY_PREFIX + momentId;

        Long added = redisTemplate.opsForSet().add(likeKey, String.valueOf(userId));
        if (added != null && added == 0) {
            throw new ServiceException("您已经点过赞了");
        }

        redisTemplate.expire(likeKey, Duration.ofHours(MomentConstants.MOMENT_CACHE_EXPIRE_HOURS));
        redisTemplate.opsForValue().increment(countKey);
        redisTemplate.expire(countKey, Duration.ofHours(MomentConstants.MOMENT_CACHE_EXPIRE_HOURS));

        MomentLike momentLike = new MomentLike();
        momentLike.setLikeId(idGenerator.nextId());
        momentLike.setMomentId(momentId);
        momentLike.setUserId(userId);
        momentLike.setCreatedAt(LocalDateTime.now());
        momentLikeMapper.insert(momentLike);

        momentMapper.incrementLikeCount(momentId);

        Moment moment = this.getById(momentId);
        if (moment != null && !moment.getUserId().equals(userId)) {
            momentNotifyService.pushLikeNotification(moment.getUserId(), userId, momentId);
        }

        log.info("用户 {} 点赞朋友圈 {} 成功", userId, momentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlikeMoment(LikeMomentRequest request) {
        Long momentId = request.getMomentId();
        Long userId = request.getUserId();

        String likeKey = MomentConstants.MOMENT_LIKE_KEY_PREFIX + momentId;
        String countKey = MomentConstants.MOMENT_LIKE_COUNT_KEY_PREFIX + momentId;

        Long removed = redisTemplate.opsForSet().remove(likeKey, String.valueOf(userId));
        if (removed == null || removed == 0) {
            throw new ServiceException("您还未点赞");
        }

        redisTemplate.opsForValue().decrement(countKey);

        LambdaQueryWrapper<MomentLike> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MomentLike::getMomentId, momentId)
                .eq(MomentLike::getUserId, userId);
        momentLikeMapper.delete(wrapper);

        momentMapper.decrementLikeCount(momentId);

        log.info("用户 {} 取消点赞朋友圈 {} 成功", userId, momentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void commentMoment(CommentMomentRequest request) {
        validateCommentRequest(request);

        MomentComment comment = new MomentComment();
        comment.setCommentId(idGenerator.nextId());
        comment.setMomentId(request.getMomentId());
        comment.setUserId(request.getUserId());
        comment.setContent(request.getContent());
        comment.setReplyToUserId(request.getReplyToUserId());
        comment.setCreatedAt(LocalDateTime.now());

        momentCommentMapper.insert(comment);
        momentMapper.incrementCommentCount(request.getMomentId());

        Moment moment = this.getById(request.getMomentId());
        if (moment != null && !moment.getUserId().equals(request.getUserId())) {
            momentNotifyService.pushCommentNotification(
                    moment.getUserId(),
                    request.getUserId(),
                    request.getMomentId(),
                    request.getContent()
            );
        }

        log.info("用户 {} 评论朋友圈 {} 成功", request.getUserId(), request.getMomentId());
    }

    @Override
    public List<MomentVO> getFriendMoments(Long userId, Integer pageNum, Integer pageSize) {
        int offset = (pageNum - 1) * pageSize;
        List<Moment> moments = momentMapper.selectFriendMoments(userId, offset, pageSize);

        if (moments == null || moments.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> userIds = moments.stream().map(Moment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = getUserMap(userIds);

        List<Long> momentIds = moments.stream().map(Moment::getMomentId).collect(Collectors.toList());
        Map<Long, Boolean> likedMap = batchCheckIfLiked(momentIds, userId);
        Map<Long, List<CommentVO>> commentsMap = batchTopComments(momentIds, 3);

        return moments.stream().map(moment -> {
            MomentVO vo = convertToVO(moment, userMap);
            vo.setIsLiked(Boolean.TRUE.equals(likedMap.get(moment.getMomentId())));
            vo.setComments(commentsMap.getOrDefault(moment.getMomentId(), Collections.emptyList()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public MomentVO getMomentDetail(Long momentId, Long currentUserId) {
        Moment moment = this.getById(momentId);
        if (moment == null) {
            throw new ServiceException("朋友圈不存在");
        }

        Map<Long, User> userMap = getUserMap(Collections.singleton(moment.getUserId()));
        MomentVO vo = convertToVO(moment, userMap);
        vo.setIsLiked(checkIfLiked(momentId, currentUserId));
        vo.setComments(getTopComments(momentId, 10));

        return vo;
    }

    private void validatePublishRequest(PublishMomentRequest request) {
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            if (request.getImages() == null || request.getImages().isEmpty()) {
                throw new ServiceException("内容和图片不能同时为空");
            }
        }

        if (request.getContent() != null && request.getContent().length() > MomentConstants.MAX_CONTENT_LENGTH) {
            throw new ServiceException("内容长度不能超过" + MomentConstants.MAX_CONTENT_LENGTH + "字");
        }

        if (request.getImages() != null && request.getImages().size() > MomentConstants.MAX_IMAGES_COUNT) {
            throw new ServiceException("图片数量不能超过" + MomentConstants.MAX_IMAGES_COUNT + "张");
        }
    }

    private void validateCommentRequest(CommentMomentRequest request) {
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new ServiceException("评论内容不能为空");
        }

        if (request.getContent().length() > 500) {
            throw new ServiceException("评论内容不能超过500字");
        }
    }

    private Boolean checkIfLiked(Long momentId, Long userId) {
        String likeKey = MomentConstants.MOMENT_LIKE_KEY_PREFIX + momentId;
        Boolean isMember = redisTemplate.opsForSet().isMember(likeKey, String.valueOf(userId));
        
        if (isMember == null || !isMember) {
            int count = momentLikeMapper.existsLike(momentId, userId);
            return count > 0;
        }
        
        return true;
    }

    private List<CommentVO> getTopComments(Long momentId, Integer limit) {
        List<MomentComment> comments = momentCommentMapper.selectTopComments(momentId, limit);
        if (comments == null || comments.isEmpty()) {
            return Collections.emptyList();
        }
        return buildCommentVOs(comments);
    }

    /**
     * 列表页：一次 SQL 取多条动态各自前 N 条评论，避免 N+1。
     */
    private Map<Long, List<CommentVO>> batchTopComments(List<Long> momentIds, int limit) {
        if (momentIds == null || momentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<MomentComment> all = momentCommentMapper.selectTopCommentsBatch(momentIds, limit);
        Map<Long, List<CommentVO>> out = new LinkedHashMap<>();
        for (Long id : momentIds) {
            out.put(id, new ArrayList<>());
        }
        if (all == null || all.isEmpty()) {
            return out;
        }
        Map<Long, List<MomentComment>> grouped = all.stream()
                .collect(Collectors.groupingBy(MomentComment::getMomentId, LinkedHashMap::new, Collectors.toList()));
        Set<Long> commentUserIds = all.stream()
                .flatMap(c -> {
                    Set<Long> ids = new HashSet<>();
                    ids.add(c.getUserId());
                    if (c.getReplyToUserId() != null) {
                        ids.add(c.getReplyToUserId());
                    }
                    return ids.stream();
                })
                .collect(Collectors.toSet());
        Map<Long, User> commentUserMap = getUserMap(commentUserIds);
        for (Map.Entry<Long, List<MomentComment>> e : grouped.entrySet()) {
            List<CommentVO> vos = e.getValue().stream()
                    .map(c -> toCommentVO(c, commentUserMap))
                    .collect(Collectors.toList());
            out.put(e.getKey(), vos);
        }
        return out;
    }

    private List<CommentVO> buildCommentVOs(List<MomentComment> comments) {
        Set<Long> userIds = comments.stream()
                .flatMap(c -> {
                    Set<Long> ids = new HashSet<>();
                    ids.add(c.getUserId());
                    if (c.getReplyToUserId() != null) {
                        ids.add(c.getReplyToUserId());
                    }
                    return ids.stream();
                })
                .collect(Collectors.toSet());
        Map<Long, User> userMap = getUserMap(userIds);
        return comments.stream().map(c -> toCommentVO(c, userMap)).collect(Collectors.toList());
    }

    private CommentVO toCommentVO(MomentComment comment, Map<Long, User> userMap) {
        CommentVO vo = new CommentVO();
        vo.setCommentId(comment.getCommentId());
        vo.setUserId(comment.getUserId());
        vo.setContent(comment.getContent());
        vo.setReplyToUserId(comment.getReplyToUserId());
        vo.setCreatedAt(comment.getCreatedAt());
        User user = userMap.get(comment.getUserId());
        if (user != null) {
            vo.setUsername(user.getUserName());
            vo.setAvatar(user.getAvatar());
        }
        if (comment.getReplyToUserId() != null) {
            User replyToUser = userMap.get(comment.getReplyToUserId());
            if (replyToUser != null) {
                vo.setReplyToUsername(replyToUser.getUserName());
            }
        }
        return vo;
    }

    /**
     * Pipeline 查 Redis，未命中再批量查库，避免列表页每条动态 2 次 IO。
     */
    private Map<Long, Boolean> batchCheckIfLiked(List<Long> momentIds, Long userId) {
        Map<Long, Boolean> result = new HashMap<>(momentIds.size());
        if (momentIds == null || momentIds.isEmpty()) {
            return result;
        }
        final String uid = String.valueOf(userId);
        @SuppressWarnings("unchecked")
        List<Object> piped = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            RedisSerializer<String> ser = redisTemplate.getStringSerializer();
            byte[] uidBytes = ser.serialize(uid);
            for (Long mid : momentIds) {
                byte[] keyBytes = ser.serialize(MomentConstants.MOMENT_LIKE_KEY_PREFIX + mid);
                connection.sIsMember(keyBytes, uidBytes);
            }
            return null;
        });
        List<Long> needDb = new ArrayList<>();
        for (int i = 0; i < momentIds.size(); i++) {
            Long mid = momentIds.get(i);
            Object o = piped.get(i);
            boolean inRedis = o instanceof Boolean && (Boolean) o;
            if (inRedis) {
                result.put(mid, true);
            } else {
                needDb.add(mid);
            }
        }
        if (!needDb.isEmpty()) {
            List<Long> dbLiked = momentLikeMapper.selectLikedMomentIds(userId, needDb);
            Set<Long> dbSet = dbLiked != null ? new HashSet<>(dbLiked) : Collections.emptySet();
            for (Long mid : needDb) {
                result.put(mid, dbSet.contains(mid));
            }
        }
        return result;
    }

    private MomentVO convertToVO(Moment moment, Map<Long, User> userMap) {
        MomentVO vo = new MomentVO();
        vo.setMomentId(moment.getMomentId());
        vo.setUserId(moment.getUserId());
        vo.setContent(moment.getContent());
        vo.setCreatedAt(moment.getCreatedAt());

        if (moment.getImages() != null && !moment.getImages().isEmpty()) {
            vo.setImages(JSONUtil.toList(moment.getImages(), String.class));
        }

        String countKey = MomentConstants.MOMENT_LIKE_COUNT_KEY_PREFIX + moment.getMomentId();
        String countStr = redisTemplate.opsForValue().get(countKey);
        vo.setLikeCount(countStr != null ? Integer.parseInt(countStr) : moment.getLikeCount());
        vo.setCommentCount(moment.getCommentCount());

        User user = userMap.get(moment.getUserId());
        if (user != null) {
            vo.setUsername(user.getUserName());
            vo.setAvatar(user.getAvatar());
        }

        return vo;
    }

    private Map<Long, User> getUserMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<User> users = userMapper.selectBatchIds(userIds);
        return users.stream().collect(Collectors.toMap(User::getUserId, u -> u));
    }
}
