package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.GovernanceEntities.AlertChannel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlertChannelMapper extends BaseMapper<AlertChannel> {
}
