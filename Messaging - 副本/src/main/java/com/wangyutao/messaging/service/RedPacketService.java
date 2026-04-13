package com.wangyutao.messaging.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wangyutao.messaging.model.dto.SendRedPacketRequest;
import com.wangyutao.messaging.model.entity.RedPacket;
import com.wangyutao.messaging.model.vo.ResponseMsgVo;
import org.springframework.stereotype.Service;


public interface RedPacketService extends IService<RedPacket> {
    /**
     * 发送红包
     * @param request
     * @return
     * @throws Exception
     */
    ResponseMsgVo sendRedPacket(SendRedPacketRequest request) throws Exception;

    /**
     * 红包过期处理
     *
     * @param redPacketId 红包Id
     */
    void handleExpiredRedPacket(Long redPacketId);
}