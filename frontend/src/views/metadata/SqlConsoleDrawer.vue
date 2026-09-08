<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { format as formatSql } from 'sql-formatter'
import { dataSourceApi } from '@/api/datasource'
import type { DataSource } from '@/types/datasource'
import type { SqlQueryResult } from '@/types/query'

/**
 * SQL 查询控制台(功能 7「支持自定义查询类的 SQL 语句…支持 SQL 语句的格式化处理」)。
 *
 * <b>格式化放在前端,执行放在后端</b>:格式化是纯文本变换,没有任何需要
 * 服务端参与的理由;而执行必须过后端的只读护栏与行数/超时限制。
 * 前端<b>不</b>自己做语法校验 —— 那会造出第二份规则,两份迟早不一致,
 * 而不一致的表现是前端放行了后端拒绝的语句(或者更糟,反过来)。
 */
const visible = ref(false)
const current = ref<DataSource | null>(null)
const sql = ref('')
const maxRows = ref(200)
const running = ref(false)
const result = ref<SqlQueryResult | null>(null)
const errorText = ref('')

/** sql-formatter 的方言。取不到就用通用方言 —— 格式化失败不该阻断查询。 */
const dialect = computed(() => {
  switch (current.value?.type) {
    case 'MYSQL':
    case 'DORIS':
    case 'STARROCKS':
      return 'mysql'
    case 'POSTGRESQL':
      return 'postgresql'
    case 'ORACLE':
      return 'plsql'
    case 'SQLSERVER':
      return 'transactsql'
    // 达梦兼容 Oracle 语法,用 plsql 方言格式化效果最接近
    case 'DAMENG':
      return 'plsql'
    default:
      return 'sql'
  }
})

function open(row: DataSource) {
  current.value = row
  sql.value = ''
  result.value = null
  errorText.value = ''
  visible.value = true
}

function onFormat() {
  if (!sql.value.trim()) return
  try {
    sql.value = formatSql(sql.value, {
      language: dialect.value as never,
      keywordCase: 'upper',
      tabWidth: 2,
    })
  } catch {
    // 语句还没写完整时格式化会失败,这很正常 —— 提示一下就好,别打断输入
    ElMessage.warning('当前语句还不完整,无法格式化')
  }
}

async function onRun() {
  if (!current.value || !sql.value.trim()) {
    ElMessage.warning('请输入 SQL 语句')
    return
  }
  running.value = true
  errorText.value = ''
  result.value = null
  try {
    result.value = await dataSourceApi.query(current.value.id, {
      sql: sql.value,
      maxRows: maxRows.value,
    })
  } catch (e: unknown) {
    // 后端把数据库原话带回来了,原样展示 —— 对写错 SQL 的人来说,
    // 数据库自己的报错比平台的转述有用得多
    const body = e as { message?: string }
    errorText.value = body?.message ?? '查询失败'
  } finally {
    running.value = false
  }
}

defineExpose({ open })
</script>

<template>
  <el-drawer v-model="visible" size="76%" :title="`SQL 查询 · ${current?.name ?? ''}`">
    <div class="console">
      <el-alert type="info" :closable="false" show-icon class="console__notice">
        只允许查询类语句(SELECT / WITH / SHOW / DESC / EXPLAIN)。
        平台会强制行数上限与查询超时 —— 它连的是业务方的生产库,不该有能力对那些库做无界的事。
      </el-alert>

      <el-input
        v-model="sql"
        type="textarea"
        :rows="8"
        class="console__editor"
        placeholder="SELECT * FROM schema.table LIMIT 100"
        @keydown.ctrl.enter="onRun"
        @keydown.meta.enter="onRun"
      />

      <div class="console__toolbar">
        <div class="console__toolbar-left">
          <span class="text-muted">最多返回</span>
          <el-input-number v-model="maxRows" :min="1" :max="2000" :step="50" size="small" />
          <span class="text-muted">行</span>
        </div>
        <div>
          <el-button size="small" @click="onFormat">格式化</el-button>
          <el-button type="primary" size="small" :loading="running" @click="onRun">
            执行(Ctrl+Enter)
          </el-button>
        </div>
      </div>

      <el-alert v-if="errorText" type="error" :closable="false" show-icon class="console__error">
        <pre class="console__error-text">{{ errorText }}</pre>
      </el-alert>

      <template v-if="result">
        <div class="console__summary">
          <span>返回 {{ result.rowCount }} 行 · 耗时 {{ result.elapsedMillis }} ms</span>
          <!-- 截断必须显眼:用户看到 200 行却不知道后面还有,会据此得出错误结论 -->
          <el-tag v-if="result.truncated" type="warning" size="small" effect="dark">
            结果已截断,仅显示前 {{ result.rowCount }} 行
          </el-tag>
        </div>

        <el-table :data="result.rows" border size="small" max-height="420" class="console__result">
          <el-table-column
            v-for="(col, index) in result.columns"
            :key="col.name"
            :label="col.name"
            min-width="140"
            show-overflow-tooltip
          >
            <template #header>
              <div class="console__col-header">
                <span>{{ col.name }}</span>
                <span class="text-muted">{{ col.rawType }}</span>
              </div>
            </template>
            <template #default="{ row }">
              <!-- NULL 与空字符串必须能区分开,否则用户没法判断数据到底有没有值 -->
              <span v-if="row[index] === null" class="console__null">NULL</span>
              <span v-else class="text-mono">{{ row[index] }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.console {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.console__editor :deep(textarea) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}

.console__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.console__toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.console__error-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
}

.console__summary {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #606266;
  font-size: 13px;
}

.console__col-header {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.console__null {
  color: #c0c4cc;
  font-style: italic;
}
</style>
