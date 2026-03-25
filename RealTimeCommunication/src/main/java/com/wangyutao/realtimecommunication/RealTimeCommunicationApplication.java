package com.wangyutao.realtimecommunication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class RealTimeCommunicationApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealTimeCommunicationApplication.class, args);
    }

}
