<script setup lang="ts">
import { useRoute } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const route = useRoute()
</script>

<template>
  <div class="page-container">
    <PageHeader title="资产详情" :description="`资产 ID：${route.params.id}`" />
    <el-card shadow="never">
      <EmptyState description="资产详情页骨架已就绪，字段/血缘/敏感/分区将在 asset-detail-page change 中接入" />
    </el-card>
  </div>
</template>
