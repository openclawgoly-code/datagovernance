import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { registerDirectives } from './directives/permission'
import './styles/main.css'

const app = createApp(App)

// Pinia 必须先于 router 安装:路由守卫里会 useAuthStore(),
// 那时 Pinia 实例必须已经挂到 app 上
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 图标整体注册。P1 的菜单图标名由后端 pf_permission.icon 下发,
// 前端拿到的是字符串,只能靠全局注册来按名字解析。
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

registerDirectives(app)

app.mount('#app')
