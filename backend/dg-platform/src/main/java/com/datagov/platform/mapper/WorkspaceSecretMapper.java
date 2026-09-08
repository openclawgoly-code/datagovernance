package com.datagov.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.platform.entity.PlatformEntities.WorkspaceSecret;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkspaceSecretMapper extends BaseMapper<WorkspaceSecret> {
}
