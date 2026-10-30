<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
</script>

<template>
  <div class="page-container">
    <PageHeader title="字段血缘" description="列级上下游与影响分析" />
    <el-card shadow="never">
      <EmptyState description="血缘工作台骨架已就绪，图与影响分析将在 lineage-workspace change 中接入" />
    </el-card>
  </div>
</template>
