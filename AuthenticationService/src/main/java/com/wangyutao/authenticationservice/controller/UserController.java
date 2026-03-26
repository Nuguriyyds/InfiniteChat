package com.wangyutao.authenticationservice.controller;

import com.wangyutao.authenticationservice.common.Result;
import com.wangyutao.authenticationservice.common.ResultGenerator;
import com.wangyutao.authenticationservice.exception.ThrowUtils;
import com.wangyutao.authenticationservice.model.dto.UserLogOutRequest;
import com.wangyutao.authenticationservice.model.dto.UserLoginCodeRequest;
import com.wangyutao.authenticationservice.model.dto.UserLoginPwdRequest;
import com.wangyutao.authenticationservice.model.dto.UserRegisterRequest;
import com.wangyutao.authenticationservice.model.dto.UserSMSRequest;
import com.wangyutao.authenticationservice.model.dto.UserUpdateAvatarRequest;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import com.wangyutao.authenticationservice.model.enums.ErrorEnum;
import com.wangyutao.authenticationservice.model.enums.SuccessEnum;
import com.wangyutao.authenticationservice.model.vo.UploadUrlResponse;
import com.wangyutao.authenticationservice.model.vo.UserVO;
import com.wangyutao.authenticationservice.service.UserService;
import com.wangyutao.authenticationservice.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;

@Slf4j
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/v1/user/noToken/sms")
    public Result sendClientSms(@Valid @RequestBody UserSMSRequest userSMSRequest) {
        userService.sendClientSms(userSMSRequest);
        return ResultGenerator.genSuccessResult(SuccessEnum.SUCCESS_CODE_SEND.getMessage());
    }

    @PostMapping("/v1/user/noToken/register")
    public Result register(@Valid @RequestBody UserRegisterRequest userRegisterRequest) throws InterruptedException {
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
    public Result logout(@RequestHeader("X-User-Id") Long userId) {
        UserLogOutRequest request = new UserLogOutRequest();
        request.setUserId(userId);
        userService.userLogout(request);
        return ResultGenerator.genSuccessResult(SuccessEnum.SUCCESS_LOGOUT.getMessage());
    }

    @PatchMapping("/v1/user/avatar")
    public Result updateAvatar(@Valid @RequestBody UserUpdateAvatarRequest request,
                               @RequestHeader("X-User-Id") Long userId) {
        userService.updateAvatar(userId, request);
        return ResultGenerator.genSuccessResult(null);
    }

    @GetMapping("/v1/user/uploadUrl")
    public Result getUploadUrl(@NotEmpty(message = "文件名称不能为空") @RequestParam("fileName") String fileName) throws Exception {
        UploadUrlResponse response = userService.getUploadUrl(fileName);
        return ResultGenerator.genSuccessResult(response);
    }

    @PostMapping("/v1/user/refreshToken")
    public Result refreshToken(@RequestHeader(value = "X-Refresh-Token", required = false) String refreshTokenHeader,
                               @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return doRefreshToken(refreshTokenHeader, authorizationHeader);
    }

    @GetMapping("/v1/user/refreshToken")
    public Result refreshTokenCompat(@RequestHeader(value = "X-Refresh-Token", required = false) String refreshTokenHeader,
                                     @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return doRefreshToken(refreshTokenHeader, authorizationHeader);
    }

    private Result doRefreshToken(String refreshTokenHeader, String authorizationHeader) {
        String refreshToken = resolveRefreshToken(refreshTokenHeader, authorizationHeader);
        String userIdStr = JwtUtil.getUserIdSafe(refreshToken);
        ThrowUtils.throwIf(StringUtils.isBlank(userIdStr), ErrorEnum.LOGIN_EXPIRED);

        Long userId = Long.valueOf(userIdStr);
        UserVO userVO = userService.refreshToken(userId, refreshToken);
        return ResultGenerator.genSuccessResult(userVO);
    }

    private String resolveRefreshToken(String refreshTokenHeader, String authorizationHeader) {
        String refreshToken = StringUtils.trimToNull(refreshTokenHeader);
        if (refreshToken == null) {
            refreshToken = StringUtils.trimToNull(authorizationHeader);
        }

        if (refreshToken != null && StringUtils.startsWithIgnoreCase(refreshToken, "Bearer ")) {
            refreshToken = StringUtils.trimToNull(refreshToken.substring(7));
        }

        ThrowUtils.throwIf(StringUtils.isBlank(refreshToken), ErrorEnum.NOT_LOGIN_ERROR);
        return refreshToken;
    }
}
