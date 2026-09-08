<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { monitorApi } from '@/api/governance'
import { formatDateTime } from '@/utils/format'
import type { MonitorDashboard, RecentFailure, TypeBreakdown } from '@/types/governance'

/**
 * 任务监控(序号 24)。
 *
 * 五个口径直接来自需求原文。它们能一次算出来,是因为执行事实只有一张表
 * (架构约束 R4)—— 页面上这个"跨数据集成与数据开发"的拆分表,如果当初
 * 按菜单拆了五张执行记录表,就是一个每加一种任务类型都要改的 UNION。
 */

const router = useRouter()

const loading = ref(false)
const days = ref<number | ''>('')
const data = ref<MonitorDashboard | null>(null)

let timer: ReturnType<typeof setInterval> | undefined

async function load() {
  loading.value = true
  try {
    data.value = await monitorApi.dashboard(days.value === '' ? undefined : days.value)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  // 监控页是给值班的人看的:它必须自己动。30 秒是一个折中 ——
  // 再快只会让数字跳动而看不清,再慢就失去"盯着"的意义
  timer = setInterval(load, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

/** 毫秒转人话。时延用"秒"比用一串六位数字好读 */
function humanDuration(ms: number): string {
  if (ms < 1000) return `${ms} 毫秒`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} 秒`
  return `${(ms / 60000).toFixed(1)} 分钟`
}

function humanRows(rows: number): string {
  if (rows < 10000) return String(rows)
  if (rows < 100000000) return `${(rows / 10000).toFixed(1)} 万`
  return `${(rows / 100000000).toFixed(2)} 亿`
}

function viewExecution(row: RecentFailure) {
  router.push({ name: 'executions', query: { highlight: row.executionId } })
}

/** 失败率的颜色。阈值是经验值,不是需求给的 —— 所以写在这里而不是后端 */
function rateClass(rate: number): string {
  if (rate >= 20) return 'metric--danger'
  if (rate >= 5) return 'metric--warning'
  return ''
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-radio-group v-model="days" @change="load">
            <el-radio-button :value="''">全量</el-radio-button>
            <el-radio-button :value="1">今天</el-radio-button>
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
          </el-radio-group>
        </div>
        <span class="text-muted" v-if="data">
          更新于 {{ formatDateTime(data.generatedAt) }}
        </span>
      </div>

      <el-alert type="info" :closable="false" show-icon style="margin: 12px 0">
        <template #title>这五个数字跨数据集成与数据开发一起算</template>
        离线同步、整库迁移、文件/接口解析、实时开发、离线开发、工作流节点的执行
        都在同一张事实表里 —— 所以"今天一共跑了多少、失败了多少"有唯一的答案。
      </el-alert>

      <el-row :gutter="12" v-loading="loading" v-if="data">
        <el-col :span="4">
          <el-card shadow="never" class="metric">
            <div class="metric__label">执行总数</div>
            <div class="metric__value">{{ data.totalExecutions }}</div>
            <div class="text-muted">其中 {{ data.runningExecutions }} 个进行中</div>
          </el-card>
        </el-col>
        <el-col :span="4">
          <el-card shadow="never" class="metric" :class="rateClass(data.failureRatePercent)">
            <div class="metric__label">失败数</div>
            <div class="metric__value">{{ data.failedExecutions }}</div>
            <div class="text-muted">失败率 {{ data.failureRatePercent }}%</div>
          </el-card>
        </el-col>
        <el-col :span="5">
          <el-card shadow="never" class="metric">
            <div class="metric__label">今日新增抽取</div>
            <div class="metric__value">{{ humanRows(data.todayRowsExtracted) }}</div>
            <div class="text-muted">行(按执行结束时间计)</div>
          </el-card>
        </el-col>
        <el-col :span="5">
          <el-card shadow="never" class="metric">
            <div class="metric__label">总计抽取</div>
            <div class="metric__value">{{ humanRows(data.totalRowsExtracted) }}</div>
            <div class="text-muted">行</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="metric">
            <div class="metric__label">任务时延</div>
            <div class="metric__value">{{ humanDuration(data.avgLatencyMs) }}</div>
            <div class="text-muted">已完成执行的平均耗时</div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="12" style="margin-top: 12px" v-if="data">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>按作业种类</template>
          <el-table :data="data.byJobType" size="small">
            <el-table-column label="种类" min-width="120">
              <template #default="{ row }">{{ (row as TypeBreakdown).displayName }}</template>
            </el-table-column>
            <el-table-column label="执行数" width="90" align="right">
              <template #default="{ row }">{{ (row as TypeBreakdown).total }}</template>
            </el-table-column>
            <el-table-column label="失败" width="90" align="right">
              <template #default="{ row }">
                <span :class="(row as TypeBreakdown).failed > 0 ? 'metric--danger' : ''">
                  {{ (row as TypeBreakdown).failed }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="写入行数" width="120" align="right">
              <template #default="{ row }">
                {{ humanRows((row as TypeBreakdown).rowsWritten) }}
              </template>
            </el-table-column>
            <template #empty>
              <div class="text-muted" style="padding: 16px">这个窗口里还没有执行记录</div>
            </template>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>最近的失败</template>
          <el-table :data="data.recentFailures" size="small">
            <el-table-column label="任务" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ (row as RecentFailure).jobName ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag type="danger" size="small">{{ (row as RecentFailure).status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="原因" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ (row as RecentFailure).message ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="" width="70">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="viewExecution(row as RecentFailure)">
                  查看
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="text-muted" style="padding: 16px">这个窗口里没有失败 —— 好事</div>
            </template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.metric {
  text-align: center;
}

.metric__label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.metric__value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.4;
}

.metric--warning .metric__value,
span.metric--warning {
  color: var(--el-color-warning);
}

.metric--danger .metric__value,
span.metric--danger {
  color: var(--el-color-danger);
}
</style>
