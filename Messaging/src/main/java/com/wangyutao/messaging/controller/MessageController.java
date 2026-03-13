package com.wangyutao.messaging.controller;

import com.wangyutao.messaging.model.dto.SendMsgRequest;
import com.wangyutao.messaging.model.vo.ResponseMsgVo;
import com.wangyutao.messaging.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * IM 核心消息发送接口
 */
@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * 发送聊天消息 (单聊/群聊)
     * * @param currentUserId 从网关透传过来的当前登录用户 ID (安全可靠)
     * @param request       前端传入的消息体
     * @return 消息发送回执
     */
    @PostMapping("/send")
    public ResponseMsgVo sendMessage(
            @RequestHeader(value = "X-User-Id", required = true) Long currentUserId,
            @RequestBody SendMsgRequest request) {

        // 🌟 架构师的安全底线：不管前端传什么，发送者 ID 必须以 Header 里的 Token 解析结果为准！
        request.setSendUserId(currentUserId);

        // 调用刚才咱们写好的无敌 Service
        return messageService.sendMessage(request);
    }
}