package com.wangyutao.messaging.controller;


import com.wangyutao.messaging.common.Result;
import com.wangyutao.messaging.common.ResultGenerator;
import com.wangyutao.messaging.model.dto.ReceiveRedPacketRequest;
import com.wangyutao.messaging.model.dto.ReceiveRedPacketResponse;
import com.wangyutao.messaging.model.dto.RedPacketResponse;
import com.wangyutao.messaging.model.dto.SendRedPacketRequest;
import com.wangyutao.messaging.model.vo.ResponseMsgVo;
import com.wangyutao.messaging.service.GetRedPacketService;
import com.wangyutao.messaging.service.RedPacketReceiveService;
import com.wangyutao.messaging.service.RedPacketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/message/redpacket")
@RequiredArgsConstructor
public class RedPacketController {

    private final RedPacketService redPacketService;
    private final RedPacketReceiveService redPacketReceiveService;
    private final GetRedPacketService getRedPacketService;

    /**
     * 发送红包
     */
    @PostMapping("/send")
    public Result<ResponseMsgVo> sendRedPacket(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody SendRedPacketRequest request) throws Exception {
        // 🌟 强制覆盖，防止黑客抓包篡改 senderId 花别人的钱发红包
        request.setSendUserId(userId);
        // 建议：防止重复提交最好放在网关层用 Redis Lua 脚本做，这里保留注解也可以
        return ResultGenerator.genSuccessResult(redPacketService.sendRedPacket(request));
    }

    @PostMapping("/receive")
    public Result<ReceiveRedPacketResponse> receiveRedPacket(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody ReceiveRedPacketRequest request) {
        request.setUserId(userId);
        return ResultGenerator.genSuccessResult(redPacketReceiveService.receiveRedPacket(request));
    }

    @GetMapping("/detail/{redPacketId}")
    public Result<RedPacketResponse> getRedPacketDetail(
            @RequestHeader(value = "X-User-Id", required = true) Long currentUserId, // 留着做访问权限校验
            @PathVariable Long redPacketId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {

        // 这里的 getRedPacketDetails 已经被咱们优化成了 O(1) 批量查用户的神级性能
        return ResultGenerator.genSuccessResult(
                getRedPacketService.getRedPacketDetails(redPacketId, pageNum, pageSize)
        );
    }
}
