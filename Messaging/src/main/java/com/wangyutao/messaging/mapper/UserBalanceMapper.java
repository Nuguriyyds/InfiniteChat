package com.wangyutao.messaging.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.messaging.model.entity.UserBalance;

import java.math.BigDecimal;


/**
* @author yutao
* @description 针对表【user_balance(用户钱包余额表)】的数据库操作Mapper
* @createDate 2026-03-10 11:41:57
* @Entity com.wangyutao.messaging.model.entity.UserBalance
*/
@Mapper
public interface UserBalanceMapper extends BaseMapper<UserBalance> {
    @Update("UPDATE user_balance SET balance = balance - #{amount}, updated_at = NOW() WHERE user_id = #{userId} AND balance >= #{amount}")
    int deductBalanceAtomically(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Update("UPDATE user_balance SET balance = balance + #{amount}, updated_at = NOW() WHERE user_id = #{userId}")
    int addBalanceAtomically(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
