package com.datagov.runtime.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.runtime.entity.RuntimeEntities.Executor;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExecutorMapper extends BaseMapper<Executor> {
}
