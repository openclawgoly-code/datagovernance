package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.QualityEntities.QualityRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityRuleMapper extends BaseMapper<QualityRule> {
}
