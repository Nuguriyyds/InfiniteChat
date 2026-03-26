package com.wangyutao.authenticationservice.interceptor;

import com.wangyutao.authenticationservice.exception.BusinessException;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import com.wangyutao.authenticationservice.model.enums.ErrorEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@Component
public class GatewayTrustInterceptor implements HandlerInterceptor {

    @Value("${security.internal-request.secret:infinitechat_internal_auth_2026}")
    private String internalRequestSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String internalRequestHeader = request.getHeader(ConfigEnum.INTERNAL_REQUEST_HEADER.getValue());
        if (!StringUtils.equals(internalRequestSecret, StringUtils.trimToEmpty(internalRequestHeader))) {
            log.warn("拦截非法直连请求: uri={}, remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            throw new BusinessException(ErrorEnum.NO_AUTH_ERROR, "请求未通过网关鉴权");
        }

        String userIdHeader = StringUtils.trimToNull(request.getHeader(ConfigEnum.USER_ID_HEADER.getValue()));
        if (userIdHeader == null) {
            throw new BusinessException(ErrorEnum.NOT_LOGIN_ERROR, "缺少当前用户身份");
        }

        try {
            Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorEnum.PARAMS_ERROR, "当前用户身份格式非法");
        }
        return true;
    }
}
