package com.wangyutao.realtimecommunication.enums;

import lombok.Getter;
import org.springframework.util.ObjectUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public enum ConfigEnum {

    SMS_ACCESS_KEY_ID("smsAccessKeyId", "LTAI5t9w3ctvvetXtCjqWZ8s"),
    SMS_ACCESS_KEY_SECRET("smsAccessKeySecret","rGp3V1PL0i72DrdDnwtDXUa0xlLZbi"),
    SMS_SIG_NAME("smsSigName","Zzw"),
    SMS_TEMPLATE_CODE("smsTemplateCode","SMS_468395208"),
    TOKEN_SECRET_KEY("tokenSecretKey","infinitechat_secret_key_2026_safe_and_secure_for_im_system_64_bits"),
    PASSWORD_SALT("passwordSalt","goat"),
    WX_STATE("wxState","goat"),
    WORKED_ID("workedId","1"),
    DATACENTER_ID("DATACENTER_ID","1"),
    IMAGE_URI("imageUri","http://47.113.96.105/img/avatar/"),
    IMAGE_PATH("imagePath", "/home/img/avatar/"),
    NETTY_SERVER_HEAD("nettyServerHead","Nacos:"),
    REDIS_CONVERT_SEND("redisConvertSend","userLogout"),
    RTC_NODE_LOAD_ZSET("rtcNodeLoadZset", "im:rtc:load");

    private final String text;

    private final String value;

    ConfigEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }


    public static List<String> getValues() {
        return Arrays.stream(ConfigEnum.values()).map(ConfigEnum::getValue).collect(Collectors.toList());
    }


    public static ConfigEnum getEnumByValue(String value) {
        if (ObjectUtils.isEmpty(value)) {
            return null;
        }
        for (ConfigEnum anEnum : ConfigEnum.values()) {
            if (anEnum.getValue().equals(value)) {
                return anEnum;
            }

        }
        return null;
    }
}
