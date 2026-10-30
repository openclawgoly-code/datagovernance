<script setup lang="ts">
export interface DetailTab {
  key: string
  label: string
}

defineProps<{ tabs: DetailTab[] }>()
const model = defineModel<string>({ default: '' })
</script>

<template>
  <el-tabs v-model="model" class="detail-tabs">
    <el-tab-pane v-for="tab in tabs" :key="tab.key" :label="tab.label" :name="tab.key">
      <slot :name="tab.key" />
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--dg-space-lg);
}
</style>
