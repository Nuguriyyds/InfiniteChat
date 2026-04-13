package com.wangyutao.messaging.config;


import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.support.config.FastJsonConfig;
import com.alibaba.fastjson.support.spring.FastJsonHttpMessageConverter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor //
public class WebMvcConfig implements WebMvcConfigurer {
    private final Logger logger = LoggerFactory.getLogger(WebMvcConfig.class);

    // 注入刚才写的 Token 拦截器
//    private final TokenInterceptor tokenInterceptor;
//
//    // 1. 拦截器注册逻辑
//    @Override
//    public void addInterceptors(InterceptorRegistry registry) {
//        registry.addInterceptor(tokenInterceptor)
//                .addPathPatterns("/**") // 拦截所有请求
//                .excludePathPatterns(   // 白名单：这些不需要 Token 就能访问
//                        "/api/v1/user/noToken/**",
//
//                        // 🌟 修复：放行续期接口，注意路径对齐
//                        "/api/v1/user/refreshToken",
//
//                        // Swagger 相关路径（如果它们也受 /api 影响，请加上前缀）
//                        "/swagger-ui/**",
//                        "/v3/api-docs/**",
//                        "/swagger-resources/**",
//                        "/webjars/**"
//                );
//    }

    // 2. 你原有的 FastJson 配置逻辑完全保留
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        FastJsonHttpMessageConverter converter = new FastJsonHttpMessageConverter();
        FastJsonConfig config = new FastJsonConfig();

        // 保留空的字段
        config.setSerializerFeatures(SerializerFeature.WriteMapNullValue);

        converter.setFastJsonConfig(config);
        converter.setDefaultCharset(StandardCharsets.UTF_8);
        converter.setSupportedMediaTypes(Arrays.asList(MediaType.APPLICATION_JSON));
        converters.add(converter);
    }
}
