package com.wangyutao.realtimecommunication.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class HeartBody implements Serializable {

    private Integer type;

    private String message;
}
