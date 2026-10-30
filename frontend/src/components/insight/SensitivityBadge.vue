<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  level?: string | null
  category?: string | null
  status?: string | null
}>(), {
  level: '',
  category: '',
  status: '',
})

type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'

const type = computed<TagType>(() => {
  switch ((props.level || '').toUpperCase()) {
    case 'L4':
      return 'danger'
    case 'L3':
      return 'warning'
    case 'L2':
      return 'primary'
    default:
      return 'info'
  }
})

const text = computed(() => {
  const level = props.level || 'L1'
  const category = props.category ? ` · ${props.category}` : ''
  const status = props.status === 'SUGGESTED' ? ' 待复核' : ''
  return `${level}${category}${status}`
})
</script>

<template>
  <el-tag :type="type" size="small" effect="light">{{ text }}</el-tag>
</template>
