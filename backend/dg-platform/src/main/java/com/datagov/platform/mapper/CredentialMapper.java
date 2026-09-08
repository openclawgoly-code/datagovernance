package com.datagov.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.platform.entity.PlatformEntities.Credential;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CredentialMapper extends BaseMapper<Credential> {
}
