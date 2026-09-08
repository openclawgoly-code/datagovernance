package com.datagov.platform.dto;

import java.util.List;

/**
 * 前端菜单树节点。
 *
 * <p>菜单由后端下发而不是前端硬编码,原因不只是"配置化":菜单项与权限码
 * 一一对应,前端硬编码一份就等于把权限模型复制了一份,两份迟早会不一致 ——
 * 而不一致的表现是用户看得见一个点进去报 403 的菜单。
 *
 * @param ownerSpace 该菜单归属的架构 Space。前端不使用,但保留在响应里
 *                   便于排查「这个菜单为什么在这」——它编码了「基础配置菜单
 *                   横跨三个 Space」这条推导结论。
 */
public record MenuNode(
        String code,
        String name,
        String routePath,
        String icon,
        Integer sortOrder,
        String ownerSpace,
        List<MenuNode> children
) {
}
