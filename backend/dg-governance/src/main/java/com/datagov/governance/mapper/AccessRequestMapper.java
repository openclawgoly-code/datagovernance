package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.AccessRequestEntities.AccessRequest;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccessRequestMapper extends BaseMapper<AccessRequest> {
}
