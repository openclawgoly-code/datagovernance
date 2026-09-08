package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.entity.DataSourceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自定义 SQL 查询的编排(功能 7 的后半句)。
 *
 * <p><b>执行归 Data Space,编排归这里</b>:只有 Metadata 知道 datasourceId
 * 对应哪个数据源、它是否已验证、凭据在哪 —— 这些都是定义态知识。
 * Data Space 只接收一个组装好的 {@link ConnectionConfig} 和一条 SQL。
 *
 * <p>这个服务很薄,但它存在的意义在于:它是"谁能对业务库执行任意查询"
 * 这个问题的<b>唯一入口</b>。P4 的 Governance 要给数据查询加审计与配额时,
 * 只需要在这一个类上做文章。
 */
@Service
public class DataQueryService {

    private static final Logger log = LoggerFactory.getLogger(DataQueryService.class);

    private final DataSourceService dataSourceService;
    private final DataAccessGateway gateway;
    private final ConnectionConfigAssembler assembler;

    public DataQueryService(DataSourceService dataSourceService,
                            DataAccessGateway gateway,
                            ConnectionConfigAssembler assembler) {
        this.dataSourceService = dataSourceService;
        this.gateway = gateway;
        this.assembler = assembler;
    }

    public SqlQuery.Result execute(String dataSourceId, SqlQuery.Request request) {
        DataSourceEntity entity = dataSourceService.requireInWorkspace(dataSourceId);

        // 与结构浏览同一条规则:没验证过连通性就去打生产库是不负责任的。
        // 对查询更是如此 —— 它执行的是用户手写的语句。
        if (!entity.getStatus().isUsable()) {
            throw new BizException(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE,
                    "数据源当前状态为 %s,需先通过连通性测试才能执行查询"
                            .formatted(entity.getStatus()));
        }

        ConnectionConfig config = assembler.assemble(entity, entity.getWorkspaceId());

        // 审计留痕的雏形:记谁、在哪个空间、对哪个数据源、执行了什么。
        // P4 由 Governance 接管为正式的 AuditRecord;在那之前日志是唯一的痕迹,
        // 而"平台曾对业务库执行过哪些语句"是一定会被问到的问题。
        log.info("执行自定义查询 workspace={} datasource={} user={} sql={}",
                entity.getWorkspaceId(), dataSourceId,
                WorkspaceContext.require().username(), oneLine(request.sql()));

        SqlQuery.Result result = gateway.executeQuery(entity.getType(), config, request);

        log.info("查询完成 datasource={} 返回 {} 行{} 耗时 {}ms",
                dataSourceId, result.rowCount(),
                result.truncated() ? "(已截断)" : "", result.elapsedMillis());
        return result;
    }

    /** 日志里的 SQL 压成一行并截断 —— 多行 SQL 会把日志切得没法看。 */
    private static String oneLine(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 500 ? flat : flat.substring(0, 500) + "…";
    }
}
