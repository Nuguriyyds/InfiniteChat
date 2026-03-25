package com.wangyutao.authenticationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuthenticationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthenticationServiceApplication.class, args);
        // 在项目里随便找个 main 方法跑
        System.out.println(cn.hutool.crypto.digest.BCrypt.hashpw("Test@123456", cn.hutool.crypto.digest.BCrypt.gensalt(6)));

    }

}
