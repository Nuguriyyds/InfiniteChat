package com.wangyutao.messaging.utils; // 根据你的目录结构调整


import com.wangyutao.messaging.model.enums.ErrorEnum;
import com.wangyutao.messaging.model.vo.BaseResponse;

/**
 * 返回工具类
 */
public class ResultUtils {

    /**
     * 成功
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(20000, data, "ok"); // 假设 20000 是你们前端约定的成功码
    }

    /**
     * 失败 (传入自定义错误码和信息)
     */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 失败 (直接传入 ErrorEnum)
     */
    public static BaseResponse<?> error(ErrorEnum errorEnum) {
        return new BaseResponse<>(errorEnum.getCode(), errorEnum.getMsg());
    }

    /**
     * 失败 (传入 ErrorEnum，但覆盖默认的 message)
     */
    public static BaseResponse<?> error(ErrorEnum errorEnum, String message) {
        return new BaseResponse<>(errorEnum.getCode(), null, message);
    }
}