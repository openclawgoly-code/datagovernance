<script setup lang="ts">
export interface MetadataItem {
  label: string
  value?: string | number | null
}

withDefaults(defineProps<{
  title?: string
  items: MetadataItem[]
  columns?: number
}>(), {
  title: '',
  columns: 1,
})
</script>

<template>
  <div class="metadata-panel">
    <h4 v-if="title">{{ title }}</h4>
    <dl class="metadata-panel__list">
      <div v-for="item in items" :key="item.label" class="metadata-panel__item">
        <dt>{{ item.label }}</dt>
        <dd>{{ item.value ?? '-' }}</dd>
      </div>
    </dl>
    <slot />
  </div>
</template>

<style scoped>
.metadata-panel h4 {
  margin: 0 0 var(--dg-space-sm);
  font-size: 14px;
  color: var(--dg-text);
}

.metadata-panel__list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--dg-space-sm) var(--dg-space-lg);
  margin: 0;
}

.metadata-panel__item {
  min-width: 0;
}

.metadata-panel__item dt {
  font-size: 12px;
  color: var(--dg-text-muted);
  margin-bottom: 2px;
}

.metadata-panel__item dd {
  margin: 0;
  font-size: 13px;
  color: var(--dg-text);
  word-break: break-all;
}
</style>
