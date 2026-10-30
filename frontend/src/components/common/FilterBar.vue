<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })
</script>

<template>
  <div class="filter-bar" :class="{ 'filter-bar--compact': compact }">
    <div class="filter-bar__filters">
      <slot />
    </div>
    <div v-if="$slots.actions" class="filter-bar__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
  flex-wrap: wrap;
}

.filter-bar--compact {
  margin-bottom: var(--dg-space-sm);
}

.filter-bar__filters,
.filter-bar__actions {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
  flex-wrap: wrap;
}
</style>
