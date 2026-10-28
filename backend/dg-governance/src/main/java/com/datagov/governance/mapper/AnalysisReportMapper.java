package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * 分析报告聚合根。
 *
 * <p>三个自定义语句对应三个必须原子完成的动作:
 * <ul>
 *   <li>{@link #insertIfAbsent} —— 幂等。先查后插在并发下会双写,
 *       而 PostgreSQL 在唯一约束冲突后会让整个事务进入 aborted 状态,
 *       所以不能像 MySQL 那样 catch DuplicateKeyException 再查。
 *       {@code ON CONFLICT DO NOTHING} 是唯一既不中断事务、又只写一次的做法</li>
 *   <li>{@link #compareAndBumpVersion} —— 版本号并发控制。条件更新比悲观锁轻,
 *       比 {@code @Version} 少一个需要维护的列(报告写入频率本来就低)</li>
 *   <li>{@link #markRetracted} —— 撤回同样用条件更新,避免"先读后写"覆盖
 *       另一条并发撤回</li>
 * </ul>
 *
 * <p><b>没有 updateContent</b>:版本表只 insert。不可变靠边界,不靠触发器。
 */
@Mapper
public interface AnalysisReportMapper extends BaseMapper<AnalysisReport> {

    /**
     * 插入报告;{@code (workspace_id, correlation_id)} 冲突时什么也不做。
     *
     * @return 1 = 插入成功;0 = 幂等键已存在,调用方应把既有报告查回来
     */
    @Insert("""
            INSERT INTO gv_analysis_report
              (id, workspace_id, subject_type, subject_id, title, state, latest_version,
               correlation_id, created_by, created_at)
            VALUES
              (#{id}, #{workspaceId}, #{subjectType}, #{subjectId}, #{title}, #{state},
               #{latestVersion}, #{correlationId}, #{createdBy}, #{createdAt})
            ON CONFLICT (workspace_id, correlation_id) DO NOTHING
            """)
    int insertIfAbsent(AnalysisReport report);

    /**
     * 抢占下一个版本号。
     *
     * @return 1 = 抢到;0 = 当前版本已被别人推进或报告已撤回,调用方应重读后重试
     */
    @Update("""
            UPDATE gv_analysis_report
               SET latest_version = #{next}
             WHERE id = #{id}
               AND workspace_id = #{workspaceId}
               AND latest_version = #{expected}
               AND state = 'PUBLISHED'
            """)
    int compareAndBumpVersion(@Param("id") String id,
                              @Param("workspaceId") String workspaceId,
                              @Param("expected") int expected,
                              @Param("next") int next);

    /**
     * 撤回。
     *
     * @return 1 = 本次完成撤回;0 = 已经撤回(幂等)或报告不存在
     */
    @Update("""
            UPDATE gv_analysis_report
               SET state = 'RETRACTED',
                   retracted_at = #{retractedAt},
                   retracted_by = #{retractedBy}
             WHERE id = #{id}
               AND workspace_id = #{workspaceId}
               AND state = 'PUBLISHED'
            """)
    int markRetracted(@Param("id") String id,
                      @Param("workspaceId") String workspaceId,
                      @Param("retractedAt") Instant retractedAt,
                      @Param("retractedBy") String retractedBy);
}
