package com.wangyutao.contact.listener;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.wangyutao.contact.service.ContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 用户注册事件监听器
 * 
 * 🌟 核心职责：
 * 监听用户注册事件，自动创建 AI 助手
 * 
 * 🌟 架构亮点：
 * - 异步处理：不阻塞用户注册流程
 * - 解耦：Auth Service 不需要知道 Contact Service 的存在
 * - 可扩展：后续可以监听其他事件（如用户升级 VIP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
    topic = "USER_EVENT",
    consumerGroup = "contact-user-event-group",
    selectorExpression = "REGISTER" // 只监听注册事件
)
public class UserRegisterListener implements RocketMQListener<String> {

    private final ContactService contactService;

    @Override
    public void onMessage(String message) {
        try {
            log.info("收到用户注册事件: {}", message);
            
            // 解析消息
            JSONObject json = JSON.parseObject(message);
            Long userId = json.getLong("userId");
            
            if (userId == null) {
                log.warn("用户 ID 为空，跳过创建 AI 助手");
                return;
            }
            
            // 创建 AI 助手
            contactService.createAiAssistant(userId);
            
            log.info("用户注册事件处理成功, userId={}", userId);
            
        } catch (Exception e) {
            log.error("处理用户注册事件失败, message={}", message, e);
            // 不抛出异常，避免 MQ 重试（创建 AI 助手失败不影响用户注册）
        }
    }
}
