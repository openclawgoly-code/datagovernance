package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.app.insight.entity.InsightQueryLog;
import com.datagov.app.insight.mapper.InsightQueryLogMapper;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.platform.dto.CredentialSecret;
import com.datagov.platform.service.CredentialService;
import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 智能问数网关。
 *
 * <p>平台不生成 SQL,只做四件 SuperSonic 不该负责的事:
 * <ol>
 *   <li>身份与空间:从平台 JWT/WorkspaceContext 取用户,不用 SuperSonic 的登录态</li>
 *   <li>结果脱敏:按平台敏感标签兜底遮蔽 PII 列</li>
 *   <li>行数上限:避免把上游 1000 行原样吐给前端</li>
 *   <li>审计与事实记录:谁问了什么、生成了什么 SQL、遮蔽了哪些列</li>
 * </ol>
 */
@Service
public class InsightQueryService {

    private static final Logger log = LoggerFactory.getLogger(InsightQueryService.class);

    private final SuperSonicClient client;
    private final InsightProperties properties;
    private final InsightQueryLogMapper logMapper;
    private final SensitivityService sensitivityService;
    private final AuditService auditService;
    private final InsightSupersonicMappingService mappingService;
    private final CredentialService credentialService;

    public InsightQueryService(SuperSonicClient client,
                               InsightProperties properties,
                               InsightQueryLogMapper logMapper,
                               SensitivityService sensitivityService,
                               AuditService auditService,
                               InsightSupersonicMappingService mappingService,
                               CredentialService credentialService) {
        this.client = client;
        this.properties = properties;
        this.logMapper = logMapper;
        this.sensitivityService = sensitivityService;
        this.auditService = auditService;
        this.mappingService = mappingService;
        this.credentialService = credentialService;
    }

    private record ClientCredentials(String username, String password) {
    }

    public record AskRequest(String question, Long dataSetId, Integer agentId) {
    }

    public record Answer(
            String queryId,
            String state,
            String sql,
            List<SuperSonicClient.Column> columns,
            List<Map<String, Object>> rows,
            int rowCount,
            boolean truncated,
            long elapsedMs,
            List<String> maskedColumns,
            String summary
    ) {
    }

    public record ExportFile(String filename, byte[] content, int rowCount,
                             List<String> maskedColumns) {
    }

    public record QueryLogView(
            String id,
            String username,
            String question,
            String queryState,
            String generatedSql,
            Integer rowCount,
            Long elapsedMs,
            String maskedColumns,
            boolean succeeded,
            String errorMessage,
            Instant createdAt
    ) {
        static QueryLogView from(InsightQueryLog value) {
            return new QueryLogView(value.getId(), value.getUsername(), value.getQuestion(),
                    value.getQueryState(), value.getGeneratedSql(), value.getRowCount(),
                    value.getElapsedMs(), value.getMaskedColumns(),
                    Boolean.TRUE.equals(value.getSucceeded()), value.getErrorMessage(),
                    value.getCreatedAt());
        }
    }

    public List<SuperSonicClient.DatasetOption> datasets() {
        Caller caller = WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        ClientCredentials credentials = credentials(caller);
        return client.datasets(credentials.username(), credentials.password());
    }

