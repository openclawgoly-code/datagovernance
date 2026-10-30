<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { catalogAssetApi } from '@/api/catalog-assets'
import type { CatalogDataset, CatalogSource } from '@/types/catalog-assets'

/**
 * 数据集远程搜索选择器。
 *
 * Catalog 没有全局数据集搜索接口，这里按来源并行分页搜索后在内存合并。
 * 1000 表环境只返回每个来源当前关键词的前 20/30 条，不把全量目录拉进浏览器。
 */
const props = withDefaults(defineProps<{
  modelValue?: string
  placeholder?: string
  disabled?: boolean
  clearable?: boolean
}>(), {
  modelValue: '',
  placeholder: '搜索数据集',
  disabled: false,
  clearable: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const sources = ref<CatalogSource[]>([])
const options = ref<CatalogDataset[]>([])
const selected = ref<CatalogDataset | null>(null)
const loading = ref(false)

let searchTimer: ReturnType<typeof setTimeout> | null = null
let searchSequence = 0

function register(item: CatalogDataset) {
  const next = new Map(options.value.map((option) => [option.id, option]))
  next.set(item.id, item)
  options.value = [...next.values()].sort((a, b) =>
    a.qualifiedName.localeCompare(b.qualifiedName),
  )
}

async function loadSources() {
  try {
    sources.value = await catalogAssetApi.listSources()
  } catch {
    sources.value = []
  }
}

async function search(keyword: string) {
  const sequence = ++searchSequence
  loading.value = true
  try {
    const term = keyword.trim()
    if (!sources.value.length) {
      options.value = selected.value ? [selected.value] : []
      return
    }
    const results = await Promise.allSettled(
      sources.value.map((source) =>
        catalogAssetApi.listDatasets(source.id, {
          page: 1,
          size: term ? 30 : 15,
          keyword: term || undefined,
        }),
      ),
    )
    if (sequence !== searchSequence) {
      return
    }
    const next = new Map<string, CatalogDataset>()
    if (selected.value) {
      next.set(selected.value.id, selected.value)
    }
    for (const result of results) {
      if (result.status === 'fulfilled') {
        for (const item of result.value.records) {
          next.set(item.id, item)
        }
      }
    }
    options.value = [...next.values()]
      .sort((a, b) => a.qualifiedName.localeCompare(b.qualifiedName))
      .slice(0, 80)
  } finally {
    if (sequence === searchSequence) {
      loading.value = false
    }
  }
}

function onSearch(keyword: string) {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
  searchTimer = setTimeout(() => {
    void search(keyword)
  }, 260)
}

async function hydrate(value: string) {
  if (!value) {
    selected.value = null
    return
  }
  if (selected.value?.id === value) {
    return
  }
  const existing = options.value.find((option) => option.id === value)
  if (existing) {
    selected.value = existing
    return
  }
  try {
    const detail = await catalogAssetApi.getDataset(value)
    selected.value = detail.dataset
    register(detail.dataset)
  } catch {
    // 无权限或数据集已删除时保持空白，不阻塞页面其他功能。
  }
}

function onChange(value: string | undefined) {
  const next = value || ''
  selected.value = options.value.find((option) => option.id === next) || null
  emit('update:modelValue', next)
  emit('change', next)
}

watch(() => props.modelValue, hydrate, { immediate: true })

onMounted(async () => {
  await loadSources()
  await search('')
})

onBeforeUnmount(() => {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
})
</script>

<template>
  <el-select
    :model-value="modelValue"
    filterable
    remote
    :clearable="clearable"
    :disabled="disabled"
    reserve-keyword
    :remote-method="onSearch"
    :loading="loading"
    :placeholder="placeholder"
    @change="onChange"
  >
    <el-option
      v-for="item in options"
      :key="item.id"
      :label="item.qualifiedName"
      :value="item.id"
    >
      <div class="dataset-option">
        <span>{{ item.name }}</span>
        <span class="dataset-option__meta">
          {{ item.qualifiedName }} · {{ item.columnCount ?? '-' }} 字段
        </span>
      </div>
    </el-option>
  </el-select>
</template>

<style scoped>
.dataset-option {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.dataset-option__meta {
  font-size: 11px;
  color: var(--dg-text-muted);
}
</style>
