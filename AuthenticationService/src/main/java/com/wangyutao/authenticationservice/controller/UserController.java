package com.wangyutao.authenticationservice.controller;

import com.wangyutao.authenticationservice.common.Result;
import com.wangyutao.authenticationservice.common.ResultGenerator;
import com.wangyutao.authenticationservice.exception.ThrowUtils;
import com.wangyutao.authenticationservice.model.dto.*;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import com.wangyutao.authenticationservice.model.enums.ErrorEnum;
import com.wangyutao.authenticationservice.model.enums.SuccessEnum;
import com.wangyutao.authenticationservice.model.vo.*;
import com.wangyutao.authenticationservice.service.UserService;
import com.wangyutao.authenticationservice.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/v1/user/noToken/sms")
    public Result sendClientSms(@Valid @RequestBody UserSMSRequest userSMSRequest) throws Exception {
        userService.sendClientSms(userSMSRequest);
        return ResultGenerator.genSuccessResult(SuccessEnum.SUCCESS_CODE_SEND.getMessage());
    }

    @PostMapping("/v1/user/noToken/register")
    public Result register(@Valid @RequestBody UserRegisterRequest userRegisterRequest) throws Exception {
        userService.userRegister(userRegisterRequest);
        return ResultGenerator.genSuccessResult(SuccessEnum.SUCCESS_REGISTER.getMessage());
    }

    @PostMapping("/v1/user/noToken/loginPwd")
    public Result loginPwd(@Valid @RequestBody UserLoginPwdRequest userLoginPwdRequest, HttpServletResponse response) {
        UserVO userVO = userService.userLoginPwd(userLoginPwdRequest, response);
        return ResultGenerator.genSuccessResult(userVO);
    }

    @PostMapping("/v1/user/noToken/loginCode")
    public Result loginCode(@Valid @RequestBody UserLoginCodeRequest userLoginCodeRequest, HttpServletResponse response) {
        UserVO userVO = userService.userLoginCode(userLoginCodeRequest, response);
        return ResultGenerator.genSuccessResult(userVO);
    }

    @PostMapping("/v1/user/logout")
    public Result logout(@RequestHeader("X-User-Id") String userId) { // 🌟 抛弃 Body 传参，用网关的真实 ID
        // 包装一下以兼容你现有的 Service 逻辑
        UserLogOutRequest request = new UserLogOutRequest();
        request.setUserId(Long.valueOf(userId));

        userService.userLogout(request);
        return ResultGenerator.genSuccessResult(SuccessEnum.SUCCESS_LOGOUT.getMessage());
    }

    @PatchMapping("/v1/user/avatar")
    public Result updateAvatar(@Valid @RequestBody UserUpdateAvatarRequest request,
                               @RequestHeader("X-User-Id") String userId) { // 🌟 直接从网关塞的 Header 拿
        userService.updateAvatar(userId, request);
        return ResultGenerator.genSuccessResult(null);
    }

    @GetMapping("/v1/user/uploadUrl")
    public Result getUploadUrl(@Valid @NotEmpty(message = "文件名称不能为空") @RequestParam("fileName") String fileName) throws Exception {
        UploadUrlResponse response = userService.getUploadUrl(fileName);
        return ResultGenerator.genSuccessResult(response);
    }

    /**
     * 🌟 JWT 双 Token 续签接口 (已加入白名单)
     * 前端携带 RefreshToken 来换取全新的一对 Token
     */
    @GetMapping("/v1/user/refreshToken")
    public Result refreshToken(HttpServletRequest request) {
        // 1. 获取前端传来的 RefreshToken
        String refreshToken = request.getHeader(ConfigEnum.AUTHORIZATION.getValue());
        ThrowUtils.throwIf(StringUtils.isBlank(refreshToken), ErrorEnum.NOT_LOGIN_ERROR);

        // 2. 解析 RefreshToken 获取 UserId (既然能解析出来，说明它没过期)
        String userId = JwtUtil.getUserIdSafe(refreshToken);
        ThrowUtils.throwIf(StringUtils.isBlank(userId), ErrorEnum.LOGIN_EXPIRED);

        // 3. 调用 Service 层，查 Redis 校验并签发全新的双 Token
        UserVO userVO = userService.refreshToken(userId, refreshToken);

        // 4. 返回带有新 token 和 refreshToken 的 userVO 给前端
        return ResultGenerator.genSuccessResult(userVO);
    }
}