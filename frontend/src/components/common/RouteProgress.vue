<script setup lang="ts">
import { routeProgress } from '@/router/progress'
</script>

<template>
  <div
    v-show="routeProgress.visible"
    class="route-progress"
    :class="{ 'route-progress--failed': routeProgress.failed }"
    aria-hidden="true"
  >
    <div class="route-progress__bar" :style="{ width: `${routeProgress.value}%` }" />
  </div>
</template>

<style scoped>
.route-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  z-index: 3000;
  pointer-events: none;
  background: transparent;
}

.route-progress__bar {
  height: 100%;
  background: var(--el-color-primary);
  box-shadow: 0 0 8px color-mix(in srgb, var(--el-color-primary) 60%, transparent);
  transition: width 0.18s ease;
}

.route-progress--failed .route-progress__bar {
  background: var(--el-color-danger);
  box-shadow: 0 0 8px color-mix(in srgb, var(--el-color-danger) 60%, transparent);
}
</style>
