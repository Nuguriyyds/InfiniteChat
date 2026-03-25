package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("contact")
public class Friend {

    @TableId
    private Long id;

    private Long userId;

    private Long friendId;

    private String remark;

    /**
     * 0-申请中, 1-正常, 2-拉黑, 3-删除
     */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}