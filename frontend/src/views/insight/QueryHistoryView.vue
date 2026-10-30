<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
</script>

<template>
  <div class="page-container">
    <PageHeader title="查询历史" description="问数记录、SQL、脱敏列与重新执行" />
    <el-card shadow="never">
      <EmptyState description="查询历史页骨架已就绪，列表与重跑将在 query-history-page change 中接入" />
    </el-card>
  </div>
</template>
