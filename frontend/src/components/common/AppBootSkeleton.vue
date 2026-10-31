<script setup lang="ts">
/**
 * 首屏骨架。
 *
 * 只用于“应用还没完成首次路由解析”的短窗口。进入某个业务页之后不再用整页骨架，
 * 否则每次导航都盖一层灰块，反而更像整页刷新。
 */
</script>

<template>
  <div class="boot-skeleton">
    <aside class="boot-skeleton__aside">
      <div class="boot-skeleton__brand" />
      <el-skeleton :rows="8" animated />
    </aside>
    <main class="boot-skeleton__main">
      <div class="boot-skeleton__header">
        <el-skeleton :rows="1" animated />
      </div>
      <div class="boot-skeleton__cards">
        <el-skeleton v-for="index in 4" :key="index" :rows="4" animated />
      </div>
    </main>
  </div>
</template>

<style scoped>
.boot-skeleton {
  display: flex;
  height: 100%;
  background: var(--dg-canvas);
}

.boot-skeleton__aside {
  width: 220px;
  padding: 20px 16px;
  background: var(--dg-sidebar);
}

.boot-skeleton__brand {
  height: 24px;
  width: 140px;
  margin: 8px auto 36px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.12);
}

.boot-skeleton__aside :deep(.el-skeleton__item) {
  background: rgba(255, 255, 255, 0.1);
}

.boot-skeleton__main {
  flex: 1;
  padding: 24px;
}

.boot-skeleton__header {
  max-width: 760px;
  margin-bottom: 24px;
}

.boot-skeleton__cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.boot-skeleton__cards :deep(.el-skeleton) {
  padding: 20px;
  background: #fff;
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

@media (max-width: 900px) {
  .boot-skeleton__aside {
    display: none;
  }

  .boot-skeleton__cards {
    grid-template-columns: 1fr;
  }
}
</style>
