package com.datagov.metadata.dto;

import java.util.List;

/**
 * 数据源目录树节点(功能 5)。
 *
 * @param dataSourceCount 该目录<b>直接</b>挂载的数据源数量(不含子目录)。
 *                        前端要显示"这个目录下有几个数据源",而递归统计会让
 *                        一次树查询变成 N 次计数;子目录的数量由前端自行累加。
 */
public record CatalogNodeView(
        String id,
        String parentId,
        String name,
        String description,
        Integer sortOrder,
        int dataSourceCount,
        List<CatalogNodeView> children
) {
}