    public Answer ask(AskRequest request) {
        Caller caller = WorkspaceContext.require();
        // 问数事实必须落在某个空间里;平台管理员在未选空间时也不能匿名查业务数据。
        WorkspaceContext.requireWorkspaceId();
        ClientCredentials credentials = credentials(caller);
        String question = request == null ? null : request.question();
        if (question == null || question.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "问题不能为空");
        }
        question = question.trim();
        if (question.length() > 2000) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "问题不能超过 2000 字");
        }
        int agentId = request.agentId() == null || request.agentId() <= 0
                ? properties.getAgentId() : request.agentId();
        long datasetId = request.dataSetId() == null || request.dataSetId() <= 0
                ? properties.getDefaultDatasetId() : request.dataSetId();

        long started = System.currentTimeMillis();
        InsightQueryLog fact = newFact(caller, question, agentId, datasetId);
        try {
            SuperSonicClient.AskResult result = client.ask(question, agentId, datasetId,
                    credentials.username(), credentials.password());
            if (!"SUCCESS".equalsIgnoreCase(result.state())) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                        "问数未成功: " + nullSafe(result.error(), result.state()));
            }
            List<SensitiveColumnView> sensitive = sensitivityService.sensitiveColumnNames();
            InsightMasking.Masked masked = InsightMasking.mask(result.rows(),
                    result.columns(), result.sql(), sensitive);

            boolean truncated = masked.rows().size() > properties.getMaxRows();
            List<Map<String, Object>> rows = truncated
                    ? masked.rows().subList(0, properties.getMaxRows())
                    : masked.rows();
            long elapsed = System.currentTimeMillis() - started;

            fact.setQueryState(result.state());
            fact.setGeneratedSql(truncate(result.sql(), 20000));
            fact.setRowCount(rows.size());
            fact.setElapsedMs(elapsed);
            fact.setMaskedColumns(truncate(String.join(",", masked.maskedColumns()), 1000));
            fact.setSucceeded(true);
            logMapper.insert(fact);

            auditService.record(new AuditEntry(
                    AuditService.EXECUTE,
                    "INSIGHT_QUERY",
                    fact.getId(),
                    truncate(question, 256),
                    "GOVERNANCE",
                    true,
                    null,
                    "POST /api/v1/insight/ask",
                    "dataset=" + datasetId + "; masked=" + masked.maskedColumns()
                            + "; rows=" + rows.size() + "; elapsedMs=" + elapsed));

            return new Answer(result.queryId(), result.state(), result.sql(),
                    result.columns(), rows, rows.size(), truncated, elapsed,
                    masked.maskedColumns(), result.summary());
        } catch (RuntimeException ex) {
            long elapsed = System.currentTimeMillis() - started;
            fact.setSucceeded(false);
            fact.setElapsedMs(elapsed);
            fact.setErrorMessage(truncate(ex.getMessage(), 1000));
            logMapper.insert(fact);
            auditService.record(new AuditEntry(
                    AuditService.EXECUTE,
                    "INSIGHT_QUERY",
                    fact.getId(),
                    truncate(question, 256),
                    "GOVERNANCE",
                    false,
                    ex instanceof BizException biz ? biz.errorCode().code() : "SYS_INTERNAL_ERROR",
                    "POST /api/v1/insight/ask",
                    "dataset=" + datasetId + "; elapsedMs=" + elapsed));
            log.warn("智能问数失败 workspace={} user={} dataset={}",
                    caller.workspaceId(), caller.username(), datasetId, ex);
            if (ex instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "智能问数失败: " + ex.getMessage(), null, ex);
        }
    }

    public ExportFile export(AskRequest request) {
        WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        Answer answer = ask(request);
        List<String> keys = answer.columns().isEmpty()
                ? (answer.rows().isEmpty() ? List.of() : List.copyOf(answer.rows().get(0).keySet()))
                : answer.columns().stream()
                        .map(column -> column.bizName() == null ? column.name() : column.bizName())
                        .toList();
        List<String> labels = answer.columns().isEmpty()
                ? keys
                : answer.columns().stream().map(SuperSonicClient.Column::name).toList();

        StringBuilder csv = new StringBuilder("\uFEFF");
        // 表头用业务名(状态/订单数),值仍按 key 取值;下游导入时列名可读。
        csv.append(labels.stream().map(InsightQueryService::csvCell)
                .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for (Map<String, Object> row : answer.rows()) {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvCell(row.get(keys.get(i))));
            }
            csv.append("\r\n");
        }
        // labels 目前只用于审计/未来加表头别名;CSV 表头用真实 key,避免
        // “中文表头 + 英文值”在下游导入时对不上列。
        auditService.record(new AuditEntry(
                AuditService.EXPORT,
                "INSIGHT_EXPORT",
                Ids.of("inx"),
                truncate(request == null ? null : request.question(), 256),
                "GOVERNANCE",
                true,
                null,
                "POST /api/v1/insight/export",
                "rows=" + answer.rowCount() + "; masked=" + answer.maskedColumns()
                        + "; labels=" + labels));
        return new ExportFile("insight-" + System.currentTimeMillis() + ".csv",
                csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                answer.rowCount(), answer.maskedColumns());
    }

    public ExportFile exportXlsx(AskRequest request) {
        WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        Answer answer = ask(request);
        List<String> keys = answer.columns().isEmpty()
                ? (answer.rows().isEmpty() ? List.of() : List.copyOf(answer.rows().get(0).keySet()))
                : answer.columns().stream()
                        .map(column -> column.bizName() == null ? column.name() : column.bizName())
                        .toList();
        List<String> labels = answer.columns().isEmpty()
                ? keys
                : answer.columns().stream().map(SuperSonicClient.Column::name).toList();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("问数结果");
            var header = sheet.createRow(0);
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            for (int i = 0; i < labels.size(); i++) {
                var cell = header.createCell(i);
                cell.setCellValue(labels.get(i));
                cell.setCellStyle(headerStyle);
            }
            for (int rowIndex = 0; rowIndex < answer.rows().size(); rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                Map<String, Object> source = answer.rows().get(rowIndex);
                for (int i = 0; i < keys.size(); i++) {
                    Object value = source.get(keys.get(i));
                    row.createCell(i).setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            for (int i = 0; i < labels.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);
            auditService.record(new AuditEntry(
                    AuditService.EXPORT,
                    "INSIGHT_EXPORT",
                    Ids.of("inx"),
                    truncate(request == null ? null : request.question(), 256),
                    "GOVERNANCE",
                    true,
                    null,
                    "POST /api/v1/insight/export/xlsx",
                    "format=xlsx; rows=" + answer.rowCount()
                            + "; masked=" + answer.maskedColumns()));
            return new ExportFile("insight-" + System.currentTimeMillis() + ".xlsx",
                    output.toByteArray(), answer.rowCount(), answer.maskedColumns());
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "生成 XLSX 失败: " + e.getMessage(), null, e);
        }
    }

    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")
                || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public PageResult<QueryLogView> history(long page, long size) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Page<InsightQueryLog> result = logMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<InsightQueryLog>()
                        .eq(InsightQueryLog::getWorkspaceId, workspaceId)
                        .orderByDesc(InsightQueryLog::getCreatedAt));
        return PageResult.of(result.getRecords().stream().map(QueryLogView::from).toList(),
                result.getTotal(), page, size);
    }

    private ClientCredentials credentials(Caller caller) {
        var mapping = mappingService.find(caller.workspaceId(), caller.username());
        if (mapping != null) {
            CredentialSecret secret = credentialService.resolveSecret(
                    caller.workspaceId(), mapping.getCredentialId());
            if (secret.secret() == null || secret.secret().isBlank()) {
                throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED,
                        "问数账号映射的凭据没有口令");
            }
            return new ClientCredentials(mapping.getSupersonicUsername(), secret.secret());
        }
        if (properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED,
                    "当前用户没有 SuperSonic 账号映射,且未配置服务账号");
        }
        return new ClientCredentials(properties.getUsername(), properties.getPassword());
    }

    private InsightQueryLog newFact(Caller caller, String question, int agentId, long datasetId) {
        InsightQueryLog fact = new InsightQueryLog();
        fact.setId(Ids.of("inq"));
        fact.setWorkspaceId(caller.workspaceId());
        fact.setUserId(caller.userId());
        fact.setUsername(caller.username());
        fact.setQuestion(truncate(question, 2000));
        fact.setAgentId(agentId);
        fact.setDatasetId(datasetId);
        fact.setSucceeded(false);
        fact.setCreatedAt(Instant.now());
        return fact;
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
