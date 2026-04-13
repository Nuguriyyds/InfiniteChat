package com.wangyutao.messaging.model.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class CreateGroupRequest {

    private String name;

    @NotEmpty(message = "成员列表不能为空")
    private List<Long> memberIds;
}
