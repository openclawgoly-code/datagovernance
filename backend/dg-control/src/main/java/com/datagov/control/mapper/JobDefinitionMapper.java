package com.datagov.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobDefinitionMapper extends BaseMapper<JobDefinition> {
}
