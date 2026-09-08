package com.datagov.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.platform.entity.PlatformEntities.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
