package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.RegistryEntities.RegistryArtifactVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RegistryArtifactVersionMapper extends BaseMapper<RegistryArtifactVersion> {
}
