package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.GlossaryEntities.GlossaryTerm;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GlossaryTermMapper extends BaseMapper<GlossaryTerm> {
}
