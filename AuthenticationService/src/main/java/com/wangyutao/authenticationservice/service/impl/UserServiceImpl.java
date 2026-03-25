package com.wangyutao.authenticationservice.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangyutao.authenticationservice.constants.OSSConstant;
import com.wangyutao.authenticationservice.model.dto.*;
import com.wangyutao.authenticationservice.model.enums.ErrorEnum;
import com.wangyutao.authenticationservice.exception.ThrowUtils;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import com.wangyutao.authenticationservice.model.enums.TimeOutEnum;
import com.wangyutao.authenticationservice.mapper.UserBalanceMapper;
import com.wangyutao.authenticationservice.mapper.UserMapper;
import com.wangyutao.authenticationservice.model.entity.User;
import com.wangyutao.authenticationservice.model.entity.UserBalance;
import com.wangyutao.authenticationservice.model.vo.*;
import com.wangyutao.authenticationservice.service.JwtBlacklistService;
import com.wangyutao.authenticationservice.service.UserService;
import com.wangyutao.authenticationservice.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.mail.SimpleEmail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.redisson.api.RedissonClient;
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
@RequiredArgsConstructor // 🌟 替代满屏的 @Autowired
public class UserServiceImpl implements UserService {

    @Qualifier("ioThreadPool")
    private final Executor ioThreadPool;


    private final UserBalanceMapper userBalanceMapper;
    private final UserMapper userMapper;
    private final RedissonClient redissonClient;
    private final OSSUtil ossUtil;
    private final StringRedisTemplate redisTemplate;
    private final ServiceInstanceUtil serviceInstanceUtil; // 一致性哈希工具，咱们下一步盘它
    private final RedisLockExecutor redisLockExecutor;
    private final IdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;
    private final JwtBlacklistService jwtBlacklistService;


