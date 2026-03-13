package com.wangyutao.messaging.service.impl;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wangyutao.messaging.exception.ServiceException;
import com.wangyutao.messaging.mapper.RedPacketReceiveMapper;
import com.wangyutao.messaging.model.dto.RedPacketResponse;
import com.wangyutao.messaging.model.dto.RedPacketUser;
import com.wangyutao.messaging.model.entity.RedPacket;
import com.wangyutao.messaging.model.entity.RedPacketReceive;
import com.wangyutao.messaging.model.entity.User;
import com.wangyutao.messaging.service.GetRedPacketService;
import com.wangyutao.messaging.service.RedPacketService;
import com.wangyutao.messaging.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;


import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetRedPacketServiceImpl implements GetRedPacketService {

    private final RedPacketService redPacketService;
    private final RedPacketReceiveMapper redPacketReceiveMapper;
    private final UserService userService;
    private final StringRedisTemplate redisTemplate;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");


    @Override
    public RedPacketResponse getRedPacketDetails(Long redPacketId, Integer pageNum, Integer pageSize) {
        // 🌟 P2-9: 增加 Redis 缓存，优化热点查询
        String cacheKey = "red_packet:detail:" + redPacketId + ":" + pageNum;
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        
        if (cachedJson != null && !cachedJson.isEmpty()) {
            try {
                return com.alibaba.fastjson.JSON.parseObject(cachedJson, RedPacketResponse.class);
            } catch (Exception e) {
                log.warn("Redis 缓存解析失败，降级查询 MySQL", e);
            }
        }
        
        // 缓存未命中，查询 MySQL
        RedPacketResponse response = queryFromDatabase(redPacketId, pageNum, pageSize);
        
        // 写入缓存，过期时间 1 小时
        try {
            redisTemplate.opsForValue().set(cacheKey, 
                com.alibaba.fastjson.JSON.toJSONString(response), 
                1, java.util.concurrent.TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败", e);
        }
        
        return response;
    }
    
    /**
     * 从数据库查询红包详情
     */
    private RedPacketResponse queryFromDatabase(Long redPacketId, Integer pageNum, Integer pageSize) {
        // 1. 查询红包本体
        RedPacket redPacket = redPacketService.getById(redPacketId);
        if (redPacket == null) {
            throw new ServiceException("红包不存在或已过期");
        }

        // 2. 查询发红包的人
        User sender = userService.getById(redPacket.getSenderId());
        if (sender == null) {
            throw new ServiceException("发红包用户状态异常");
        }

        // 3. 利用 MyBatis-Plus 分页插件，代替手动 offset 计算
        Page<RedPacketReceive> pageParam = new Page<>(pageNum, pageSize);
        Page<RedPacketReceive> receivePage = redPacketReceiveMapper.selectByRedPacketId(pageParam, redPacketId);
        List<RedPacketReceive> receiveRecords = receivePage.getRecords();

        // 4. O(1) 批量转化核心逻辑：消除 N+1
        List<RedPacketUser> userList = buildRedPacketUserList(receiveRecords);

        // 5. 组装返回结果
        return new RedPacketResponse(
                userList,
                sender.getUserName(),
                sender.getAvatar(),
                redPacket.getRedPacketWrapperText(),
                redPacket.getRedPacketType(),
                redPacket.getTotalAmount(),
                redPacket.getTotalCount(),
                redPacket.getRemainingAmount(),
                redPacket.getRemainingCount(),
                redPacket.getStatus()
        );
    }

    /**
     * 将领取流水转化为带有用户头像昵称的 DTO (批量 IN 查询 + 内存 Map)
     */
    private List<RedPacketUser> buildRedPacketUserList(List<RedPacketReceive> records) {
        if (CollectionUtils.isEmpty(records)) {
            return new ArrayList<>();
        }

        // 第一步：提取本页所有抢到红包的 userId，并去重
        List<Long> userIds = records.stream()
                .map(RedPacketReceive::getReceiverId)
                .distinct()
                .collect(Collectors.toList());

        // 第二步：1条 SQL 查出所有用户详情 (SELECT * FROM user WHERE id IN (...))
        List<User> users = userService.listByIds(userIds);

        // 第三步：在内存里建立 UserId -> User 字典，查询复杂度降为 O(1)
        // 假设 User 的主键或获取 ID 的方法叫 getUserId()，如果是 getId() 请自行替换
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));

        // 第四步：遍历流水组装 DTO
        return records.stream().map(receive -> {
            User user = userMap.get(receive.getReceiverId());

            String userName = user != null ? user.getUserName() : "已注销用户";
            String avatar = user != null ? user.getAvatar() : "";

            String formattedTime = receive.getReceivedAt() != null
                    ? receive.getReceivedAt().format(TIME_FORMATTER)
                    : "";
            return new RedPacketUser(userName, avatar, formattedTime, receive.getAmount());
        }).collect(Collectors.toList());
    }
}
