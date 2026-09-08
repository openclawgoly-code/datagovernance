package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.DataSourceVersionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataSourceVersionMapper extends BaseMapper<DataSourceVersionEntity> {
}
