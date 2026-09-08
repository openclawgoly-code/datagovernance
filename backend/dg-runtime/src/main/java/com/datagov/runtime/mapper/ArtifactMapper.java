package com.datagov.runtime.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.runtime.entity.ArtifactEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArtifactMapper extends BaseMapper<ArtifactEntity> {
}
