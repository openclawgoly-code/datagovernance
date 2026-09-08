<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertApi } from '@/api/governance'
import { formatDateTime } from '@/utils/format'
import type { Alert, AlertStatus, AlertSummary } from '@/types/governance'

/**
 * 告警信息(序号 26)—— 今日 + 历史 + 具体内容。
 *
 * 「今日」与「历史」不是两个页面,是同一个列表的两个过滤 —— 与执行记录的
 * 五个菜单同一个道理(SPACE-MODEL.md I.2)。
 */

const loading = ref(false)
const rows = ref<Alert[]>([])
const total = ref(0)
const summary = ref<AlertSummary>({ todayCount: 0, openCount: 0, suppressedTodayCount: 0 })
const detail = ref<Alert | null>(null)
const detailVisible = ref(false)

const query = reactive({
  page: 1,
  size: 20,
  status: '' as '' | AlertStatus,
  severity: '',
  today: false,
})

const STATUS_OPTIONS: { value: AlertStatus; label: string }[] = [
  { value: 'NOTIFIED', label: '已通知' },
  { value: 'SUPPRESSED', label: '已抑制' },
  { value: 'NOTIFY_FAILED', label: '推送失败' },
  { value: 'ACKNOWLEDGED', label: '已认领' },
  { value: 'RESOLVED', label: '已解决' },
]

/**
 * 状态 → 标签颜色。
 *
 * 「已抑制」用 info 而不是 warning:它是配置生效的正常结果,不是异常。
 * 「推送失败」才是危险的 —— 那意味着告警系统自己坏了,而它坏了这件事
 * 没有人会收到告警。
 */
function tagType(status: AlertStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'RESOLVED') return 'success'
  if (status === 'NOTIFY_FAILED') return 'danger'
  if (status === 'ACKNOWLEDGED') return 'warning'
  if (status === 'SUPPRESSED') return 'info'
  return 'warning'
}

function severityType(severity: string): 'danger' | 'warning' | 'info' {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const [page, s] = await Promise.all([
      alertApi.list({
        page: query.page,
        size: query.size,
        status: query.status || undefined,
        severity: query.severity || undefined,
        today: query.today || undefined,
      }),
      alertApi.summary(),
    ])
    rows.value = page.records
    total.value = page.total
    summary.value = s
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onFilterChange() {
  query.page = 1
  load()
}

function openDetail(row: Alert) {
  detail.value = row
  detailVisible.value = true
}

async function onAcknowledge(row: Alert) {
  await alertApi.acknowledge(row.id)
  ElMessage.success('已认领')
  detailVisible.value = false
  await load()
}

async function onResolve(row: Alert) {
  try {
    const { value } = await ElMessageBox.prompt('处置说明(选填)', '关闭告警', {
      inputPlaceholder: '例如:已确认是上游停机,无需处理',
      inputValidator: () => true,
    })
    await alertApi.resolve(row.id, value || undefined)
    ElMessage.success('已关闭')
    detailVisible.value = false
    await load()
  } catch {
    /* 取消 */
  }
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-row :gutter="12">
        <el-col :span="8">
          <el-card shadow="never" class="summary">
            <div class="summary__label">今日告警</div>
            <div class="summary__value">{{ summary.todayCount }}</div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" class="summary">
            <div class="summary__label">未关闭</div>
            <div class="summary__value summary__value--warning">{{ summary.openCount }}</div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" class="summary">
            <div class="summary__label">今日被抑制</div>
            <div class="summary__value">{{ summary.suppressedTodayCount }}</div>
            <div class="text-muted">实际发生次数远不止你收到的</div>
          </el-card>
        </el-col>
      </el-row>

      <div class="page-toolbar" style="margin-top: 12px">
        <div class="page-toolbar__filters">
          <el-radio-group v-model="query.today" @change="onFilterChange">
            <el-radio-button :value="false">历史</el-radio-button>
            <el-radio-button :value="true">今日</el-radio-button>
          </el-radio-group>
          <el-select
            v-model="query.status"
            placeholder="全部状态"
            clearable
            style="width: 140px"
            @change="onFilterChange"
          >
            <el-option
              v-for="s in STATUS_OPTIONS"
              :key="s.value"
              :label="s.label"
              :value="s.value"
            />
          </el-select>
          <el-select
            v-model="query.severity"
            placeholder="全部级别"
            clearable
            style="width: 130px"
            @change="onFilterChange"
          >
            <el-option label="严重" value="CRITICAL" />
            <el-option label="警告" value="WARNING" />
            <el-option label="提示" value="INFO" />
          </el-select>
        </div>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="severityType((row as Alert).severity)" size="small">
              {{ (row as Alert).severity }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="标题" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" @click="openDetail(row as Alert)">
              {{ (row as Alert).title }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip
              v-if="(row as Alert).status === 'SUPPRESSED'"
              content="在告警频率的抑制窗口内 —— 仍然记录,但没有推送"
            >
              <el-tag :type="tagType((row as Alert).status)" size="small">
                {{ (row as Alert).statusDisplayName }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else :type="tagType((row as Alert).status)" size="small">
              {{ (row as Alert).statusDisplayName }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="规则" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ (row as Alert).ruleName ?? '—' }}</template>
        </el-table-column>

        <el-table-column label="任务" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ (row as Alert).jobName ?? '—' }}</template>
        </el-table-column>

        <el-table-column label="触发时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime((row as Alert).triggeredAt) }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'governance:alert:handle'"
              link
              type="warning"
              :disabled="['ACKNOWLEDGED', 'RESOLVED'].includes((row as Alert).status)"
              @click="onAcknowledge(row as Alert)"
            >
              认领
            </el-button>
            <el-button
              v-permission="'governance:alert:handle'"
              link
              type="success"
              :disabled="(row as Alert).status === 'RESOLVED'"
              @click="onResolve(row as Alert)"
            >
              关闭
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">没有告警 —— 好事</div>
        </template>
      </el-table>

      <el-pagination
        :current-page="query.page"
        :page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="(p: number) => { query.page = p; load() }"
        @size-change="(s: number) => { query.size = s; query.page = 1; load() }"
      />
    </el-card>

    <el-dialog v-model="detailVisible" title="告警详情" width="640px">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
        <el-descriptions-item label="内容">
          <div style="white-space: pre-wrap">{{ detail.content ?? '—' }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="tagType(detail.status)" size="small">
            {{ detail.statusDisplayName }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="触发规则">{{ detail.ruleName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="关联任务">{{ detail.jobName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="触发时间">
          {{ formatDateTime(detail.triggeredAt) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.notifiedAt" label="推送时间">
          {{ formatDateTime(detail.notifiedAt) }}
        </el-descriptions-item>
        <!-- 被抑制时给出压制者,顺着它能找到同一轮故障的第一条 -->
        <el-descriptions-item v-if="detail.suppressedBy" label="被哪一条压住">
          {{ detail.suppressedBy }}
          <div class="text-muted">
            同源告警在抑制窗口内不再推送 —— 但它仍然被记录在这里
          </div>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.notifyError" label="推送错误">
          <span class="metric--danger">{{ detail.notifyError }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.acknowledgedBy" label="处理人">
          {{ detail.acknowledgedBy }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.resolveNote" label="处置说明">
          {{ detail.resolveNote }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.summary {
  text-align: center;
}

.summary__label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.summary__value {
  font-size: 26px;
  font-weight: 600;
}

.summary__value--warning {
  color: var(--el-color-warning);
}

.metric--danger {
  color: var(--el-color-danger);
}
</style>
