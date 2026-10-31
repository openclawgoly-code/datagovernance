import { reactive } from 'vue'

/**
 * 路由加载进度。
 *
 * 不用 nprogress：这里只需要一条 3px 的顶部进度，自己维护比引入依赖更轻；
 * 进度条的目的是让异步 chunk / 守卫耗时可见，而不是装饰。
 */
export const routeProgress = reactive({
  visible: false,
  value: 0,
  failed: false,
})

let timer: ReturnType<typeof setInterval> | null = null
let hideTimer: ReturnType<typeof setTimeout> | null = null

function clearTimers() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
}

export function startProgress() {
  clearTimers()
  routeProgress.visible = true
  routeProgress.failed = false
  routeProgress.value = 8
  // 越接近 100 越慢：用户能看到“在走”，但不会假装马上完成。
  timer = setInterval(() => {
    const remaining = 92 - routeProgress.value
    routeProgress.value = Math.min(92, routeProgress.value + Math.max(0.5, remaining * 0.12))
  }, 120)
}

export function finishProgress() {
  clearTimers()
  routeProgress.value = 100
  hideTimer = setTimeout(() => {
    routeProgress.visible = false
    routeProgress.value = 0
  }, 220)
}

export function failProgress() {
  clearTimers()
  routeProgress.failed = true
  routeProgress.value = 100
  hideTimer = setTimeout(() => {
    routeProgress.visible = false
    routeProgress.failed = false
    routeProgress.value = 0
  }, 500)
}
