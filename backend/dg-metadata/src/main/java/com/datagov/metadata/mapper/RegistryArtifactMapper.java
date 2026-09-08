package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.RegistryEntities.RegistryArtifact;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RegistryArtifactMapper extends BaseMapper<RegistryArtifact> {
}
