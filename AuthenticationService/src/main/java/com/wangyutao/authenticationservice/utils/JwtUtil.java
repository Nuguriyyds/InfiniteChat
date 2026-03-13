package com.wangyutao.authenticationservice.utils;

import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import com.wangyutao.authenticationservice.model.enums.TimeOutEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.Date;

@Slf4j
public final class JwtUtil {

    // 保留原有的默认过期时间
    private final static Duration defaultExpiration = Duration.ofHours(TimeOutEnum.JWT_TIME_OUT.getTimeOut());

    /**
     * 原有生成方法：使用默认过期时间 (兼容老代码)
     */
    public static String generate(String id) {
        return generate(id, defaultExpiration.toMillis());
    }

    /**
     * 🌟 新增核心方法：支持自定义过期时间的生成方法（双 Token 专属）
     * @param id 用户ID (String类型)
     * @param expirationMillis 过期时间(毫秒)
     */
    public static String generate(String id, long expirationMillis) {
        Date expiryDate = new Date(System.currentTimeMillis() + expirationMillis);

        return Jwts.builder()
                .setSubject(id)
                .setIssuedAt(new Date())
                .setExpiration(expiryDate)
                .signWith(SignatureAlgorithm.HS512, ConfigEnum.TOKEN_SECRET_KEY.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .compact();
    }

    /**
     * 解析JWT
     * @return 解析成功返回 Claims，解析失败返回 null
     */
    public static Claims parse(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        try {
            return Jwts.parser()
                    .setSigningKey(ConfigEnum.TOKEN_SECRET_KEY.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            // 🌟 规范日志记录：去掉了 e 的完整堆栈，避免高并发下过期 Token 把日志磁盘刷爆
            log.warn("⚠️ JWT 解析失败: Token 非法或已过期. Token: [{}]", token);
            return null;
        } catch (Exception e) {
            log.error("❌ JWT 解析发生未知错误", e);
            return null;
        }
    }

    /**
     * 🌟 极其好用的快捷方法：直接安全获取 UserId
     */
    public static String getUserIdSafe(String token) {
        Claims claims = parse(token);
        return claims != null ? claims.getSubject() : null;
    }
}