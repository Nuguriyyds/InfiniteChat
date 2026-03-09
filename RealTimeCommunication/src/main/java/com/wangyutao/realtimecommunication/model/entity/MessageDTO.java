package com.wangyutao.realtimecommunication.model.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@JsonPropertyOrder({ "type", "data" })
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(chain = true)
public class MessageDTO {

    // PushTypeEnum 的 code
    private Integer type;

    // 具体的推送数据
    private Object data;
}
