package com.wangyutao.realtimecommunication.controller;

import com.wangyutao.realtimecommunication.common.Result;
import com.wangyutao.realtimecommunication.common.ResultGenerator;
import com.wangyutao.realtimecommunication.model.push.FriendApplicationNotification;
import com.wangyutao.realtimecommunication.model.push.MomentNotification;
import com.wangyutao.realtimecommunication.model.push.NewGroupSessionNotification;
import com.wangyutao.realtimecommunication.model.push.NewSessionNotification;
import com.wangyutao.realtimecommunication.service.NettyMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequestMapping("/api/v1/chat/push")
@RequiredArgsConstructor
public class PushController {

    private final NettyMessageService nettyMessageService;

    /**
     * 发送新会话通知
     */
    @PostMapping("/newSession/{userId}")
    public Result pushNewSession(
            @PathVariable("userId") Long userId,
            @RequestBody NewSessionNotification notification) {
        nettyMessageService.sendNewSessionNotification(notification, userId);
        return ResultGenerator.genSuccessResult("New session notification pushed.");
    }

    /**
     * 发送好友申请通知
     */
    @PostMapping("/friendApplication/{userId}")
    public Result pushFriendApplication(
            @PathVariable("userId") Long userId,
            @RequestBody FriendApplicationNotification notification) {
        nettyMessageService.sendFriendApplicationNotification(notification, userId);
        return ResultGenerator.genSuccessResult("Friend application notification pushed.");
    }

    /**
     * 发送新群会话通知
     */
    @PostMapping("/newGroupSession/{userId}")
    public Result pushNewGroupSession(
            @PathVariable("userId") Long userId,
            @RequestBody NewGroupSessionNotification notification) {
        nettyMessageService.sendNewGroupSessionNotification(notification, userId);
        return ResultGenerator.genSuccessResult("New Group session notification pushed.");
    }

    /**
     * 推送朋友圈通知 (广播机制)
     */
    @PostMapping("/moment")
    public Result pushMomentNotification(@RequestBody MomentNotification momentNotification) {
        nettyMessageService.sendNoticeMoment(momentNotification);
        return ResultGenerator.genSuccessResult("Moment notification pushed.");
    }
}