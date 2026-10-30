<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
</script>

<template>
  <div class="page-container">
    <PageHeader title="敏感数据" description="规则、字段标签、扫描与复核" />
    <el-card shadow="never">
      <EmptyState description="敏感工作台骨架已就绪，扫描/复核将在 sensitivity-workbench change 中接入" />
    </el-card>
  </div>
</template>
