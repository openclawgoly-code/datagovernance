<script setup lang="ts">
withDefaults(defineProps<{
  sql?: string | null
  title?: string
}>(), {
  sql: '',
  title: 'SQL',
})
</script>

<template>
  <section class="query-sql">
    <div class="query-sql__title">{{ title }}</div>
    <pre v-if="sql">{{ sql }}</pre>
    <div v-else class="query-sql__empty">暂无 SQL</div>
  </section>
</template>

<style scoped>
.query-sql__title {
  font-size: 12px;
  color: var(--dg-text-muted);
  margin-bottom: var(--dg-space-xs);
}

.query-sql pre {
  margin: 0;
  padding: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  overflow-x: auto;
  font-family: var(--dg-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--dg-text);
}

.query-sql__empty {
  padding: var(--dg-space-md);
  color: var(--dg-text-muted);
  font-size: 12px;
}
</style>
