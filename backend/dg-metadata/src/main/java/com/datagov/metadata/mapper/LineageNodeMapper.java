package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LineageNodeMapper extends BaseMapper<LineageNode> {
}
