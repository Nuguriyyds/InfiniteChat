package com.wangyutao.authenticationservice.service;

import com.wangyutao.authenticationservice.model.dto.*;
import com.wangyutao.authenticationservice.model.vo.*;

import javax.servlet.http.HttpServletResponse;

public interface UserService {
    UserVO userLoginPwd(UserLoginPwdRequest userLoginPwdRequest, HttpServletResponse response);

    UserVO userLoginCode(UserLoginCodeRequest userLoginCodeRequest, HttpServletResponse response);

    void userRegister(UserRegisterRequest userRegisterRequest) throws InterruptedException;

    void updateAvatar(Long id, UserUpdateAvatarRequest request);

    UploadUrlResponse getUploadUrl(String fileName) throws Exception;

    void sendClientSms(UserSMSRequest userSMSRequest);

    void userLogout(UserLogOutRequest userLogOutRequest);

    UserVO refreshToken(Long userId, String clientRefreshToken);

}
