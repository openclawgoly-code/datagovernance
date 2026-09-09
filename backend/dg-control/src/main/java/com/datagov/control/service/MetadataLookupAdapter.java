package com.datagov.control.service;

import com.datagov.control.compile.JobCompiler;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.metadata.service.CatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 编译器向 Metadata 提问的实现。
 *
 * <p>它是 Control 与 Metadata 之间<b>唯一</b>的接触点。集中在一处的价值:
 * 要回答"编译期都读了 Metadata 的什么"这个问题时,只看这一个类,而不必翻遍编译器。
 *
 * <p>注意它只读,而且只读四样东西。Control 的 must_not_do 里没有明写"不得写
 * Metadata",但 H.3 的禁止边里有 —— 编译一次不该改变任何定义的状态。
 */
@Component
public class MetadataLookupAdapter implements JobCompiler.MetadataLookup {

    private static final Logger log = LoggerFactory.getLogger(MetadataLookupAdapter.class);

    private final DataSourceMapper dataSourceMapper;
    private final CatalogService catalogService;

    public MetadataLookupAdapter(DataSourceMapper dataSourceMapper, CatalogService catalogService) {
        this.dataSourceMapper = dataSourceMapper;
        this.catalogService = catalogService;
    }

    @Override
    public boolean isDataSourceAvailable(String workspaceId, String dataSourceId) {
        DataSourceEntity entity = load(workspaceId, dataSourceId);
        return entity != null && entity.getStatus() == DataSourceStatus.AVAILABLE;
    }

    @Override
    public String dataSourceName(String workspaceId, String dataSourceId) {
        DataSourceEntity entity = load(workspaceId, dataSourceId);
        // 拿不到就回显 ID。报错里出现一个 ID 总好过出现 "null" ——
        // 至少运维还能拿它去查。
        return entity == null ? dataSourceId : entity.getName();
    }

    @Override
    public DataSourceType dataSourceType(String workspaceId, String dataSourceId) {
        DataSourceEntity entity = load(workspaceId, dataSourceId);
        return entity == null ? null : entity.getType();
    }

    @Override
    public Map<String, String> tableColumns(String workspaceId, String dataSourceId,
                                            String database, String schema, String table) {
        try {
            return catalogService.snapshotColumns(workspaceId, dataSourceId, database, schema, table);
        } catch (RuntimeException e) {
            // 没有快照不是编译器的错,是"还没浏览过这张表"。返回空 Map,
            // 由编译器判定成一条带定位的诊断("请先浏览该表的结构"),
            // 而不是在这里抛出一个用户看不懂的异常。
            log.debug("取不到表结构快照 ds={} {}.{}.{}", dataSourceId, database, schema, table, e);
            return Map.of();
        }
    }

    private DataSourceEntity load(String workspaceId, String dataSourceId) {
        if (dataSourceId == null || dataSourceId.isBlank()) {
            return null;
        }
        return dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getId, dataSourceId)
                .eq(DataSourceEntity::getWorkspaceId, workspaceId));
    }
}
