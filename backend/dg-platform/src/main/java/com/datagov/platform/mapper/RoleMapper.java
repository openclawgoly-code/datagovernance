package com.datagov.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.platform.entity.PlatformEntities.Role;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