    @Override
    public void sendClientSms(UserSMSRequest userSMSRequest) {
        String phone = userSMSRequest.getPhone();
        String code = new RandomNumUtil().getRandomNum();

        // 1. 🌟 核心欺骗：Redis 照旧用前端传来的真实手机号做 Key！
        // 这样后续的注册/登录接口去 Redis 查 phone 的时候，能完美匹配上。
        redisTemplate.opsForValue().set(phone, code, TimeOutEnum.CODE_TIME_OUT.getTimeOut(), TimeUnit.MINUTES);

        // 2. 异步发送“伪装短信”的邮件，绝不阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                // 🌟 目标邮箱写死成你的接收邮箱！这里就是你的“万能短信接收器”
                String myDevEmail = "1533290655@qq.com";

                // 设置TLS协议
                System.setProperty("mail.smtp.ssl.protocols", "TLSv1.2");
                SimpleEmail mail = new org.apache.commons.mail.SimpleEmail();
                mail.setHostName("smtp.qq.com");
                mail.setAuthentication("1533290655@qq.com", "pgufqaqflhjniiji");
                mail.setFrom("1533290655@qq.com", "天幕IM-短信网关拦截"); // 改个霸气的发件人
                mail.setSSLOnConnect(true);

                mail.addTo(myDevEmail);
                mail.setSubject("【开发环境】短信验证码拦截通知");

                // 🌟 极其关键的细节：在邮件正文里打上手机号的 Tag
                // 这样你用 13800000001 和 13800000002 注册时，看邮件就知道是哪个号的码了
                mail.setMsg("系统已拦截发往手机号 [" + phone + "] 的短信。\n您的验证码为: " + code + "\n(五分钟内有效)");

                mail.send();
                log.info("📢 [Mock模式生效] 手机号 {} 的验证码 {} 已重定向发送至开发者邮箱", phone, code);

            } catch (Exception e) {
                // 如果邮件网关挂了，把 Redis 里无效的验证码删掉，保持数据一致性
                log.error("❌ 模拟短信(邮件)发送失败, 目标手机: {}", phone, e);
                redisTemplate.delete(phone);
            }
        }, ioThreadPool);
    }

    @Override
    public UserVO userLoginPwd(UserLoginPwdRequest request, HttpServletResponse response) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", request.getPhone()));
        //String encryptedPassword = DigestUtils.md5DigestAsHex((ConfigEnum.PASSWORD_SALT.getValue() + request.getPassword()).getBytes());


        ThrowUtils.throwIf(user == null, ErrorEnum.LOGIN_ERROR);
        // 使用 BCrypt.checkpw 自动提取密文中的盐并与明文进行哈希比对
        boolean isPwdMatch = cn.hutool.crypto.digest.BCrypt.checkpw(request.getPassword(), user.getPassword());
        ThrowUtils.throwIf(!isPwdMatch, ErrorEnum.LOGIN_ERROR);

        return buildUserVOAndAllocateNode(user);
    }

    @Override
    public UserVO userLoginCode(UserLoginCodeRequest request, HttpServletResponse response) {
        String redisCode = redisTemplate.opsForValue().get(request.getPhone());
        ThrowUtils.throwIf(redisCode == null || !redisCode.equals(request.getCode()), ErrorEnum.CODE_ERROR);

        User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", request.getPhone()));
        ThrowUtils.throwIf(user == null, ErrorEnum.LOGIN_ERROR);

        // 🌟 校验并登录成功后，立刻销毁验证码，防止二次利用！
        redisTemplate.delete(request.getPhone());

        return buildUserVOAndAllocateNode(user);
    }

    /**
     * 🌟 提取的公共方法：构建VO、生成Token、分配 Netty 节点
     */
    // 🌟 改造后的登录组装方法
    private UserVO buildUserVOAndAllocateNode(User user) {
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);

        buildNewTokenPair(userVO);

        // 获取 Netty 服务实例
        String nettyHost = serviceInstanceUtil.getServiceInstance(userVO.getUserId());
        ThrowUtils.throwIf(nettyHost == null, ErrorEnum.SYSTEM_ERROR);

        userVO.setNettyUrl(ConfigEnum.NETTY_PROTOCOL.getValue() + nettyHost + ConfigEnum.NETTY_PORT.getValue());
        return userVO;
    }

    @Override
    public void userRegister(UserRegisterRequest request) {
        String phone = request.getPhone();

        // 锁外预处理：BCrypt 加密 ~100ms，ID 生成 ~1ms，前置以缩小锁粒度
        String encryptedPassword = BCrypt.hashpw(request.getPassword());
        Long distributedUserId = idGenerator.nextId();
        String generatedNickname = new NicknameGeneratorUtil().generateNickname();

        String lockKey = "register:lock:" + phone;

        redisLockExecutor.executeWithLock(lockKey, 3000, 5000, () -> {
            return transactionTemplate.execute(status -> {

                // 验证码校验必须在锁内，消除锁外校验与锁内操作之间的 TOCTOU 竞态窗口
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

                // 事务提交前销毁验证码，保证一次性消费
                redisTemplate.delete(phone);

                return null;
            });
        });
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

    public UploadUrlResponse getUploadUrl(String fileName) {
        return new UploadUrlResponse()
                .setUploadUrl(ossUtil.uploadUrl(OSSConstant.BUCKET_NAME, fileName, OSSConstant.PICTURE_EXPIRE_TIME))
                .setDownloadUrl(ossUtil.downUrl(fileName));
    }

    @Override
    public void userLogout(UserLogOutRequest request) {
        String userIdStr = String.valueOf(request.getUserId());
        
        // 🔥 1. 记录登出时间戳（与网关 jwt:logout 比较 token.iat）
        jwtBlacklistService.addToBlacklist(userIdStr);
        
        // 🔥 2. 使用 Lua 脚本保证原子性：删除 Token + 路由缓存 + 发布踢人消息
        String luaScript =
            "redis.call('del', KEYS[1]); " +
            "redis.call('del', KEYS[2]); " +
            "redis.call('publish', KEYS[3], ARGV[1]); " +
            "return 1";
        
        try {
            redisTemplate.execute(
                new DefaultRedisScript<>(luaScript, Long.class),
                Arrays.asList(
                    "RT:" + userIdStr,
                    ConfigEnum.NETTY_SERVER_HEAD.getValue() + userIdStr,
                    ConfigEnum.REDIS_CONVERT_SEND.getValue()
                ),
                ConfigEnum.NETTY_SERVER_HEAD.getValue() + userIdStr
            );
            
            log.info("✅ 用户登出成功: userId={}", userIdStr);
            
        } catch (Exception e) {
            log.error("❌ 用户登出失败: userId={}", userIdStr, e);
            throw new com.wangyutao.authenticationservice.exception.BusinessException(ErrorEnum.SYSTEM_ERROR);
        }
        
        // 异步补偿：检查路由是否已清除，若仍残留则重新删除并发布踢人消息
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000);
                String routeKey = ConfigEnum.NETTY_SERVER_HEAD.getValue() + userIdStr;
                String remainingRoute = redisTemplate.opsForValue().get(routeKey);
                if (remainingRoute != null) {
                    redisTemplate.delete(routeKey);
                    redisTemplate.convertAndSend(ConfigEnum.REDIS_CONVERT_SEND.getValue(), routeKey);
                    log.info("登出补偿: 路由残留已清除, userId={}, remainingRoute={}", userIdStr, remainingRoute);
                }
            } catch (Exception e) {
                log.warn("登出补偿检查失败: userId={}", userIdStr, e);
            }
        }, ioThreadPool);
    }

    /**
     * 🔥 Token 续签实现 (防重放攻击版本)
     */
    @Override
    public UserVO refreshToken(Long userId, String clientRefreshToken) {
        String rtKey = "RT:" + userId;
        
        // 🔥 使用 Lua 脚本实现原子性校验+删除，防止并发重放攻击
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
        
        // 校验失败：Token 不存在、已被使用或不匹配
        ThrowUtils.throwIf(result == null || result == 0, ErrorEnum.LOGIN_EXPIRED);
        
        log.info("RefreshToken 校验通过并已销毁: userId={}", userId);
        
        // 从数据库查出完整用户信息，避免返回给前端的 VO 字段大面积为 null
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_id", userId));
        ThrowUtils.throwIf(user == null, ErrorEnum.LOGIN_EXPIRED);

        return buildUserVOAndAllocateNode(user);
    }


    // 🌟 核心复用方法：专门负责生成一对新 Token 并刷新 Redis
    private void buildNewTokenPair(UserVO userVO) {
        Long userId = userVO.getUserId();

        String userIdStr = String.valueOf(userId);

        // 1. 生成双 Token (AccessToken 2小时, RefreshToken 7天)
        String accessToken = JwtUtil.generate(userIdStr, 2 * 60 * 60 * 1000L);
        String refreshToken = JwtUtil.generate(userIdStr, 7 * 24 * 60 * 60 * 1000L);

        // 2. 塞入返回值
        userVO.setToken(accessToken); // 兼容旧版，这就是 AccessToken
        userVO.setRefreshToken(refreshToken);

        // 3. Redis 只存 RefreshToken（用来踢人或验证续期）
        redisTemplate.opsForValue().set(
                "RT:" + userId,
                refreshToken,
                7, TimeUnit.DAYS
        );
        // 故意不删除 jwt:logout：保留最后登出时间，使「登出前签发」的被盗 AT 在重新登录后仍会被网关拒绝（iat < logoutAt）
    }
}

