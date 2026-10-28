package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.SensitivityEntities.ScanRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScanRunMapper extends BaseMapper<ScanRun> {
}
