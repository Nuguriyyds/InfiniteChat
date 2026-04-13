package com.wangyutao.messaging.service;

import com.wangyutao.messaging.model.dto.SendMsgRequest;
import com.wangyutao.messaging.model.entity.Message;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wangyutao.messaging.model.vo.ResponseMsgVo;
import com.wangyutao.messaging.model.vo.SyncMessageVo;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
* @author yutao
* @description 针对表【im_message(IM核心聊天消息表)】的数据库操作Service
* @createDate 2026-03-10 11:36:08
*/
public interface MessageService extends IService<Message> {

    @Transactional(rollbackFor = Exception.class)
    ResponseMsgVo sendMessage(SendMsgRequest request);

    List<SyncMessageVo> syncMessages(String sessionId, Long beginSeq, Long endSeq);

    Long getMaxSeq(String sessionId);

    /**
     * 客户端上报已读 seq，服务端更新 lastAckSeq
     */
    void ackSeq(Long userId, String sessionId, Long seq);

    /**
     * 兼容只回传 messageId 的 ACK 场景，服务端回查消息并更新 lastAckSeq
     */
    void ackMessage(Long userId, String messageId);

    /**
     * 登录/重连时，返回该用户所有会话的 lastAckSeq，客户端据此发起离线消息补齐
     */
    Map<String, Long> getSessionAckSeqMap(Long userId);
}
