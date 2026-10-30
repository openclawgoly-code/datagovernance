package com.datagov.app.insight;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.app.insight.InsightQueryService.Answer;
import com.datagov.app.insight.InsightQueryService.AskRequest;
import com.datagov.app.insight.InsightQueryService.QueryLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 智能问数(Phase 2 网关)。
 *
 * <p>前端只认这个接口,不直连 SuperSonic。这样问数的身份、脱敏、行数上限和
 * 审计都由平台统一承担;将来把 SuperSonic 换掉,前端不需要改。
 */
@RestController
@RequestMapping("/api/v1/insight")
@Tag(name = "智能问数", description = "受治理的自然语言问数网关")
public class InsightController {

    private final InsightQueryService queryService;
    private final InsightProperties properties;

    public InsightController(InsightQueryService queryService, InsightProperties properties) {
        this.queryService = queryService;
        this.properties = properties;
    }

    @PostMapping("/ask")
    @RequirePermission("insight:ask")
    @Operation(summary = "自然语言问数",
            description = "转发 SuperSonic,按平台敏感标签脱敏,并记录查询事实")
    public ApiResponse<Answer> ask(@RequestBody AskRequest request) {
        return ApiResponse.ok(queryService.ask(request));
    }

    @PostMapping("/export")
    @RequirePermission("insight:export")
    @Operation(summary = "导出问数结果",
            description = "重新执行同一问题并输出 CSV;导出单独授权,结果同样经过脱敏和审计")
    public ResponseEntity<byte[]> export(@RequestBody AskRequest request) {
        var file = queryService.export(request);
        String encodedName = java.net.URLEncoder.encode(file.filename(),
                StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename()
                                + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file.content());
    }

    @PostMapping("/export/xlsx")
    @RequirePermission("insight:export")
    @Operation(summary = "导出问数结果为 XLSX")
    public ResponseEntity<byte[]> exportXlsx(@RequestBody AskRequest request) {
        var file = queryService.exportXlsx(request);
        String encodedName = java.net.URLEncoder.encode(file.filename(),
                StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename()
                                + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }

    @GetMapping("/datasets")
    @RequirePermission("insight:ask")
    @Operation(summary = "可选数据集", description = "来自 SuperSonic 的主题域/数据集树")
    public ApiResponse<List<SuperSonicClient.DatasetOption>> datasets() {
        return ApiResponse.ok(queryService.datasets());
    }

    @GetMapping("/queries")
    @RequirePermission("insight:read")
    @Operation(summary = "问数记录", description = "当前空间的问数事实,用于审计/质量复盘")
    public ApiResponse<PageResult<QueryLogView>> queries(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean succeeded,
            @RequestParam(required = false) String username) {
        return ApiResponse.ok(queryService.history(page, size, keyword, succeeded, username));
    }

    @GetMapping("/config")
    @RequirePermission("insight:ask")
    @Operation(summary = "问数能力探测", description = "前端用它判断网关是否可用")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(Map.of(
                "enabled", true,
                "masking", true,
                "maxRows", properties.getMaxRows(),
                "message", "问数请求请走 POST /api/v1/insight/ask"));
    }
}
