package com.wangyutao.messaging.exception;

import com.wangyutao.messaging.model.enums.ErrorEnum;
import com.wangyutao.messaging.model.vo.BaseResponse;
import com.wangyutao.messaging.utils.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice // 🌟 相当于 @ControllerAdvice + @ResponseBody
public class GlobalExceptionHandler {

    /**
     * 1. 拦截业务异常 (绝大部分 ThrowUtils 抛出的都是这个)
     * 只要代码里执行了 ThrowUtils.throwIf(..., ErrorEnum.XXX)，就会被这里精准接住！
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e, HttpServletRequest request) {
        log.warn("⚠️ 业务异常拦截 [路径: {}] - 错误码: {}, 错误信息: {}",
                request.getRequestURI(), e.getCode(), e.getMessage());

        // 🌟 返回你们项目定义的统一响应体 (BaseResponse / Result)
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 2. 拦截参数校验异常 (可选)
     * 如果你以后在 Controller 用了 @Validated 和 @NotBlank，参数不合法时会走这里
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public BaseResponse<?> illegalArgumentExceptionHandler(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("⚠️ 参数校验异常 [路径: {}] - 错误信息: {}", request.getRequestURI(), e.getMessage());
        return ResultUtils.error(ErrorEnum.PARAMS_ERROR.getCode(), e.getMessage());
    }

    /**
     * 3. 终极兜底：拦截所有未知的系统异常 (空指针、数组越界、SQL 语法错误等)
     * 绝不能把详细的堆栈报错直接扔给前端，必须统一包装成“系统内部错误”
     */
    @ExceptionHandler(Exception.class)
    public BaseResponse<?> runtimeExceptionHandler(Exception e, HttpServletRequest request) {
        // 未知异常属于严重错误，必须打 error 日志并记录完整堆栈，方便查 Bug
        log.error("❌ 系统未知异常 [路径: {}] - 异常详情: ", request.getRequestURI(), e);

        return ResultUtils.error(ErrorEnum.SYSTEM_ERROR.getCode(), ErrorEnum.SYSTEM_ERROR.getMsg());
    }
}