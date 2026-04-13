package com.wangyutao.messaging.controller;

import com.wangyutao.messaging.exception.ServiceException;
import com.wangyutao.messaging.model.dto.SendMsgRequest;
import com.wangyutao.messaging.model.vo.ResponseMsgVo;
import com.wangyutao.messaging.model.vo.SyncMessageVo;
import com.wangyutao.messaging.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
        request.setSendUserId(currentUserId);
        return messageService.sendMessage(request);
    }

    @GetMapping("/maxSeq")
    public Long getMaxSeq(@RequestParam("sessionId") String sessionId) {
        return messageService.getMaxSeq(sessionId);
    }

    @GetMapping("/sync")
    public List<SyncMessageVo> syncMessages(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("beginSeq") Long beginSeq,
            @RequestParam("endSeq") Long endSeq) {
        return messageService.syncMessages(sessionId, beginSeq, endSeq);
    }

    /**
     * 客户端收到并渲染消息后，上报已确认的最大 seq。
     * 服务端据此更新 lastAckSeq，确保换设备/重连时可从正确位置开始补齐。
     */
    @PostMapping("/ack")
    public void ackSeq(
            @RequestHeader(value = "X-User-Id", required = true) Long currentUserId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "seq", required = false) Long seq,
            @RequestParam(value = "messageId", required = false) String messageId) {
        if (StringUtils.isNotBlank(messageId)) {
            messageService.ackMessage(currentUserId, messageId);
            return;
        }
        if (StringUtils.isBlank(sessionId) || seq == null) {
            throw new ServiceException("ACK 参数非法，必须提供 messageId 或 sessionId+seq");
        }
        messageService.ackSeq(currentUserId, sessionId, seq);
    }

    /**
     * 登录或 WebSocket 重连时调用，返回该用户所有会话的 lastAckSeq。
     * 客户端用这个 Map 作为 beginSeq，逐会话调用 /sync 补齐离线消息。
     */
    @GetMapping("/sessions/ackSeqMap")
    public Map<String, Long> getSessionAckSeqMap(
            @RequestHeader(value = "X-User-Id", required = true) Long currentUserId) {
        return messageService.getSessionAckSeqMap(currentUserId);
    }
}
