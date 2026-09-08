import type { App, Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * `v-permission="'metadata:datasource:create'"` —— 无权限时把元素从 DOM 移除。
 *
 * <b>移除而不是隐藏</b>:`display:none` 的按钮在 DevTools 里改一行样式就能点。
 * 当然真正的防线在后端(每个接口都有 @RequirePermission),这里只是不让用户
 * 看见并点击一个注定 403 的按钮 —— 那种体验会让人以为系统坏了。
 *
 * 权限码取值与后端 pf_permission.code 完全一致,同一个事实只在数据库里定义一次。
 */
const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el: HTMLElement, binding: DirectiveBinding<string | string[]>) {
    const auth = useAuthStore()
    const required = binding.value
    const codes = Array.isArray(required) ? required : [required]

    // 多个权限码时按"任一满足"判定 —— 需要"全部满足"的场景至今没出现,
    // 真出现了再加修饰符,不预先设计
    const allowed = codes.some((code) => auth.hasPermission(code))
    if (!allowed) {
      el.parentNode?.removeChild(el)
    }
  },
}

export function registerDirectives(app: App) {
  app.directive('permission', permission)
}

export default permission
