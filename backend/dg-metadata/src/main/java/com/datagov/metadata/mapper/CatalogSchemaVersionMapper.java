package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSchemaVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogSchemaVersionMapper extends BaseMapper<CatalogSchemaVersion> {
}
