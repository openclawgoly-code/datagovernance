<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { insightApi } from '@/api/insight'
import type {
  InsightAnswer,
  InsightDashboard,
  InsightDashboardDetail,
  InsightDashboardRun,
  InsightDashboardVersion,
} from '@/types/insight'
import { formatDateTime } from '@/utils/format'

const dashboards = ref<InsightDashboard[]>([])
const detail = ref<InsightDashboardDetail | null>(null)
const loading = ref(false)
const selectedId = ref('')
const dialogVisible = ref(false)
const draft = ref({ name: '', description: '' })
const running = ref(false)
const runResult = ref<InsightDashboardRun | null>(null)
const versionDialog = ref(false)
const versions = ref<InsightDashboardVersion[]>([])
const autoRefresh = ref(false)
let refreshTimer: number | undefined

async function loadDashboards() {
  loading.value = true
  try {
    dashboards.value = await insightApi.dashboards()
    if (!selectedId.value && dashboards.value.length) {
      selectedId.value = dashboards.value[0].id
    }
    if (selectedId.value) {
      await loadDetail(selectedId.value)
    }
  } finally {
    loading.value = false
  }
}

async function loadDetail(id: string) {
  selectedId.value = id
  detail.value = await insightApi.dashboard(id)
}

async function createDashboard() {
  if (!draft.value.name.trim()) {
    ElMessage.warning('请输入看板名称')
    return
  }
  const created = await insightApi.createDashboard({
    name: draft.value.name.trim(),
    description: draft.value.description.trim() || undefined,
  })
  dialogVisible.value = false
  draft.value = { name: '', description: '' }
  await loadDashboards()
  await loadDetail(created.id)
}

async function runDashboard(silent = false) {
  if (!detail.value || running.value) {
    return
  }
  running.value = true
  try {
    runResult.value = await insightApi.runDashboard(detail.value.id)
    if (!silent) {
      ElMessage.success('看板已刷新')
    }
  } finally {
    running.value = false
  }
}

async function openVersions() {
  if (!detail.value) {
    return
  }
  versions.value = await insightApi.dashboardVersions(detail.value.id)
  versionDialog.value = true
}

function answerColumns(answer?: InsightAnswer) {
  if (!answer) {
    return []
  }
  if (answer.columns?.length) {
    return answer.columns.map((column) => ({
      key: column.bizName || column.name,
      label: column.name,
    }))
  }
  const first = answer.rows?.[0]
  return first ? Object.keys(first).map((key) => ({ key, label: key })) : []
}

async function removeDashboard(id: string) {
  await ElMessageBox.confirm('删除看板会同时删除其问题项,确定吗?', '删除看板', {
    type: 'warning',
  })
  await insightApi.deleteDashboard(id)
  if (selectedId.value === id) {
    selectedId.value = ''
    detail.value = null
  }
  await loadDashboards()
}

async function removeItem(itemId: string) {
  if (!detail.value) {
    return
  }
  await insightApi.deleteDashboardItem(detail.value.id, itemId)
  await loadDetail(detail.value.id)
}

watch(autoRefresh, (enabled) => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = undefined
  }
  if (enabled) {
    runDashboard(true)
    refreshTimer = window.setInterval(() => runDashboard(true), 60_000)
  }
})

onMounted(loadDashboards)

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
  }
})
</script>

<template>
  <div class="page-container dashboard">
    <el-card shadow="never">
      <template #header>
        <div class="dashboard__header">
          <span>问数看板</span>
          <el-button type="primary" @click="dialogVisible = true">新建看板</el-button>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-table
            :data="dashboards"
            size="small"
            v-loading="loading"
            highlight-current-row
            @row-click="(row: InsightDashboard) => loadDetail(row.id)"
          >
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="itemCount" label="问题数" width="80" />
            <el-table-column label="更新时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column width="70">
              <template #default="{ row }">
                <el-button link type="danger" @click.stop="removeDashboard(row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && !dashboards.length" description="还没有看板" />
        </el-col>

        <el-col :span="16">
          <template v-if="detail">
            <div class="dashboard__title">
              <div>
                <h3>{{ detail.name }}</h3>
                <div class="text-muted">{{ detail.description || '暂无描述' }}</div>
              </div>
              <div class="dashboard__actions">
                <el-tag size="small">v{{ detail.version }}</el-tag>
                <span class="text-muted">{{ detail.items.length }} 个问题</span>
                <el-button size="small" @click="openVersions">版本</el-button>
                <el-switch v-model="autoRefresh" active-text="自动刷新 60s" />
                <el-button
                  size="small"
                  type="primary"
                  :loading="running"
                  :disabled="!detail.items.length"
                  @click="runDashboard()"
                >
                  刷新看板
                </el-button>
              </div>
            </div>

            <el-table :data="detail.items" size="small">
              <el-table-column prop="question" label="问题" min-width="240" show-overflow-tooltip />
              <el-table-column prop="datasetId" label="数据集 ID" width="100" />
              <el-table-column prop="chartType" label="图表" width="90" />
              <el-table-column width="80">
                <template #default="{ row }">
                  <el-button link type="danger" @click="removeItem(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!detail.items.length" description="去智能问数页把问题保存到看板" />

            <template v-if="runResult">
              <el-divider>刷新于 {{ formatDateTime(runResult.refreshedAt) }}</el-divider>
              <el-card
                v-for="item in runResult.items"
                :key="item.itemId"
                shadow="never"
                class="dashboard__result"
              >
                <template #header>
                  <div class="dashboard__result-header">
                    <span>{{ item.question }}</span>
                    <el-tag :type="item.succeeded ? 'success' : 'danger'" size="small">
                      {{ item.succeeded ? 'SUCCESS' : 'FAILED' }}
                    </el-tag>
                  </div>
                </template>
                <el-alert
                  v-if="!item.succeeded"
                  type="error"
                  show-icon
                  :closable="false"
                  :title="item.error || '执行失败'"
                />
                <template v-else-if="item.answer">
                  <div v-if="item.answer.summary" class="dashboard__summary">
                    {{ item.answer.summary }}
                  </div>
                  <el-table :data="item.answer.rows" size="small" max-height="220">
                    <el-table-column
                      v-for="column in answerColumns(item.answer)"
                      :key="column.key"
                      :prop="column.key"
                      :label="column.label"
                      min-width="120"
                    />
                  </el-table>
                </template>
              </el-card>
            </template>
          </template>
          <el-empty v-else description="选择左侧看板查看详情" />
        </el-col>
      </el-row>
    </el-card>

    <el-dialog v-model="versionDialog" title="看板版本" width="520px">
      <el-table :data="versions" size="small">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="itemCount" label="问题数" width="90" />
        <el-table-column prop="createdBy" label="创建人" width="100" />
        <el-table-column label="时间">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="新建看板" width="480px">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="draft.name" maxlength="128" placeholder="例如：销售日看板" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="draft.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createDashboard">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dashboard__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dashboard__title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 12px;
}

.dashboard__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dashboard__result {
  margin-top: 12px;
}

.dashboard__result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dashboard__summary {
  margin-bottom: 8px;
  line-height: 1.6;
}

.dashboard__title h3 {
  margin: 0 0 4px;
}
</style>
