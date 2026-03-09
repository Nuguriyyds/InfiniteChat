package com.wangyutao.realtimecommunication.controller;

import com.wangyutao.realtimecommunication.common.Result;
import com.wangyutao.realtimecommunication.common.ResultGenerator;
import com.wangyutao.realtimecommunication.model.entity.Message;
import com.wangyutao.realtimecommunication.service.NettyMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/message/user")
@Slf4j
@RequiredArgsConstructor
public class RcvMsgController {
    private final NettyMessageService nettyMessageService;

    @PostMapping
    public Result receiveMessage(@RequestBody Message message){
        log.info("message:{}",message);
        nettyMessageService.sendMessageToUser(message);
        return ResultGenerator.genSuccessResult();
    }
}