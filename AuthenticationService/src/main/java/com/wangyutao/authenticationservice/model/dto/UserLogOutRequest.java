package com.wangyutao.authenticationservice.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserLogOutRequest implements Serializable {

    private Long userId;
}
