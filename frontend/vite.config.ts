import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),

    // Element Plus 按需引入(组件 + 样式)。
    // 只解析 Element Plus,不把 vue/vue-router/pinia 的 API 也塞进自动导入——
    // 那些仍然显式 import,保证从任意一个文件本身就能看出它依赖了什么,
    // 不用先记住"这个项目开了自动导入"这条隐性规则。
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: Number(process.env.DG_UI_PORT ?? 5173),
    proxy: {
      // 后端 dg-app 默认监听 8080;开发期同源代理,避免 CORS 配置渗进业务代码。
      // 端口可用 DG_API_TARGET 覆盖 —— 本地同时跑多套环境时不必改代码。
      '/api': {
        target: process.env.DG_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: Number(process.env.DG_UI_PORT ?? 4173),
    proxy: {
      '/api': {
        target: process.env.DG_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
