import router from './index'

/**
 * 路由 chunk 预取。
 *
 * 路由组件都是 `() => import(...)`；调用同一个 loader 会命中 ESM 模块缓存，
 * 因此这里不是“提前渲染”，只是把网络/编译开销从点击时挪到空闲时。
 *
 * 菜单 hover 时预取单项；登录后空闲时预取全部有权限的静态路由。
 */
export function prefetchRoute(path: string | null | undefined) {
  if (!path) {
    return
  }
  try {
    const resolved = router.resolve(path)
    for (const record of resolved.matched) {
      const loader = record.components?.default
      if (typeof loader === 'function') {
        // loader 的返回类型是 Promise<Component>;失败时静默，真正的导航会再报错。
        void (loader as () => Promise<unknown>)().catch(() => undefined)
      }
    }
  } catch {
    // 预取永远不能影响正常导航
  }
}

/** 只预取没有动态参数、且声明了权限的页面，避免把详情页/404 也拉下来。 */
export function prefetchAllRoutes() {
  for (const route of router.getRoutes()) {
    if (!route.meta?.permission || route.path.includes(':')) {
      continue
    }
    prefetchRoute(route.path)
  }
}
