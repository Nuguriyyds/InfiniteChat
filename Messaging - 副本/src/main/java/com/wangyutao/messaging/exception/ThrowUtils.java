package com.wangyutao.messaging.exception;


import com.wangyutao.messaging.model.enums.ErrorEnum;

/**
 * 异常抛出工具类：让业务代码里的条件判断变得极其优雅
 */
public class ThrowUtils {

    /**
     * 条件成立则抛出业务异常 (传入 ErrorEnum)
     * 示例：ThrowUtils.throwIf(user == null, ErrorEnum.NOT_FOUND_ERROR);
     */
    public static void throwIf(boolean condition, ErrorEnum errorEnum) {
        if (condition) {
            throw new BusinessException(errorEnum);
        }
    }

    /**
     * 条件成立则抛出业务异常 (传入 ErrorEnum 并覆盖报错信息)
     * 示例：ThrowUtils.throwIf(code != 200, ErrorEnum.SYSTEM_ERROR, "第三方短信接口调用失败");
     */
    public static void throwIf(boolean condition, ErrorEnum errorEnum, String message) {
        if (condition) {
            throw new BusinessException(errorEnum, message);
        }
    }

    /**
     * 条件成立则抛出业务异常 (完全自定义 code 和 msg)
     */
    public static void throwIf(boolean condition, int code, String message) {
        if (condition) {
            throw new BusinessException(code, message);
        }
    }
}