package com.wangyutao.authenticationservice.utils;

import java.util.Random;

public class RandomNumUtil {

    public String getRandomNum() {
        Random random = new Random();
        int randomNum = random.nextInt(900000) + 100000;
        String code = String.format("%06d", randomNum);
        return code;
    }
}
