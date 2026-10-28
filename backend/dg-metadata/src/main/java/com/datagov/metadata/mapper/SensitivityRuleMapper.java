package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.SensitivityEntities.SensitivityRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SensitivityRuleMapper extends BaseMapper<SensitivityRule> {
}
