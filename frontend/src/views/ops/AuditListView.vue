<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { auditApi } from '@/api/governance'
import { formatDateTime } from '@/utils/format'
import type { AuditRecord } from '@/types/governance'

/**
 * 审计日志(序号 27)。
 *
 * 这个页面<b>只有查询</b> —— 没有新建、没有编辑、没有删除按钮,因为后端
 * 也没有那些接口。一条能被修改的审计记录不是审计记录。
 *
 * 需求要求「跨数据集成与数据开发聚合」:所有 Space 的操作都在同一张表里,
 * 所以"上周谁动过这个数据源"有一个能回答它的地方(架构约束 R4)。
 */

const loading = ref(false)
const rows = ref<AuditRecord[]>([])
const total = ref(0)
const actions = ref<string[]>([])

const query = reactive({
  page: 1,
  size: 20,
  action: '',
  resourceType: '',
  succeeded: '' as '' | 'true' | 'false',
})

const RESOURCE_TYPES = [
  'JOB', 'STREAMING_JOB', 'TASK_CATALOG', 'EXECUTION', 'EXECUTOR', 'ARTIFACT',
  'DATASOURCE', 'RULE', 'ALERT_RULE', 'ALERT', 'ALERT_CHANNEL',
  'WORKSPACE', 'USER', 'ROLE', 'CREDENTIAL', 'SESSION',
]

const ACTION_LABELS: Record<string, string> = {
  CREATE: '创建',
  UPDATE: '修改',
  DELETE: '删除',
  EXECUTE: '执行',
  LOGIN: '登录',
  EXPORT: '导出',
}

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}

function actionType(action: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (action === 'DELETE') return 'danger'
  if (action === 'CREATE') return 'success'
  if (action === 'UPDATE') return 'warning'
  if (action === 'EXECUTE') return 'primary'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const page = await auditApi.search({
      page: query.page,
      size: query.size,
      action: query.action || undefined,
      resourceType: query.resourceType || undefined,
      succeeded: query.succeeded === '' ? undefined : query.succeeded === 'true',
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  actions.value = await auditApi.actions()
  await load()
})

function onFilterChange() {
  query.page = 1
  load()
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-select
            v-model="query.action"
            placeholder="全部操作"
            clearable
            style="width: 130px"
            @change="onFilterChange"
          >
            <el-option
              v-for="a in actions"
              :key="a"
              :label="actionLabel(a)"
              :value="a"
            />
          </el-select>
          <el-select
            v-model="query.resourceType"
            placeholder="全部资源"
            clearable
            filterable
            style="width: 160px"
            @change="onFilterChange"
          >
            <el-option v-for="t in RESOURCE_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
          <el-select
            v-model="query.succeeded"
            placeholder="全部结果"
            clearable
            style="width: 130px"
            @change="onFilterChange"
          >
            <el-option label="成功" value="true" />
            <el-option label="失败" value="false" />
          </el-select>
        </div>
      </div>

      <el-alert type="info" :closable="false" show-icon style="margin: 12px 0">
        <template #title>审计记录不可变、只追加</template>
        这个页面没有编辑与删除 —— 一条能被修改的审计记录不是审计记录。
        失败的操作同样留痕:那往往才是要查的。
      </el-alert>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime((row as AuditRecord).occurredAt) }}
          </template>
        </el-table-column>

        <el-table-column label="操作人" width="130">
          <template #default="{ row }">
            {{ (row as AuditRecord).username ?? '—' }}
            <div class="text-muted">{{ (row as AuditRecord).clientIp ?? '' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-tag :type="actionType((row as AuditRecord).action)" size="small">
              {{ actionLabel((row as AuditRecord).action) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="资源" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div>
              {{ (row as AuditRecord).resourceType }}
              <span v-if="(row as AuditRecord).resourceName">
                · {{ (row as AuditRecord).resourceName }}
              </span>
            </div>
            <div v-if="(row as AuditRecord).resourceId" class="text-muted">
              {{ (row as AuditRecord).resourceId }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="归属" width="120">
          <template #default="{ row }">
            <span class="text-muted">{{ (row as AuditRecord).ownerSpace ?? '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="(row as AuditRecord).succeeded ? 'success' : 'danger'" size="small">
              {{ (row as AuditRecord).succeeded ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="请求" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-mono text-muted">
              {{ (row as AuditRecord).requestSummary ?? '—' }}
            </span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">没有匹配的审计记录</div>
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
  </div>
</template>
