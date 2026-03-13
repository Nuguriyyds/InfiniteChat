package com.wangyutao.realtimecommunication.utils;

import com.alibaba.cloud.commons.lang.StringUtils;
import com.wangyutao.realtimecommunication.enums.ConfigEnum;
import com.wangyutao.realtimecommunication.enums.TimeOutEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Date;

@Slf4j // 🌟 引入企业级日志
public final class JwtUtil {

    private final static Duration expiration = Duration.ofHours(TimeOutEnum.JWT_TIME_OUT.getTimeOut());

    public static String generate(String id) {
        Date expiryDate = new Date(System.currentTimeMillis() + expiration.toMillis());

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
            // 🌟 规范日志记录：记录具体的异常信息，方便排查是过期了还是被篡改了
            log.warn("⚠️ JWT 解析失败: Token 非法或已过期. Token: [{}]", token, e);
            return null;
        } catch (Exception e) {
            log.error("❌ JWT 解析发生未知错误", e);
            return null;
        }
    }

    /**
     * 🌟 新增极其好用的快捷方法：直接安全获取 UserId
     */
    public static String getUserIdSafe(String token) {
        Claims claims = parse(token);
        return claims != null ? claims.getSubject() : null;
    }
}
