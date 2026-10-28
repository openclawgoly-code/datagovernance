package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LineageEdgeMapper extends BaseMapper<LineageEdge> {
}
