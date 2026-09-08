<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dataSourceApi } from '@/api/datasource'
import { useAuthStore } from '@/stores/auth'
import { statusMeta, ALL_STATUSES } from '@/utils/datasource-status'
import type { DataSource, DataSourceStatus } from '@/types/datasource'

const auth = useAuthStore()
const router = useRouter()

const loading = ref(false)
const items = ref<DataSource[]>([])
const total = ref(0)

const byStatus = computed(() => {
  const counts = new Map<DataSourceStatus, number>()
  for (const status of ALL_STATUSES) {
    counts.set(status, 0)
  }
  for (const item of items.value) {
    counts.set(item.status, (counts.get(item.status) ?? 0) + 1)
  }
  return ALL_STATUSES.map((status) => ({
    status,
    count: counts.get(status) ?? 0,
    meta: statusMeta(status),
  })).filter((entry) => entry.count > 0)
})

const needsAttention = computed(() =>
  items.value.filter((item) => item.status === 'UNREACHABLE' || item.status === 'DRAFT'),
)

async function load() {
  if (!auth.workspaceId) {
    return
  }
  loading.value = true
  try {
    // 工作台只做概览,取一页足够大的数据在前端聚合。
    // 真正的监控统计是功能 24(运维监控),属于 P4 的 Governance,不在这里堆砌。
    const page = await dataSourceApi.list({ page: 1, size: 200 })
    items.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-container">
    <el-alert
      v-if="!auth.workspaceId"
      type="warning"
      show-icon
      :closable="false"
      title="尚未选择空间"
      description="平台内每个对象都归属于某个空间,请先在右上角选择一个空间。"
    />

    <template v-else>
      <el-row :gutter="16">
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat__label">当前空间数据源</div>
            <div class="stat__value">{{ total }}</div>
            <div class="text-muted">空间:{{ auth.currentWorkspace?.name }}</div>
          </el-card>
        </el-col>

        <el-col v-for="entry in byStatus" :key="entry.status" :span="4">
          <el-card shadow="never">
            <div class="stat__label">
              <el-tag :type="entry.meta.type" size="small">{{ entry.meta.label }}</el-tag>
            </div>
            <div class="stat__value">{{ entry.count }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-card v-if="needsAttention.length > 0" shadow="never" class="attention">
        <template #header>
          <span>需要处理({{ needsAttention.length }})</span>
        </template>
        <el-table :data="needsAttention" size="small" v-loading="loading">
          <el-table-column prop="name" label="数据源" min-width="160" />
          <el-table-column prop="typeDisplayName" label="类型" width="120" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tooltip :content="statusMeta(row.status).hint" placement="top">
                <el-tag :type="statusMeta(row.status).type" size="small">
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="lastTestMessage" label="最近结论" min-width="220" show-overflow-tooltip />
          <el-table-column width="100">
            <template #default>
              <el-button link type="primary" @click="router.push('/metadata/datasources')">
                去处理
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.stat__label {
  font-size: 13px;
  color: #909399;
  min-height: 24px;
}

.stat__value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.4;
}

.attention {
  margin-top: 16px;
}
</style>
