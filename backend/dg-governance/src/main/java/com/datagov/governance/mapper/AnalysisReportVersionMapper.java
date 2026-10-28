package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReportVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分析报告版本。
 *
 * <p>刻意只有 {@link BaseMapper} 的读与 insert —— 没有 update,没有 delete。
 * 一个能被改写的结论版本无法回答"当时到底写了什么"。
 */
@Mapper
public interface AnalysisReportVersionMapper extends BaseMapper<AnalysisReportVersion> {
}
