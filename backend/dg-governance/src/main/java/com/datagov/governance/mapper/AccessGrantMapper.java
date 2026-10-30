package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.AccessRequestEntities.AccessGrant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccessGrantMapper extends BaseMapper<AccessGrant> {
}
