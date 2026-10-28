package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.SensitivityEntities.ScanFinding;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScanFindingMapper extends BaseMapper<ScanFinding> {
}
