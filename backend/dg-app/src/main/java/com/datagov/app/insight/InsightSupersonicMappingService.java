package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.app.insight.entity.InsightSupersonicMapping;
import com.datagov.app.insight.mapper.InsightSupersonicMappingMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 平台用户 → SuperSonic 账号映射。
 *
 * <p>只存映射与 credentialId;口令由 Platform CredentialService 托管。
 * 没有映射时回退到 dg.insight 的服务账号(向后兼容),但生产应给每个
 * 业务用户建映射,而不是共用 admin。
 */
@Service
public class InsightSupersonicMappingService {

    private final InsightSupersonicMappingMapper mapper;

    public InsightSupersonicMappingService(InsightSupersonicMappingMapper mapper) {
        this.mapper = mapper;
    }

    public record MappingView(String id, String platformUsername, String supersonicUsername,
                              String credentialId, boolean enabled, String createdBy,
                              Instant updatedAt) {
        static MappingView from(InsightSupersonicMapping value) {
            return new MappingView(value.getId(), value.getPlatformUsername(),
                    value.getSupersonicUsername(), value.getCredentialId(),
                    Boolean.TRUE.equals(value.getEnabled()), value.getCreatedBy(),
                    value.getUpdatedAt());
        }
    }

    public record MappingRequest(String platformUsername, String supersonicUsername,
                                 String credentialId) {
    }

    public List<MappingView> list() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return mapper.selectList(new LambdaQueryWrapper<InsightSupersonicMapping>()
                        .eq(InsightSupersonicMapping::getWorkspaceId, workspaceId)
                        .orderByAsc(InsightSupersonicMapping::getPlatformUsername))
                .stream().map(MappingView::from).toList();
    }

    public InsightSupersonicMapping find(String workspaceId, String platformUsername) {
        return mapper.selectOne(new LambdaQueryWrapper<InsightSupersonicMapping>()
                .eq(InsightSupersonicMapping::getWorkspaceId, workspaceId)
                .eq(InsightSupersonicMapping::getPlatformUsername, platformUsername)
                .eq(InsightSupersonicMapping::getEnabled, true));
    }

    @Transactional
    public MappingView save(MappingRequest request) {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        if (request == null || blank(request.platformUsername())
                || blank(request.supersonicUsername()) || blank(request.credentialId())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "platformUsername / supersonicUsername / credentialId 都不能为空");
        }
        InsightSupersonicMapping existing = find(workspaceId, request.platformUsername());
        InsightSupersonicMapping value = existing == null ? new InsightSupersonicMapping() : existing;
        if (existing == null) {
            value.setId(Ids.of("ssmap"));
            value.setWorkspaceId(workspaceId);
            value.setPlatformUsername(request.platformUsername().trim());
            value.setCreatedBy(caller.username());
            value.setCreatedAt(Instant.now());
        }
        value.setSupersonicUsername(request.supersonicUsername().trim());
        value.setCredentialId(request.credentialId().trim());
        value.setEnabled(true);
        value.setUpdatedAt(Instant.now());
        if (existing == null) {
            mapper.insert(value);
        } else {
            mapper.updateById(value);
        }
        return MappingView.from(value);
    }

    @Transactional
    public void delete(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        InsightSupersonicMapping value = mapper.selectOne(
                new LambdaQueryWrapper<InsightSupersonicMapping>()
                        .eq(InsightSupersonicMapping::getId, id)
                        .eq(InsightSupersonicMapping::getWorkspaceId, workspaceId));
        if (value != null) {
            mapper.deleteById(value.getId());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
