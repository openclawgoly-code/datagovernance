package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogSourceMapper extends BaseMapper<CatalogSource> {
}
