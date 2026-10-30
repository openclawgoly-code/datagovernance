<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  status?: string | null
  label?: string
}>(), {
  status: '',
  label: '',
})

type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'

const type = computed<TagType>(() => {
  const value = (props.status || '').toUpperCase()
  if (['SUCCESS', 'CONFIRMED', 'ACTIVE', 'AVAILABLE', 'ONLINE', 'RESOLVED'].includes(value)) {
    return 'success'
  }
  if (['FAILED', 'REJECTED', 'ERROR', 'UNREACHABLE', 'DANGER'].includes(value)) {
    return 'danger'
  }
  if (['WARNING', 'SUGGESTED', 'PENDING', 'RUNNING', 'PARTIAL'].includes(value)) {
    return 'warning'
  }
  return 'info'
})

const text = computed(() => props.label || props.status || '-')
</script>

<template>
  <el-tag :type="type" size="small">{{ text }}</el-tag>
</template>
