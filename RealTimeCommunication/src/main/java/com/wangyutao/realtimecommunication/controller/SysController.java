package com.wangyutao.realtimecommunication.controller;

import com.wangyutao.realtimecommunication.common.Result;
import com.wangyutao.realtimecommunication.common.ResultGenerator;
import com.wangyutao.realtimecommunication.model.vo.OnlineUserVo;
import com.wangyutao.realtimecommunication.service.SysService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@Slf4j
@RequestMapping("/api/v1/chat/sys")
public class SysController {

    // sysService 系统服务
    @Resource
    private SysService sysService;

    /**
     * @MethodName getOnlineUser
     * @Description 获取在线用户信息
     * @return: Result<List<OnlineUserVo>>
     * @Date 2024/11/23 17:17
     */
    @GetMapping("/onlineUser")
    public Result<OnlineUserVo> getOnlineUser(){
        OnlineUserVo onlineUser = sysService.getOnlineUser();

        return ResultGenerator.genSuccessResult(onlineUser);
    }
}
