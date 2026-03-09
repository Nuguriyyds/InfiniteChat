package com.wangyutao.gateway.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class JwtUtil {

    // 🚨 极度重要警告：这里的秘钥必须和你 AuthenticationService 里签发 Token 用的秘钥【一模一样】！
    // 请把你生成 Token 用的那个 ConfigEnum.TOKEN_SECRET_KEY.getValue() 的真实字符串复制填到这里。
    private static final String SECRET_KEY = "goat";

    /**
     * 解析 JWT Token
     *
     * @param token 客户端传来的 token 字符串
     * @return 解析成功返回 Claims，失败返回 null
     */
    public static Claims parse(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }

        try {
            // 使用 JJWT 解析 Token，纯 CPU 无状态验签
            return Jwts.parserBuilder()
                    .setSigningKey(SECRET_KEY.getBytes()) // 设置验签秘钥
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            // 捕获所有 Token 异常：过期、篡改、格式错误等
            log.warn("Token 解析失败: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Token 校验发生未知异常", e);
            return null;
        }
    }
}
