package com.wangyutao.offlineservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@MapperScan("com.wangyutao.offlineservice.mapper")
public class OfflineServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfflineServiceApplication.class, args);
    }
}
