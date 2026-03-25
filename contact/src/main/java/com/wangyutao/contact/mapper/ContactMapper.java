package com.wangyutao.contact.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.contact.model.entity.Contact;
import org.apache.ibatis.annotations.Mapper;

/**
 * 联系人 Mapper
 */
@Mapper
public interface ContactMapper extends BaseMapper<Contact> {
}
