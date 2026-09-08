<script setup lang="ts">
import type { MenuItem } from '@/types/permission'

/**
 * 递归渲染后端下发的菜单树。
 *
 * 单独成组件是因为 Vue 的模板不支持在同一个组件里递归自身而不命名 ——
 * 这个组件通过文件名自动获得名字,从而可以在自己的模板里引用自己。
 */
defineProps<{ items: MenuItem[] }>()
</script>

<template>
  <template v-for="item in items" :key="item.code">
    <!-- 有子节点渲染为可展开的子菜单,否则是可点击的叶子 -->
    <el-sub-menu v-if="item.children && item.children.length > 0" :index="item.code">
      <template #title>
        <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
        <span>{{ item.name }}</span>
      </template>
      <MenuTree :items="item.children" />
    </el-sub-menu>

    <el-menu-item v-else :index="item.routePath || item.code">
      <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
      <template #title>{{ item.name }}</template>
    </el-menu-item>
  </template>
</template>
