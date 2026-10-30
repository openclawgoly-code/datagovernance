<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

const props = defineProps<{
  kind: 'dataset' | 'column' | 'job' | 'execution' | 'dashboard' | 'report'
  id: string
  label?: string
  datasetId?: string
}>()

const target = computed(() => {
  switch (props.kind) {
    case 'dataset':
      return { path: `/metadata/assets/${props.id}` }
    case 'column':
      return props.datasetId
        ? { path: `/metadata/assets/${props.datasetId}`, query: { tab: 'schema', columnId: props.id } }
        : { path: '/metadata/lineage', query: { columnId: props.id } }
    case 'job':
      return { path: '/integration/jobs', query: { jobId: props.id } }
    case 'execution':
      return { path: '/ops/executions', query: { executionId: props.id } }
    case 'dashboard':
      return { path: '/insight/dashboards', query: { dashboardId: props.id } }
    case 'report':
      return { path: '/ops/alerts', query: { reportId: props.id } }
    default:
      return { path: '/' }
  }
})
</script>

<template>
  <RouterLink :to="target" class="entity-link">
    {{ label || id }}
  </RouterLink>
</template>

<style scoped>
.entity-link {
  color: var(--dg-primary);
  text-decoration: none;
  word-break: break-all;
}

.entity-link:hover {
  text-decoration: underline;
}
</style>
