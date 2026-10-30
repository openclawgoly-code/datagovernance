<script setup lang="ts">
withDefaults(defineProps<{
  description?: string
  imageSize?: number
}>(), {
  description: '暂无数据',
  imageSize: 80,
})
</script>

<template>
  <div class="empty-state">
    <el-empty :description="description" :image-size="imageSize">
      <slot />
    </el-empty>
  </div>
</template>

<style scoped>
.empty-state {
  padding: var(--dg-space-xl) var(--dg-space-lg);
}
</style>
