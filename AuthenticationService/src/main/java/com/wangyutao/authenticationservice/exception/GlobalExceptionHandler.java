package com.wangyutao.authenticationservice.exception;

import com.wangyutao.authenticationservice.common.Result;
import com.wangyutao.authenticationservice.common.ResultGenerator;
import com.wangyutao.authenticationservice.model.enums.ErrorEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result businessExceptionHandler(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常: uri={}, code={}, message={}", request.getRequestURI(), e.getCode(), e.getMessage());
        return ResultGenerator.genFailResult(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = extractFirstBindingMessage(e.getBindingResult().getFieldError());
        log.warn("请求体校验失败: uri={}, message={}", request.getRequestURI(), message);
        return ResultGenerator.genFailResult(ErrorEnum.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    public Result bindExceptionHandler(BindException e, HttpServletRequest request) {
        String message = extractFirstBindingMessage(e.getBindingResult().getFieldError());
        log.warn("参数绑定失败: uri={}, message={}", request.getRequestURI(), message);
        return ResultGenerator.genFailResult(ErrorEnum.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result constraintViolationExceptionHandler(ConstraintViolationException e, HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .filter(StringUtils::isNotBlank)
                .orElse(ErrorEnum.PARAMS_ERROR.getMsg());
        log.warn("方法参数校验失败: uri={}, message={}", request.getRequestURI(), message);
        return ResultGenerator.genFailResult(ErrorEnum.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class
    })
    public Result badRequestExceptionHandler(Exception e, HttpServletRequest request) {
        String message = StringUtils.defaultIfBlank(e.getMessage(), ErrorEnum.PARAMS_ERROR.getMsg());
        log.warn("请求参数异常: uri={}, message={}", request.getRequestURI(), message);
        return ResultGenerator.genFailResult(ErrorEnum.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(Exception.class)
    public Result runtimeExceptionHandler(Exception e, HttpServletRequest request) {
        log.error("系统异常: uri={}", request.getRequestURI(), e);
        return ResultGenerator.genFailResult(ErrorEnum.SYSTEM_ERROR.getCode(), ErrorEnum.SYSTEM_ERROR.getMsg());
    }

    private String extractFirstBindingMessage(FieldError fieldError) {
        if (fieldError == null || StringUtils.isBlank(fieldError.getDefaultMessage())) {
            return ErrorEnum.PARAMS_ERROR.getMsg();
        }
        return fieldError.getDefaultMessage();
    }
}
