package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datagov.metadata.domain.RuleKind;
import lombok.Data;

import java.time.Instant;

/**
 * 清洗/转换规则的<b>定义</b>(功能 17)。
 *
 * <p>规则是双栖对象:这里是定义侧,执行侧是 Runtime 的 {@code RuleInterpreter}。
 *
 * <p><b>同步任务引用 ruleId,不内嵌规则实现</b>(架构风险 R6)。若把实现写进
 * 同步任务,同一条"手机号脱敏"会在十几个任务里各有一份略微不同的拷贝,
 * 修一处漏九处 —— 而这种漏改在数据脱敏场景里就是一次合规事故。
 */
@Data
@TableName("md_rule")
public class RuleEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String name;
    private RuleKind kind;
    private String description;

    /** 参数,JSON 对象字符串。键由 RuleKind.paramSpec() 规定 */
    private String paramsJson;

    /**
     * 被多少个任务引用。
     *
     * <p>冗余这个计数是为了让删除保护能一眼判断,而不是每次删除都去
     * 扫一遍所有任务定义的 config_json —— 那是个无法走索引的查询。
     * 代价是要在引用变化时维护它。
     */
    private Integer referenceCount;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    @TableLogic
    private Boolean deleted;
}
