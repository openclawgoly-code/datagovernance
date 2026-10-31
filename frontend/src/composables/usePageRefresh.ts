import { onActivated, onMounted } from 'vue'

/**
 * 列表页的数据刷新钩子。
 *
 * KeepAlive 组件第一次挂载时 onMounted / onActivated 都会触发；这里用
 * “最小间隔 + in-flight”去重，避免首屏重复请求。之后每次从缓存重新激活
 * 都刷新一次，保证用户看到的是当前空间的最新列表，而不是缓存快照。
 */
export function usePageRefresh(
  load: () => void | Promise<void>,
  options: { minIntervalMs?: number } = {},
) {
  const minInterval = options.minIntervalMs ?? 600
  let lastLoadedAt = 0
  let inFlight = false

  async function run(force: boolean) {
    const now = Date.now()
    if (!force && now - lastLoadedAt < minInterval) {
      return
    }
    if (inFlight) {
      return
    }
    lastLoadedAt = now
    inFlight = true
    try {
      await load()
    } finally {
      inFlight = false
    }
  }

  onMounted(() => {
    void run(true)
  })
  onActivated(() => {
    void run(false)
  })

  return {
    refresh: () => run(true),
  }
}
