package com.wangyutao.authenticationservice.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
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
import com.wangyutao.authenticationservice.service.UserService;
import com.wangyutao.authenticationservice.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.mail.SimpleEmail;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.redisson.api.RedissonClient;
import javax.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor // 🌟 替代满屏的 @Autowired
public class UserServiceImpl implements UserService {


    private final UserBalanceMapper userBalanceMapper;
    private final UserMapper userMapper;
    private final RedissonClient redissonClient;
    private final OSSUtil ossUtil;
    private final StringRedisTemplate redisTemplate;
    private final ServiceInstanceUtil serviceInstanceUtil; // 一致性哈希工具，咱们下一步盘它
    private final RedisLockExecutor redisLockExecutor;

    @Override
    public void sendClientSms(UserSMSRequest userSMSRequest) {
        String phone = userSMSRequest.getPhone();
        String code = new RandomNumUtil().getRandomNum();

        // 1. 🌟 核心欺骗：Redis 照旧用前端传来的真实手机号做 Key！
        // 这样后续的注册/登录接口去 Redis 查 phone 的时候，能完美匹配上。
        redisTemplate.opsForValue().set(phone, code, TimeOutEnum.CODE_TIME_OUT.getTimeOut(), TimeUnit.MINUTES);

        // 2. 异步发送“伪装短信”的邮件，绝不阻塞主线程
        new Thread(() -> {
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
                mail.setMsg("系统已拦截发往手机号 [" + phone + "] 的短信。\n您的验证码为: " + code + "\n(一分钟内有效)");

                mail.send();
                log.info("📢 [Mock模式生效] 手机号 {} 的验证码 {} 已重定向发送至开发者邮箱", phone, code);

            } catch (Exception e) {
                // 如果邮件网关挂了，把 Redis 里无效的验证码删掉，保持数据一致性
                log.error("❌ 模拟短信(邮件)发送失败, 目标手机: {}", phone, e);
                redisTemplate.delete(phone);
            }
        }).start();
    }

    @Override
    public UserVO userLoginPwd(UserLoginPwdRequest request, HttpServletResponse response) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", request.getPhone()));
        String encryptedPassword = DigestUtils.md5DigestAsHex((ConfigEnum.PASSWORD_SALT.getValue() + request.getPassword()).getBytes());
        ThrowUtils.throwIf(user == null || !encryptedPassword.equals(user.getPassword()), ErrorEnum.LOGIN_ERROR);

        return buildUserVOAndAllocateNode(user);
    }

    @Override
    public UserVO userLoginCode(UserLoginCodeRequest request, HttpServletResponse response) {
        String redisCode = redisTemplate.opsForValue().get(request.getPhone());
        ThrowUtils.throwIf(redisCode == null || !redisCode.equals(request.getCode()), ErrorEnum.CODE_ERROR);

        User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", request.getPhone()));
        ThrowUtils.throwIf(user == null, ErrorEnum.LOGIN_ERROR);

        return buildUserVOAndAllocateNode(user);
    }

    /**
     * 🌟 提取的公共方法：构建VO、生成Token、分配 Netty 节点
     */
    // 🌟 改造后的登录组装方法
    private UserVO buildUserVOAndAllocateNode(User user) {
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);

        // 调用复用方法颁发 Token
        buildNewTokenPair(userVO);

        // 获取 Netty 服务实例
        String nettyHost = serviceInstanceUtil.getServiceInstance(userVO.getUserId());
        ThrowUtils.throwIf(nettyHost == null, ErrorEnum.SYSTEM_ERROR);

        userVO.setNettyUrl(ConfigEnum.NETTY_PROTOCOL.getValue() + nettyHost + ConfigEnum.NETTY_PORT.getValue());
        return userVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void userRegister(UserRegisterRequest request) {
        String phone = request.getPhone();

        // 1. 🌟 Fail-Fast：在抢锁前就拦截掉错误的验证码，保护锁资源
        String redisCode = redisTemplate.opsForValue().get(phone);
        ThrowUtils.throwIf(redisCode == null || !redisCode.equals(request.getCode()), ErrorEnum.CODE_ERROR);

        // 2. 拼接出精准的细粒度锁 Key (假设 DistributeLockEnum 里是这种用法，按你实际情况微调)
        String lockKey = "register:lock:" + phone;

        // 3. 🌟 降维调用：3秒拿不到锁就报错，-1触发看门狗，业务逻辑全部包裹在 Lambda 中
        redisLockExecutor.executeWithLock(lockKey, 3000, -1, () -> {

            // --- 下面的逻辑全部受到分布式锁的严密保护 ---

            // 核心查重，防止高并发下多个请求同时绕过校验
            ThrowUtils.throwIf(isReRegister(phone), ErrorEnum.REGISTER_ERROR);

            String encryptedPassword = DigestUtils.md5DigestAsHex((ConfigEnum.PASSWORD_SALT.getValue() + request.getPassword()).getBytes());
            Snowflake snowflake = IdUtil.getSnowflake(Integer.parseInt(ConfigEnum.WORKED_ID.getValue()), Integer.parseInt(ConfigEnum.DATACENTER_ID.getValue()));

            // 统一 userId 为 String 类型
            User user = new User()
                    .setUserId(String.valueOf(snowflake.nextId()))
                    .setUserName(new NicknameGeneratorUtil().generateNickname())
                    .setPhone(phone)
                    .setPassword(encryptedPassword);

            ThrowUtils.throwIf(userMapper.insert(user) <= 0, ErrorEnum.MYSQL_ERROR);

            UserBalance userBalance = new UserBalance()
                    .setUserId(user.getUserId())
                    .setBalance(BigDecimal.valueOf(1000))
                    .setUpdatedAt(LocalDateTime.now());

            ThrowUtils.throwIf(userBalanceMapper.insert(userBalance) <= 0, ErrorEnum.MYSQL_ERROR);

            return null; // Supplier 规范，没有具体返回对象就 return null
        });
    }

    private boolean isReRegister(String phone) {
        return userMapper.selectCount(new QueryWrapper<User>().eq("phone", phone)) > 0;
    }

    @Override
    public void updateAvatar(String id, UserUpdateAvatarRequest request) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_id", Long.valueOf(id)));
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
        String userIdStr = request.getUserId().toString();
        // 删除 Token
        redisTemplate.delete("RT:" + userIdStr);
        // 删除网关路由缓存
        redisTemplate.delete(ConfigEnum.NETTY_SERVER_HEAD.getValue() + userIdStr);
        // 🌟 广播踢人指令：通知 Netty 集群切断该用户的 TCP 连接
        redisTemplate.convertAndSend(ConfigEnum.REDIS_CONVERT_SEND.getValue(), ConfigEnum.NETTY_SERVER_HEAD.getValue() + userIdStr);
    }

    /**
     * 🌟 新增：Token 续签实现
     */
    // 🌟 补齐的续期方法
    @Override
    public UserVO refreshToken(String userId, String clientRefreshToken) {
        // 1. 去 Redis 查真正的 RefreshToken
        String redisRt = redisTemplate.opsForValue().get("RT:" + userId);

        // 2. 校验（防伪造、防被挤下线）
        ThrowUtils.throwIf(redisRt == null || !redisRt.equals(clientRefreshToken), ErrorEnum.LOGIN_EXPIRED);

        // 3. 校验通过，重新生成一对全新的 Token
        UserVO userVO = new UserVO();
        userVO.setUserId(userId);
        buildNewTokenPair(userVO); // 再次调用复用方法

        return userVO; // 返回给前端新的 token 和 refreshToken
    }


    // 🌟 核心复用方法：专门负责生成一对新 Token 并刷新 Redis
    private void buildNewTokenPair(UserVO userVO) {
        String userId = userVO.getUserId();

        // 1. 生成双 Token (AccessToken 2小时, RefreshToken 7天)
        String accessToken = JwtUtil.generate(userId, 2 * 60 * 60 * 1000L);
        String refreshToken = JwtUtil.generate(userId, 7 * 24 * 60 * 60 * 1000L);

        // 2. 塞入返回值
        userVO.setToken(accessToken); // 兼容旧版，这就是 AccessToken
        userVO.setRefreshToken(refreshToken);

        // 3. Redis 只存 RefreshToken（用来踢人或验证续期）
        redisTemplate.opsForValue().set(
                "RT:" + userId,
                refreshToken,
                7, TimeUnit.DAYS
        );
    }
}

