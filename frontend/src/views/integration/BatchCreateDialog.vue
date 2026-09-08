<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { jobApi } from '@/api/job'
import { dataSourceApi } from '@/api/datasource'
import type { BatchCreateResult, TaskCatalogNode } from '@/types/job'
import type { DataSource } from '@/types/datasource'

/**
 * 批量新增同步任务(功能 14)。
 *
 * <p>创建的是 <b>N 个独立定义</b>,不是一个"批量任务"。这个区分在界面上也要
 * 说清楚 —— 用户看到"批量"两个字时,默认的想象是一个能一起跑、一起看结果的
 * 东西,而这里恰恰相反:创建完之后它们各自编译、各自调度、各自有执行记录。
 */

const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const submitting = ref(false)
const dataSources = ref<DataSource[]>([])
const catalogs = ref<TaskCatalogNode[]>([])
const result = ref<BatchCreateResult | null>(null)

const form = reactive({
  namePattern: '同步-{table}',
  description: '',
  catalogId: null as string | null,

  sourceDataSourceId: '',
  sourceDatabase: '',
  sourceSchema: '',
  tablesText: '',

  targetDataSourceId: '',
  targetDatabase: '',
  targetSchema: '',
  targetTablePrefix: '',
  targetTableSuffix: '',

  writeMode: 'APPEND',
  batchSize: 1000,
  timeoutMs: 3600000,
})

/** 一行一张表。粘贴一列表名是最省事的输入方式,不必先做成 JSON */
const tables = computed(() =>
  form.tablesText
    .split('\n')
    .map((t) => t.trim())
    .filter((t) => t.length > 0),
)

/** 模板里没有 {table} 时后端会自动追加表名,这里把结果先显示出来 */
const namePreview = computed(() => {
  const first = tables.value[0]
  if (!first) return ''
  const pattern = form.namePattern.trim() || '同步-{table}'
  return pattern.includes('{table}')
    ? pattern.replace('{table}', first)
    : `${pattern}-${first}`
})

const tablePreview = computed(() => {
  const first = tables.value[0]
  if (!first) return ''
  return `${form.targetTablePrefix}${first}${form.targetTableSuffix}`
})

async function open(catalogNodes: TaskCatalogNode[] = []) {
  visible.value = true
  result.value = null
  catalogs.value = catalogNodes
  const page = await dataSourceApi.list({ page: 1, size: 200 })
  dataSources.value = page.records
}

async function submit() {
  if (!form.sourceDataSourceId || !form.targetDataSourceId) {
    ElMessage.warning('请选择来源与目标数据源')
    return
  }
  if (tables.value.length === 0) {
    ElMessage.warning('请至少填写一张源表')
    return
  }
  submitting.value = true
  try {
    // 部分成功是正常结果 —— 不 catch 掉再报错,而是把 failed 列表显示出来
    result.value = await jobApi.createBatch({
      namePattern: form.namePattern || undefined,
      description: form.description || undefined,
      catalogId: form.catalogId,
      sourceDataSourceId: form.sourceDataSourceId,
      sourceDatabase: form.sourceDatabase || undefined,
      sourceSchema: form.sourceSchema || undefined,
      tables: tables.value,
      targetDataSourceId: form.targetDataSourceId,
      targetDatabase: form.targetDatabase || undefined,
      targetSchema: form.targetSchema || undefined,
      targetTablePrefix: form.targetTablePrefix || undefined,
      targetTableSuffix: form.targetTableSuffix || undefined,
      writeMode: form.writeMode,
      batchSize: form.batchSize,
      timeoutMs: form.timeoutMs,
    })
    if (result.value.created.length > 0) {
      ElMessage.success(`已创建 ${result.value.created.length} 个任务定义`)
      emit('saved')
    }
  } finally {
    submitting.value = false
  }
}

function close() {
  visible.value = false
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="批量新增同步任务" width="760px" destroy-on-close>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>每张源表生成一个独立的任务定义</template>
      创建后它们各自编译、各自调度、各自有执行记录 —— 不是一个能一起跑的批量任务。
      字段映射留空,需要在各自的编辑页里补齐后才能编译通过。
    </el-alert>

    <el-form :model="form" label-width="110px">
      <el-divider content-position="left">来源</el-divider>
      <el-form-item label="来源数据源" required>
        <el-select v-model="form.sourceDataSourceId" filterable placeholder="选择数据源" style="width: 100%">
          <el-option v-for="d in dataSources" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="数据库">
            <el-input v-model="form.sourceDatabase" placeholder="database" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Schema">
            <el-input v-model="form.sourceSchema" placeholder="schema" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="源表清单" required>
        <el-input
          v-model="form.tablesText"
          type="textarea"
          :rows="6"
          placeholder="一行一张表名,可直接从表清单里粘贴"
        />
        <div class="text-muted">已填写 {{ tables.length }} 张表(单次上限 200 张)</div>
      </el-form-item>

      <el-divider content-position="left">目标</el-divider>
      <el-form-item label="目标数据源" required>
        <el-select v-model="form.targetDataSourceId" filterable placeholder="选择数据源" style="width: 100%">
          <el-option v-for="d in dataSources" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="数据库">
            <el-input v-model="form.targetDatabase" placeholder="database" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Schema">
            <el-input v-model="form.targetSchema" placeholder="schema" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="表名前缀">
            <el-input v-model="form.targetTablePrefix" placeholder="如 ods_" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="表名后缀">
            <el-input v-model="form.targetTableSuffix" placeholder="如 _di" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">命名与归类</el-divider>
      <el-form-item label="任务名模板">
        <el-input v-model="form.namePattern" placeholder="同步-{table}" />
        <div v-if="namePreview" class="text-muted">
          第一张表将命名为:{{ namePreview }} → 写入 {{ tablePreview }}
        </div>
      </el-form-item>
      <el-form-item label="任务目录">
        <el-tree-select
          v-model="form.catalogId"
          :data="catalogs"
          :props="{ label: 'name', children: 'children' }"
          node-key="id"
          check-strictly
          clearable
          placeholder="不选则为未分类"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" placeholder="选填" />
      </el-form-item>

      <el-divider content-position="left">执行参数</el-divider>
      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="写入模式">
            <el-select v-model="form.writeMode" style="width: 100%">
              <el-option label="追加" value="APPEND" />
              <el-option label="覆盖" value="OVERWRITE" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="批次大小">
            <el-input-number v-model="form.batchSize" :min="1" :max="100000" controls-position="right" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="超时(毫秒)">
            <el-input-number v-model="form.timeoutMs" :min="1000" :step="60000" controls-position="right" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <!--
      部分成功是正常结果,所以结果区同时显示成功与失败,而不是只在全成功时
      关闭对话框、全失败时弹一个错误
    -->
    <template v-if="result">
      <el-divider content-position="left">创建结果</el-divider>
      <el-alert
        :type="result.failed.length === 0 ? 'success' : 'warning'"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      >
        成功 {{ result.created.length }} 个,失败 {{ result.failed.length }} 个
      </el-alert>
      <el-table v-if="result.failed.length" :data="result.failed" size="small" max-height="200">
        <el-table-column prop="table" label="源表" width="200" />
        <el-table-column prop="reason" label="失败原因" show-overflow-tooltip />
      </el-table>
    </template>

    <template #footer>
      <el-button @click="close">{{ result ? '关闭' : '取消' }}</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        创建 {{ tables.length }} 个任务
      </el-button>
    </template>
  </el-dialog>
</template>
