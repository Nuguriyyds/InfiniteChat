package com.wangyutao.authenticationservice.model.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UploadUrlResponse {
    // 上传文件的地址
    public String uploadUrl;

    // 下载文件的地址
    public String downloadUrl;
}
