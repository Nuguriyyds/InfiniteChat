package com.wangyutao.authenticationservice.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangyutao.authenticationservice.constants.OSSConstant;
import com.wangyutao.authenticationservice.exception.BusinessException;
import com.wangyutao.authenticationservice.exception.ThrowUtils;
import com.wangyutao.authenticationservice.mapper.UserBalanceMapper;
import com.wangyutao.authenticationservice.mapper.UserMapper;
import com.wangyutao.authenticationservice.model.dto.UserLogOutRequest;
import com.wangyutao.authenticationservice.model.dto.UserLoginCodeRequest;
import com.wangyutao.authenticationservice.model.dto.UserLoginPwdRequest;
import com.wangyutao.authenticationservice.model.dto.UserRegisterRequest;
import com.wangyutao.authenticationservice.model.dto.UserSMSRequest;
import com.wangyutao.authenticationservice.model.dto.UserUpdateAvatarRequest;
import com.wangyutao.authenticationservice.model.entity.User;
import com.wangyutao.authenticationservice.model.entity.UserBalance;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import com.wangyutao.authenticationservice.model.enums.ErrorEnum;
import com.wangyutao.authenticationservice.model.enums.TimeOutEnum;
import com.wangyutao.authenticationservice.model.vo.UploadUrlResponse;
import com.wangyutao.authenticationservice.model.vo.UserVO;
import com.wangyutao.authenticationservice.service.JwtBlacklistService;
import com.wangyutao.authenticationservice.service.UserService;
import com.wangyutao.authenticationservice.utils.IdGenerator;
import com.wangyutao.authenticationservice.utils.JwtUtil;
import com.wangyutao.authenticationservice.utils.NicknameGeneratorUtil;
import com.wangyutao.authenticationservice.utils.OSSUtil;
import com.wangyutao.authenticationservice.utils.RandomNumUtil;
import com.wangyutao.authenticationservice.utils.RedisLockExecutor;
import com.wangyutao.authenticationservice.utils.ServiceInstanceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.mail.SimpleEmail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    @Qualifier("ioThreadPool")
    private final Executor ioThreadPool;

    private final UserBalanceMapper userBalanceMapper;
    private final UserMapper userMapper;
    private final OSSUtil ossUtil;
    private final StringRedisTemplate redisTemplate;
    private final ServiceInstanceUtil serviceInstanceUtil;
    private final RedisLockExecutor redisLockExecutor;
    private final IdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;
    private final JwtBlacklistService jwtBlacklistService;

    @Override
    public void sendClientSms(UserSMSRequest userSMSRequest) {
        String phone = userSMSRequest.getPhone();
        String code = new RandomNumUtil().getRandomNum();
        redisTemplate.opsForValue().set(phone, code, TimeOutEnum.CODE_TIME_OUT.getTimeOut(), TimeUnit.MINUTES);

        CompletableFuture.runAsync(() -> {
            try {
                String myDevEmail = "1533290655@qq.com";
                System.setProperty("mail.smtp.ssl.protocols", "TLSv1.2");

                SimpleEmail mail = new SimpleEmail();
                mail.setHostName("smtp.qq.com");
                mail.setAuthentication("1533290655@qq.com", "pgufqaqflhjniiji");
                mail.setFrom("1533290655@qq.com", "天幕IM-短信网关拦截");
                mail.setSSLOnConnect(true);
                mail.addTo(myDevEmail);
                mail.setSubject("【开发环境】短信验证码拦截通知");
                mail.setMsg("系统已拦截发往手机号[" + phone + "]的短信。\n您的验证码为: " + code + "\n(五分钟内有效)");
                mail.send();
                log.info("验证码已转发到开发邮箱: phone={}", phone);
            } catch (Exception e) {
                log.error("模拟短信发送失败: phone={}", phone, e);
                redisTemplate.delete(phone);
            }
        }, ioThreadPool);
    }

    @Override
    public UserVO userLoginPwd(UserLoginPwdRequest request, HttpServletResponse response) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", request.getPhone()));
        ThrowUtils.throwIf(user == null, ErrorEnum.LOGIN_ERROR);

        boolean isPwdMatch = BCrypt.checkpw(request.getPassword(), user.getPassword());
        ThrowUtils.throwIf(!isPwdMatch, ErrorEnum.LOGIN_ERROR);
        return buildUserVOAndAllocateNode(user);
    }

    @Override
    public UserVO userLoginCode(UserLoginCodeRequest request, HttpServletResponse response) {
        String redisCode = redisTemplate.opsForValue().get(request.getPhone());
        ThrowUtils.throwIf(redisCode == null || !redisCode.equals(request.getCode()), ErrorEnum.CODE_ERROR);

        User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", request.getPhone()));
        ThrowUtils.throwIf(user == null, ErrorEnum.LOGIN_ERROR);
        redisTemplate.delete(request.getPhone());

        return buildUserVOAndAllocateNode(user);
    }

    private UserVO buildUserVOAndAllocateNode(User user) {
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        buildNewTokenPair(userVO);

        String nettyUrl = serviceInstanceUtil.getWebSocketUrl(userVO.getUserId());
        ThrowUtils.throwIf(nettyUrl == null, ErrorEnum.SYSTEM_ERROR);
        userVO.setNettyUrl(nettyUrl);
        return userVO;
    }

    @Override
    public void userRegister(UserRegisterRequest request) {
        String phone = request.getPhone();
        String encryptedPassword = BCrypt.hashpw(request.getPassword());
        Long distributedUserId = idGenerator.nextId();
        String generatedNickname = new NicknameGeneratorUtil().generateNickname();
        String lockKey = "register:lock:" + phone;

        redisLockExecutor.executeWithLock(lockKey, 3000, 5000, () ->
                transactionTemplate.execute(status -> {
                    String redisCode = redisTemplate.opsForValue().get(phone);
                    ThrowUtils.throwIf(redisCode == null || !redisCode.equals(request.getCode()), ErrorEnum.CODE_ERROR);
                    ThrowUtils.throwIf(isReRegister(phone), ErrorEnum.REGISTER_ERROR);

                    User user = new User()
                            .setUserId(distributedUserId)
                            .setUserName(generatedNickname)
                            .setPhone(phone)
                            .setPassword(encryptedPassword);
                    ThrowUtils.throwIf(userMapper.insert(user) <= 0, ErrorEnum.MYSQL_ERROR);

                    UserBalance userBalance = new UserBalance()
                            .setUserId(user.getUserId())
                            .setBalance(BigDecimal.valueOf(1000))
                            .setUpdatedAt(LocalDateTime.now());
                    ThrowUtils.throwIf(userBalanceMapper.insert(userBalance) <= 0, ErrorEnum.MYSQL_ERROR);

                    redisTemplate.delete(phone);
                    return null;
                })
        );
    }

    private boolean isReRegister(String phone) {
        return userMapper.selectCount(new QueryWrapper<User>().eq("phone", phone)) > 0;
    }

    @Override
    public void updateAvatar(Long id, UserUpdateAvatarRequest request) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_id", id));
        ThrowUtils.throwIf(user == null, ErrorEnum.UPDATE_AVATAR_ERROR);

        user.setAvatar(request.avatarUrl);
        ThrowUtils.throwIf(userMapper.updateById(user) <= 0, ErrorEnum.UPDATE_AVATAR_ERROR);
    }

    @Override
    public UploadUrlResponse getUploadUrl(String fileName) {
        return new UploadUrlResponse()
                .setUploadUrl(ossUtil.uploadUrl(OSSConstant.BUCKET_NAME, fileName, OSSConstant.PICTURE_EXPIRE_TIME))
                .setDownloadUrl(ossUtil.downUrl(fileName));
    }

    @Override
    public void userLogout(UserLogOutRequest request) {
        String userIdStr = String.valueOf(request.getUserId());
        jwtBlacklistService.addToBlacklist(userIdStr);

        String luaScript =
                "local oldNodeId = redis.call('get', KEYS[2]); " +
                "redis.call('del', KEYS[1]); " +
                "redis.call('del', KEYS[2]); " +
                "if oldNodeId and oldNodeId ~= '' and oldNodeId ~= 'OFFLINE' then " +
                "    redis.call('publish', KEYS[3], oldNodeId .. ':' .. ARGV[1]); " +
                "else " +
                "    redis.call('publish', KEYS[3], ARGV[1]); " +
                "end; " +
                "return oldNodeId";

        try {
            String oldNodeId = redisTemplate.execute(
                    new DefaultRedisScript<>(luaScript, String.class),
                    Arrays.asList(
                            "RT:" + userIdStr,
                            ConfigEnum.NETTY_SERVER_HEAD.getValue() + userIdStr,
                            ConfigEnum.REDIS_CONVERT_SEND.getValue()
                    ),
                    userIdStr
            );
            log.info("用户登出成功: userId={}, kickedNode={}", userIdStr, oldNodeId);
        } catch (Exception e) {
            log.error("用户登出失败: userId={}", userIdStr, e);
            throw new BusinessException(ErrorEnum.SYSTEM_ERROR);
        }

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000);
                String routeKey = ConfigEnum.NETTY_SERVER_HEAD.getValue() + userIdStr;
                String remainingRoute = redisTemplate.opsForValue().get(routeKey);
                if (remainingRoute != null) {
                    redisTemplate.delete(routeKey);
                    if (!"OFFLINE".equals(remainingRoute)) {
                        redisTemplate.convertAndSend(
                                ConfigEnum.REDIS_CONVERT_SEND.getValue(),
                                remainingRoute + ":" + userIdStr
                        );
                    } else {
                        redisTemplate.convertAndSend(ConfigEnum.REDIS_CONVERT_SEND.getValue(), userIdStr);
                    }
                    log.info("登出补偿完成: userId={}, remainingRoute={}", userIdStr, remainingRoute);
                }
            } catch (Exception e) {
                log.warn("登出补偿失败: userId={}", userIdStr, e);
            }
        }, ioThreadPool);
    }

    @Override
    public UserVO refreshToken(Long userId, String clientRefreshToken) {
        String rtKey = "RT:" + userId;
        String luaScript =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "    redis.call('del', KEYS[1]) " +
                "    return 1 " +
                "else " +
                "    return 0 " +
                "end";

        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(luaScript, Long.class),
                Collections.singletonList(rtKey),
                clientRefreshToken
        );
        ThrowUtils.throwIf(result == null || result == 0, ErrorEnum.LOGIN_EXPIRED);

        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_id", userId));
        ThrowUtils.throwIf(user == null, ErrorEnum.LOGIN_EXPIRED);
        return buildUserVOAndAllocateNode(user);
    }

    private void buildNewTokenPair(UserVO userVO) {
        Long userId = userVO.getUserId();
        String userIdStr = String.valueOf(userId);

        String accessToken = JwtUtil.generate(userIdStr, 2 * 60 * 60 * 1000L);
        String refreshToken = JwtUtil.generate(userIdStr, 7 * 24 * 60 * 60 * 1000L);

        userVO.setToken(accessToken);
        userVO.setRefreshToken(refreshToken);

        redisTemplate.opsForValue().set("RT:" + userId, refreshToken, 7, TimeUnit.DAYS);
    }
}
