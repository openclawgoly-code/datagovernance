package com.datagov.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.runtime.entity.ArtifactEntity;
import com.datagov.runtime.mapper.ArtifactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 作业制品仓库(序号 32「文件管理」)。
 *
 * <p>制品不可变:同名同版本只能上传一次。要改内容就发新版本 —— 可变的制品
 * 意味着"上周跑成功的那次执行"今天再跑可能是另一个结果。
 *
 * <p>被任务引用的制品不许删。这与规则的引用计数(功能 17)是同一条原则:
 * 删掉一个正在被引用的东西,故障会发生在凌晨的调度里,而不是点删除的那一刻。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    /** 单个制品的大小上限。超过它多半是误传了一个数据文件而不是作业包 */
    static final long MAX_SIZE_BYTES = 512L * 1024 * 1024;

    static final Set<String> TYPES = Set.of("JAR", "PYTHON", "SQL", "OTHER");

    private final ArtifactMapper artifactMapper;
    private final Path storageRoot;

    public ArtifactService(ArtifactMapper artifactMapper,
                           @Value("${dg.runtime.artifact-storage:./data/artifacts}") String storageRoot) {
        this.artifactMapper = artifactMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /** 制品视图。不含 storagePath —— 那是服务端的实现细节,不该出现在 API 上。 */
    public record ArtifactView(
            String id,
            String workspaceId,
            String name,
            String version,
            String type,
            String description,
            Long sizeBytes,
            String checksumSha256,
            String originalFilename,
            int refCount,
            /** 平台级制品所有空间可用,但只有平台管理员能改 */
            boolean platformLevel,
            Instant createdAt,
            String createdBy
    ) {

        static ArtifactView from(ArtifactEntity e) {
            return new ArtifactView(e.getId(), e.getWorkspaceId(), e.getName(), e.getVersion(),
                    e.getType(), e.getDescription(), e.getSizeBytes(), e.getChecksumSha256(),
                    e.getOriginalFilename(), e.getRefCount() == null ? 0 : e.getRefCount(),
                    e.getWorkspaceId() == null, e.getCreatedAt(), e.getCreatedBy());
        }
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    /**
     * 分页查询。
     *
     * <p>同时返回本空间的与平台级的 —— 用户要选一个 JAR 时,不关心它是谁上传的,
     * 只关心自己能不能用。让他分两个页面去找是把内部的归属模型泄露给了使用者。
     */
    public PageResult<ArtifactView> list(long page, long size, String type, String keyword) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<ArtifactEntity> wrapper = new LambdaQueryWrapper<ArtifactEntity>()
                .and(w -> w.eq(ArtifactEntity::getWorkspaceId, workspaceId)
                        .or().isNull(ArtifactEntity::getWorkspaceId))
                .eq(type != null && !type.isBlank(), ArtifactEntity::getType, type)
                .like(keyword != null && !keyword.isBlank(), ArtifactEntity::getName, keyword)
                .orderByDesc(ArtifactEntity::getCreatedAt);

        Page<ArtifactEntity> result = artifactMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(ArtifactView::from).toList(),
                result.getTotal(), page, size);
    }

    public ArtifactView get(String id) {
        return ArtifactView.from(requireVisible(id));
    }

    /**
     * 制品是否可见可用。
     *
     * <p>给编译器用(序号 18/20 的 artifactId 校验)。刻意只返回布尔值:
     * 编译器需要知道的就只有"在不在",给它一个完整实体会诱导它去读别的字段。
     */
    public boolean existsVisible(String workspaceId, String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return false;
        }
        Long count = artifactMapper.selectCount(new LambdaQueryWrapper<ArtifactEntity>()
                .eq(ArtifactEntity::getId, artifactId)
                .and(w -> w.eq(ArtifactEntity::getWorkspaceId, workspaceId)
                        .or().isNull(ArtifactEntity::getWorkspaceId)));
        return count != null && count > 0;
    }

    // ── 上传 ────────────────────────────────────────────────────────────

    @Transactional
    public ArtifactView upload(String name, String version, String type, String description,
                               String originalFilename, InputStream content, long declaredSize) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        requireValidName(name);
        String effectiveVersion = version == null || version.isBlank() ? "1.0.0" : version.trim();
        String effectiveType = type == null || type.isBlank() ? "JAR" : type.trim().toUpperCase();
        if (!TYPES.contains(effectiveType)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "不支持的制品类型: " + effectiveType, "可选:" + String.join(" / ", TYPES));
        }
        if (declaredSize > MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "制品超过大小上限 %d MB".formatted(MAX_SIZE_BYTES / 1024 / 1024));
        }
        requireVersionAvailable(workspaceId, name, effectiveVersion);

        String id = Ids.of("art");
        Path target = resolveStoragePath(workspaceId, id, originalFilename);
        long written;
        String checksum;
        try {
            Files.createDirectories(target.getParent());
            // 边写边算摘要:读两遍文件在 512MB 的量级上是可感知的浪费
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            written = copyWithDigest(content, target, digest);
            checksum = HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "制品保存失败: " + e.getMessage());
        }
        if (written > MAX_SIZE_BYTES) {
            // 声明的大小可能是假的,写完才知道真实大小 —— 超了就删掉,不留半个包
            deleteQuietly(target);
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "制品超过大小上限 %d MB".formatted(MAX_SIZE_BYTES / 1024 / 1024));
        }

        Instant now = Instant.now();
        ArtifactEntity entity = new ArtifactEntity();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        entity.setName(name.trim());
        entity.setVersion(effectiveVersion);
        entity.setType(effectiveType);
        entity.setDescription(description);
        entity.setStoragePath(storageRoot.relativize(target).toString());
        entity.setSizeBytes(written);
        entity.setChecksumSha256(checksum);
        entity.setOriginalFilename(originalFilename);
        entity.setRefCount(0);
        entity.setCreatedAt(now);
        entity.setCreatedBy(operator);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(operator);
        entity.setDeleted(false);
        artifactMapper.insert(entity);

        log.info("制品已上传 workspace={} id={} name={}:{} size={}",
                workspaceId, id, name, effectiveVersion, written);
        return ArtifactView.from(entity);
    }

    /** 读回制品内容。 */
    public Path resolveContent(String id) {
        ArtifactEntity entity = requireVisible(id);
        Path path = storageRoot.resolve(entity.getStoragePath()).normalize();
        // 防目录穿越:storagePath 来自数据库,但数据库也可能被别处写坏
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "制品内容缺失: " + id);
        }
        return path;
    }

    // ── 引用计数与删除 ──────────────────────────────────────────────────

    /** 引用 +1。任务定义引用制品时调用。 */
    @Transactional
    public void retain(String artifactId) {
        adjustRefCount(artifactId, 1);
    }

    /** 引用 -1。任务定义改成不引用它、或被删除时调用。 */
    @Transactional
    public void release(String artifactId) {
        adjustRefCount(artifactId, -1);
    }

    private void adjustRefCount(String artifactId, int delta) {
        if (artifactId == null || artifactId.isBlank()) {
            return;
        }
        ArtifactEntity entity = artifactMapper.selectById(artifactId);
        if (entity == null) {
            return;
        }
        int current = entity.getRefCount() == null ? 0 : entity.getRefCount();
        // 不让它掉到负数:一次重复的 release 不该让"还有几个人在用"变成谎话
        entity.setRefCount(Math.max(0, current + delta));
        entity.setUpdatedAt(Instant.now());
        artifactMapper.updateById(entity);
    }

    @Transactional
    public void delete(String id) {
        ArtifactEntity entity = requireVisible(id);
        if (entity.getWorkspaceId() == null && !WorkspaceContext.require().platformAdmin()) {
            throw BizException.forbidden("平台级制品只有平台管理员能删除");
        }
        int refs = entity.getRefCount() == null ? 0 : entity.getRefCount();
        if (refs > 0) {
            throw new BizException(ErrorCode.SYS_CONFLICT,
                    "制品仍被 %d 个任务引用,无法删除".formatted(refs),
                    "先把那些任务改成引用别的制品");
        }
        artifactMapper.deleteById(id);
        // 只做逻辑删除,不动磁盘上的文件:历史执行记录里还留着这个制品的
        // 摘要,真出了事故要能把当时跑的那个包拿出来比对
        log.info("制品已删除(仅逻辑删除,文件保留)id={} name={}", id, entity.getName());
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private long copyWithDigest(InputStream in, Path target, MessageDigest digest)
            throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (var out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                total += read;
                if (total > MAX_SIZE_BYTES) {
                    // 早停:不把一个 5GB 的误传文件整个写到盘上再判断
                    break;
                }
            }
        }
        return total;
    }

    private Path resolveStoragePath(String workspaceId, String id, String originalFilename) {
        String suffix = "";
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            // 只取扩展名,不用原文件名 —— 用户上传的名字里可能有 ../ 或空格
            if (dot > 0 && dot < originalFilename.length() - 1) {
                String ext = originalFilename.substring(dot + 1);
                if (ext.matches("[A-Za-z0-9]{1,12}")) {
                    suffix = "." + ext;
                }
            }
        }
        return storageRoot.resolve(workspaceId).resolve(id + suffix).normalize();
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理半个制品文件失败 path={}", path, e);
        }
    }

    private void requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "制品名称不能为空");
        }
        if (name.length() > 128) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "制品名称不得超过 128 字符");
        }
    }

    private void requireVersionAvailable(String workspaceId, String name, String version) {
        Long count = artifactMapper.selectCount(new LambdaQueryWrapper<ArtifactEntity>()
                .eq(ArtifactEntity::getWorkspaceId, workspaceId)
                .eq(ArtifactEntity::getName, name.trim())
                .eq(ArtifactEntity::getVersion, version));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT,
                    "制品「%s」的版本 %s 已存在".formatted(name, version));
        }
    }

    private ArtifactEntity requireVisible(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        List<ArtifactEntity> found = artifactMapper.selectList(
                new LambdaQueryWrapper<ArtifactEntity>()
                        .eq(ArtifactEntity::getId, id)
                        .and(w -> w.eq(ArtifactEntity::getWorkspaceId, workspaceId)
                                .or().isNull(ArtifactEntity::getWorkspaceId)));
        if (found.isEmpty()) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "制品 " + id);
        }
        return found.get(0);
    }
}
