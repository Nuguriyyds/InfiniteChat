package com.wangyutao.authenticationservice.utils;

import java.util.Random;

public class NicknameGeneratorUtil {
    String [] adjectives = {"粘人的", "聪明的", "可爱的", "勇敢的", "懒散的", "活泼的", "温柔的", "狡猾的", "快乐的", "笨拙的"};
    String [] animals = {"猫", "狗", "兔子", "熊猫", "老虎", "狮子", "长颈鹿", "大象", "企鹅", "吗喽", "乌鸦", "牛马"};
    public String generateNickname() {
        Random random = new Random();
        // 随机选择一个形容词
        String adjective = adjectives[random.nextInt(adjectives.length)];
        // 随机选择一个动物
        String animal = animals[random.nextInt(animals.length)];
        return adjective + animal;
    }
}
