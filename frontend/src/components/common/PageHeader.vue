<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="page-header">
    <div class="page-header__text">
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="page-header__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dg-space-lg);
  margin-bottom: var(--dg-space-lg);
}

.page-header__text h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--dg-text);
}

.page-header__text p {
  margin: var(--dg-space-xs) 0 0;
  font-size: 13px;
  color: var(--dg-text-muted);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
  flex-wrap: wrap;
}
</style>
