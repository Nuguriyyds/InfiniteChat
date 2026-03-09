package com.wangyutao.authenticationservice.utils;

import cn.hutool.core.util.StrUtil;
import com.wangyutao.authenticationservice.constants.OSSConstant;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Service
public class OSSUtil {
    @Resource
    private MinioClient minioClient;

    @Value("${minio.url}")
    private String url;

    /***
     * @MethodName uploadUrl
     * @Description 生成预授权上传文件连接
     * @param: bucketName
     * @param: objectName
     * @param: expires
     * @return: java.lang.String
     * @Date 2025/1/11 12:08
     */
    @SneakyThrows
    public String uploadUrl(String bucketName, String objectName, Integer expires) {

        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(expires, TimeUnit.SECONDS)
                        .build());
    }

    /***
     * @MethodName downUrl
     * @Description 获取下载文件地址
     * @param: fileName
     * @return: java.lang.String
     * @Date 2025/1/12 01:15
     */
    public String downUrl(String fileName) {

        return url + StrUtil.SLASH + OSSConstant.BUCKET_NAME + StrUtil.SLASH + fileName;
    }
}
