<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { artifactApi, jobApi } from '@/api/job'
import { dataSourceApi } from '@/api/datasource'
import DdlPreviewDrawer from './DdlPreviewDrawer.vue'
import WorkflowGraphEditor from '@/views/dev/WorkflowGraphEditor.vue'
import type { DataSource } from '@/types/datasource'
import type {
  Artifact,
  JobDefinition,
  JobType,
  JobTypeInfo,
  TaskCatalogNode,
  WorkflowEdge,
  WorkflowNode,
} from '@/types/job'

/**
 * 新建 / 编辑任务定义。
 *
 * <b>表单按 jobType 渲染,但骨架是同一套。</b> 名称、描述、超时、重试对所有类型
 * 都一样;差异部分是 config,由各类型自己的片段负责。七种类型各写一个完整对话框,
 * 会让"给所有任务加一个超时设置"变成七处修改。
 *
 * P2 落地了离线同步与整库迁移两种类型的表单;其余类型的 config 用通用的
 * JSON 编辑器兜底 —— 那比隐藏它们诚实:后端已经支持,只是前端还没做专用表单。
 */

const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const types = ref<JobTypeInfo[]>([])
const dataSources = ref<DataSource[]>([])
const catalogs = ref<TaskCatalogNode[]>([])
const ddlDrawer = ref<InstanceType<typeof DdlPreviewDrawer>>()

const form = reactive({
  name: '',
  jobType: '' as '' | JobType,
  description: '',
  /** 所属任务目录(功能 16);null 表示未分类 */
  catalogId: null as string | null,
  timeoutMs: 7200000,
  retryMaxAttempts: 1,
  retryBackoffSeconds: 30,
})

/** 离线同步的配置 */
const syncConfig = reactive({
  sourceDataSourceId: '',
  sourceDatabase: '',
  sourceSchema: '',
  sourceTable: '',
  targetDataSourceId: '',
  targetDatabase: '',
  targetSchema: '',
  targetTable: '',
  writeMode: 'APPEND',
  batchSize: 1000,
  whereClause: '',
  primaryKeys: [] as string[],
})

/** 字段映射用行数组编辑,提交时转成对象 —— 对象在表单里没法增删行 */
const mappingRows = ref<{ source: string; target: string }[]>([])

/** 整库迁移的配置 */
const migrationConfig = reactive({
  sourceDataSourceId: '',
  sourceDatabase: '',
  sourceSchema: '',
  targetDataSourceId: '',
  targetDatabase: '',
  targetSchema: '',
  tables: [] as string[],
  tablePrefix: '',
  tableSuffix: '',
  lowercaseNames: false,
  createTable: true,
  writeMode: 'APPEND',
  batchSize: 1000,
})
/** 用户在预览里改过的建表语句:源表名 → 语句 */
const ddlOverrides = ref<Record<string, string>>({})

/** 其余类型的配置走 JSON 兜底 */
const rawConfigJson = ref('{}')

/** 工作流的图(功能 22) */
const wfNodes = ref<WorkflowNode[]>([])
const wfEdges = ref<WorkflowEdge[]>([])
/** 可被工作流引用的任务定义:已发布的、且不是工作流(本期不支持嵌套) */
const wfCandidates = ref<JobDefinition[]>([])

/** 实时/离线开发的配置(功能 18/20) */
const devConfig = reactive({
  sourceKind: 'SQL',
  sql: '',
  dataSourceId: '',
  artifactId: '',
  entryClass: '',
  programArgs: '',
  parallelism: 1,
  checkpointIntervalMs: 60000,
  restartStrategy: 'EXPONENTIAL',
})
const artifacts = ref<Artifact[]>([])

const isSync = computed(() => form.jobType === 'OFFLINE_SYNC')
const isMigration = computed(() => form.jobType === 'DB_MIGRATION')
const isWorkflow = computed(() => form.jobType === 'WORKFLOW')
/** 实时开发(18)与离线开发(20):编译期几乎一样,所以共用一套表单 */
const isDev = computed(() => form.jobType === 'STREAMING' || form.jobType === 'BATCH')
const isStreaming = computed(() => form.jobType === 'STREAMING')
const isRaw = computed(
  () => !!form.jobType && !isSync.value && !isMigration.value
    && !isWorkflow.value && !isDev.value,
)

const selectedType = computed(() => types.value.find((t) => t.type === form.jobType) ?? null)

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  jobType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
}))

async function open(
  row: JobDefinition | null,
  typeList: JobTypeInfo[],
  catalogNodes: TaskCatalogNode[] = [],
  /** 由路由钉死的类型(如「工作流编排」页新建时);null 表示让用户自己选 */
  presetType: JobType | null = null,
) {
  types.value = typeList
  catalogs.value = catalogNodes
  editingId.value = row?.id ?? null
  ddlOverrides.value = {}

  Object.assign(form, {
    name: row?.name ?? '',
    jobType: row?.jobType ?? presetType ?? '',
    description: row?.description ?? '',
    catalogId: row?.catalogId ?? null,
    timeoutMs: row?.timeoutMs ?? 7200000,
    retryMaxAttempts: row?.retryMaxAttempts ?? 1,
    retryBackoffSeconds: row?.retryBackoffSeconds ?? 30,
  })

  const config = (row?.config ?? {}) as Record<string, unknown>
  if (row?.jobType === 'WORKFLOW') {
    wfNodes.value = (config.nodes as WorkflowNode[] | undefined)?.map((n) => ({ ...n })) ?? []
    wfEdges.value = (config.edges as WorkflowEdge[] | undefined)?.map((e) => ({ ...e })) ?? []
  } else {
    wfNodes.value = []
    wfEdges.value = []
  }
  if (row?.jobType === 'STREAMING' || row?.jobType === 'BATCH') {
    Object.assign(devConfig, {
      sourceKind: str(config.sourceKind) || 'SQL',
      sql: str(config.sql),
      dataSourceId: str(config.dataSourceId),
      artifactId: str(config.artifactId),
      entryClass: str(config.entryClass),
      programArgs: str(config.programArgs),
      parallelism: Number(config.parallelism ?? 1),
      checkpointIntervalMs: Number(config.checkpointIntervalMs ?? 60000),
      restartStrategy: str(config.restartStrategy) || 'EXPONENTIAL',
    })
  }
  if (row?.jobType === 'OFFLINE_SYNC') {
    Object.assign(syncConfig, {
      sourceDataSourceId: str(config.sourceDataSourceId),
      sourceDatabase: str(config.sourceDatabase),
      sourceSchema: str(config.sourceSchema),
      sourceTable: str(config.sourceTable),
      targetDataSourceId: str(config.targetDataSourceId),
      targetDatabase: str(config.targetDatabase),
      targetSchema: str(config.targetSchema),
      targetTable: str(config.targetTable),
      writeMode: str(config.writeMode) || 'APPEND',
      batchSize: Number(config.batchSize ?? 1000),
      whereClause: str(config.whereClause),
      primaryKeys: Array.isArray(config.primaryKeys) ? (config.primaryKeys as string[]) : [],
    })
    const mappings = (config.fieldMappings ?? {}) as Record<string, string>
    mappingRows.value = Object.entries(mappings).map(([source, target]) => ({ source, target }))
  } else if (row?.jobType === 'DB_MIGRATION') {
    Object.assign(migrationConfig, {
      sourceDataSourceId: str(config.sourceDataSourceId),
      sourceDatabase: str(config.sourceDatabase),
      sourceSchema: str(config.sourceSchema),
      targetDataSourceId: str(config.targetDataSourceId),
      targetDatabase: str(config.targetDatabase),
      targetSchema: str(config.targetSchema),
      tables: Array.isArray(config.tables) ? (config.tables as string[]) : [],
      tablePrefix: str(config.tablePrefix),
      tableSuffix: str(config.tableSuffix),
      lowercaseNames: Boolean(config.lowercaseNames),
      createTable: config.createTable === undefined ? true : Boolean(config.createTable),
      writeMode: str(config.writeMode) || 'APPEND',
      batchSize: Number(config.batchSize ?? 1000),
    })
    ddlOverrides.value = (config.ddlOverrides ?? {}) as Record<string, string>
  } else {
    rawConfigJson.value = JSON.stringify(config, null, 2)
  }

  if (dataSources.value.length === 0) {
    const page = await dataSourceApi.list({ page: 1, size: 200 })
    dataSources.value = page.records
  }
  visible.value = true
}

function str(value: unknown): string {
  return value == null ? '' : String(value)
}

async function onTypeChange() {
  mappingRows.value = []
  rawConfigJson.value = '{}'
  ddlOverrides.value = {}
  wfNodes.value = []
  wfEdges.value = []
  // 工作流要选引用的任务,开发任务要选制品 —— 按需拉,不在打开对话框时
  // 就把两份列表都请求一遍
  if (isWorkflow.value) {
    await loadWorkflowCandidates()
  } else if (isDev.value) {
    await loadArtifacts()
  }
}

/** 可被引用的任务:已发布状态,且排除工作流自己与其他工作流 */
async function loadWorkflowCandidates() {
  const page = await jobApi.list({ page: 1, size: 200 })
  wfCandidates.value = page.records.filter(
    (j) => j.jobType !== 'WORKFLOW'
      && ['PUBLISHED', 'SCHEDULING', 'PAUSED'].includes(j.status),
  )
}

async function loadArtifacts() {
  const page = await artifactApi.list({ page: 1, size: 200 })
  artifacts.value = page.records
}

function buildConfig(): Record<string, unknown> {
  if (isSync.value) {
    const fieldMappings: Record<string, string> = {}
    for (const row of mappingRows.value) {
      if (row.source.trim() && row.target.trim()) {
        fieldMappings[row.source.trim()] = row.target.trim()
      }
    }
    return { ...syncConfig, fieldMappings }
  }
  if (isMigration.value) {
    return { ...migrationConfig, ddlOverrides: ddlOverrides.value }
  }
  if (isWorkflow.value) {
    return { nodes: wfNodes.value, edges: wfEdges.value }
  }
  if (isDev.value) {
    const base: Record<string, unknown> = {
      sourceKind: devConfig.sourceKind,
      parallelism: devConfig.parallelism,
    }
    if (devConfig.sourceKind === 'SQL') {
      base.sql = devConfig.sql
      // 批作业的 SQL 要在某个数据源上跑;流作业的 SQL 由引擎解析,不需要
      if (!isStreaming.value) base.dataSourceId = devConfig.dataSourceId
    } else {
      base.artifactId = devConfig.artifactId
      base.entryClass = devConfig.entryClass
      base.programArgs = devConfig.programArgs
    }
    if (isStreaming.value) {
      base.checkpointIntervalMs = devConfig.checkpointIntervalMs
      base.restartStrategy = devConfig.restartStrategy
    }
    return base
  }
  try {
    return JSON.parse(rawConfigJson.value) as Record<string, unknown>
  } catch {
    throw new Error('配置不是合法的 JSON')
  }
}

async function onSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  let config: Record<string, unknown>
  try {
    config = buildConfig()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '配置有误')
    return
  }

  submitting.value = true
  try {
    const payload = {
      name: form.name,
      jobType: form.jobType as JobType,
      description: form.description || undefined,
      catalogId: form.catalogId,
      config,
      timeoutMs: form.timeoutMs,
      retryMaxAttempts: form.retryMaxAttempts,
      retryBackoffSeconds: form.retryBackoffSeconds,
    }
    if (editingId.value) {
      await jobApi.update(editingId.value, payload)
      ElMessage.success('已保存。定义已改动,需重新编译并发布后才能执行')
    } else {
      await jobApi.create(payload)
      ElMessage.success('已创建,状态为草稿。请编译并发布后再执行')
    }
    visible.value = false
    emit('saved')
  } finally {
    submitting.value = false
  }
}

/** 打开建表语句预览(功能 9「预览并修改建表语句」) */
function onPreviewDdl(table: string) {
  ddlDrawer.value?.open({
    sourceDataSourceId: migrationConfig.sourceDataSourceId,
    sourceDatabase: migrationConfig.sourceDatabase,
    sourceSchema: migrationConfig.sourceSchema,
    sourceTable: table,
    targetDataSourceId: migrationConfig.targetDataSourceId,
    targetDatabase: migrationConfig.targetDatabase,
    targetSchema: migrationConfig.targetSchema,
    tablePrefix: migrationConfig.tablePrefix,
    tableSuffix: migrationConfig.tableSuffix,
    lowercaseNames: migrationConfig.lowercaseNames,
  }, ddlOverrides.value[table])
}

function onDdlConfirmed(table: string, script: string) {
  ddlOverrides.value = { ...ddlOverrides.value, [table]: script }
  ElMessage.success(`表「${table}」的建表语句已保存,执行时将使用它`)
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editingId ? '编辑任务' : '新建任务'"
    width="820px"
    destroy-on-close
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
      <el-form-item label="任务名称" prop="name">
        <el-input v-model="form.name" placeholder="同一空间内唯一" />
      </el-form-item>

      <el-form-item label="任务类型" prop="jobType">
        <el-select
          v-model="form.jobType"
          placeholder="请选择"
          :disabled="!!editingId"
          style="width: 100%"
          @change="onTypeChange"
        >
          <el-option v-for="t in types" :key="t.type" :label="t.displayName" :value="t.type">
            <span>{{ t.displayName }}</span>
            <span class="text-muted" style="float: right">
              {{ t.schedulable ? '可周期调度' : t.longRunning ? '常驻' : '一次性' }}
            </span>
          </el-option>
        </el-select>
        <div v-if="editingId" class="text-muted">
          类型不可变更 —— 换类型等于换了一个东西,配置结构与运行语义全变。
        </div>
        <div v-else-if="selectedType && !selectedType.schedulable" class="text-muted">
          {{ selectedType.longRunning
            ? '实时任务是常驻的,启动后一直运行,不需要周期触发'
            : '整库迁移是一次性任务,用「立即执行」触发' }}
        </div>
      </el-form-item>

      <!-- ── 离线同步(功能 11)────────────────────────────────────── -->
      <template v-if="isSync">
        <el-divider content-position="left">源</el-divider>
        <el-form-item label="源数据源">
          <el-select v-model="syncConfig.sourceDataSourceId" filterable style="width: 100%">
            <el-option
              v-for="d in dataSources"
              :key="d.id"
              :label="`${d.name}(${d.typeDisplayName})`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="源库 / 模式 / 表">
          <el-space>
            <el-input v-model="syncConfig.sourceDatabase" placeholder="库" style="width: 180px" />
            <el-input v-model="syncConfig.sourceSchema" placeholder="模式(可空)" style="width: 180px" />
            <el-input v-model="syncConfig.sourceTable" placeholder="表" style="width: 200px" />
          </el-space>
        </el-form-item>
        <el-form-item label="过滤条件">
          <el-input v-model="syncConfig.whereClause" placeholder="增量同步的 WHERE 条件,可空" />
          <div class="text-muted">如 update_time &gt;= '2026-01-01';留空则全量</div>
        </el-form-item>

        <el-divider content-position="left">目标</el-divider>
        <el-form-item label="目标数据源">
          <el-select v-model="syncConfig.targetDataSourceId" filterable style="width: 100%">
            <el-option
              v-for="d in dataSources"
              :key="d.id"
              :label="`${d.name}(${d.typeDisplayName})`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标库 / 模式 / 表">
          <el-space>
            <el-input v-model="syncConfig.targetDatabase" placeholder="库" style="width: 180px" />
            <el-input v-model="syncConfig.targetSchema" placeholder="模式(可空)" style="width: 180px" />
            <el-input v-model="syncConfig.targetTable" placeholder="表" style="width: 200px" />
          </el-space>
        </el-form-item>
        <el-form-item label="写入模式">
          <el-radio-group v-model="syncConfig.writeMode">
            <el-radio value="APPEND">追加</el-radio>
            <el-radio value="OVERWRITE">覆盖(先清空目标表)</el-radio>
            <el-radio value="UPSERT">按主键更新</el-radio>
          </el-radio-group>
          <div v-if="syncConfig.writeMode === 'OVERWRITE'" class="text-muted">
            会在写入前清空目标表 —— 确认它没有其它来源的数据。
          </div>
        </el-form-item>
        <el-form-item v-if="syncConfig.writeMode === 'UPSERT'" label="主键字段">
          <el-select
            v-model="syncConfig.primaryKeys"
            multiple
            filterable
            allow-create
            placeholder="输入目标表的主键字段名"
            style="width: 100%"
          >
            <el-option v-for="k in syncConfig.primaryKeys" :key="k" :label="k" :value="k" />
          </el-select>
          <div class="text-muted">
            UPSERT 靠主键判断记录是否已存在,主键必须出现在下面的字段映射目标端。
          </div>
        </el-form-item>

        <el-divider content-position="left">字段映射</el-divider>
        <el-form-item label="映射">
          <div class="mappings">
            <div v-for="(row, index) in mappingRows" :key="index" class="mappings__row">
              <el-input v-model="row.source" placeholder="源字段" style="width: 220px" />
              <span class="text-muted">→</span>
              <el-input v-model="row.target" placeholder="目标字段" style="width: 220px" />
              <el-button link type="danger" @click="mappingRows.splice(index, 1)">删除</el-button>
            </div>
            <el-button link type="primary" @click="mappingRows.push({ source: '', target: '' })">
              + 添加映射
            </el-button>
            <div class="text-muted">
              编译时会校验字段是否存在、类型能否对接。类型有损时给提醒而不阻断。
            </div>
          </div>
        </el-form-item>

        <el-form-item label="写入批次">
          <el-input-number v-model="syncConfig.batchSize" :min="1" :max="50000" :step="100" />
          <span class="text-muted" style="margin-left: 8px">
            批次过大时一次失败要回滚的数据量也大
          </span>
        </el-form-item>
      </template>

      <!-- ── 整库迁移(功能 9)────────────────────────────────────── -->
      <template v-if="isMigration">
        <el-divider content-position="left">源</el-divider>
        <el-form-item label="源数据源">
          <el-select v-model="migrationConfig.sourceDataSourceId" filterable style="width: 100%">
            <el-option
              v-for="d in dataSources"
              :key="d.id"
              :label="`${d.name}(${d.typeDisplayName})`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="源库 / 模式">
          <el-space>
            <el-input v-model="migrationConfig.sourceDatabase" placeholder="库" style="width: 200px" />
            <el-input v-model="migrationConfig.sourceSchema" placeholder="模式(可空)" style="width: 200px" />
          </el-space>
        </el-form-item>
        <el-form-item label="迁移的表">
          <el-select
            v-model="migrationConfig.tables"
            multiple
            filterable
            allow-create
            placeholder="留空 = 迁移整个库"
            style="width: 100%"
          >
            <el-option v-for="t in migrationConfig.tables" :key="t" :label="t" :value="t" />
          </el-select>
          <div class="text-muted">留空表示迁移源库下的全部表(不含视图)。</div>
        </el-form-item>

        <el-divider content-position="left">目标</el-divider>
        <el-form-item label="目标数据源">
          <el-select v-model="migrationConfig.targetDataSourceId" filterable style="width: 100%">
            <el-option
              v-for="d in dataSources"
              :key="d.id"
              :label="`${d.name}(${d.typeDisplayName})`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标库 / 模式">
          <el-space>
            <el-input v-model="migrationConfig.targetDatabase" placeholder="库" style="width: 200px" />
            <el-input v-model="migrationConfig.targetSchema" placeholder="模式(可空)" style="width: 200px" />
          </el-space>
        </el-form-item>
        <el-form-item label="表名规则">
          <el-space>
            <el-input v-model="migrationConfig.tablePrefix" placeholder="前缀" style="width: 140px" />
            <el-input v-model="migrationConfig.tableSuffix" placeholder="后缀" style="width: 140px" />
            <el-checkbox v-model="migrationConfig.lowercaseNames">转小写</el-checkbox>
          </el-space>
        </el-form-item>
        <el-form-item label="自动建表">
          <el-switch v-model="migrationConfig.createTable" />
          <span class="text-muted" style="margin-left: 8px">
            按源表结构在目标端建表。可逐表预览并修改建表语句。
          </span>
        </el-form-item>

        <!-- 功能 9 的「预览并修改建表语句」 -->
        <el-form-item v-if="migrationConfig.createTable && migrationConfig.tables.length" label="建表语句">
          <div class="ddl-list">
            <div v-for="t in migrationConfig.tables" :key="t" class="ddl-list__row">
              <span class="text-mono">{{ t }}</span>
              <el-tag v-if="ddlOverrides[t]" type="success" size="small" effect="plain">已自定义</el-tag>
              <el-button link type="primary" @click="onPreviewDdl(t)">预览 / 修改</el-button>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="写入模式">
          <el-radio-group v-model="migrationConfig.writeMode">
            <el-radio value="APPEND">追加</el-radio>
            <el-radio value="OVERWRITE">覆盖</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="写入批次">
          <el-input-number v-model="migrationConfig.batchSize" :min="1" :max="50000" :step="100" />
        </el-form-item>
      </template>

      <!-- ── 工作流(功能 22)──────────────────────────────────────── -->
      <template v-if="isWorkflow">
        <el-divider content-position="left">工作流编排</el-divider>
        <WorkflowGraphEditor
          v-model:nodes="wfNodes"
          v-model:edges="wfEdges"
          :candidates="wfCandidates"
        />
      </template>

      <!-- ── 实时开发(18)/ 离线开发(20)——编译期几乎一样,共用一套表单 -->
      <template v-if="isDev">
        <el-divider content-position="left">作业内容</el-divider>
        <el-form-item label="作业形态">
          <el-radio-group v-model="devConfig.sourceKind">
            <el-radio value="SQL">写 SQL</el-radio>
            <el-radio value="JAR">上传的 JAR</el-radio>
            <el-radio value="PYTHON">Python 包</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="devConfig.sourceKind === 'SQL'">
          <el-form-item v-if="!isStreaming" label="执行数据源">
            <el-select v-model="devConfig.dataSourceId" filterable style="width: 100%">
              <el-option
                v-for="d in dataSources"
                :key="d.id"
                :label="`${d.name}(${d.type})`"
                :value="d.id"
              />
            </el-select>
            <div class="text-muted">SQL 会在这个库上按分号切分后,在一个事务里顺序执行</div>
          </el-form-item>
          <el-form-item label="作业 SQL">
            <el-input
              v-model="devConfig.sql"
              type="textarea"
              :rows="8"
              class="text-mono"
              placeholder="多条语句用分号分隔;字符串里的分号不会被切开"
            />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="制品">
            <el-select v-model="devConfig.artifactId" filterable style="width: 100%">
              <el-option
                v-for="a in artifacts"
                :key="a.id"
                :label="`${a.name}:${a.version}(${a.type})`"
                :value="a.id"
              />
            </el-select>
            <div class="text-muted">在「基础配置 → 文件管理」里上传</div>
          </el-form-item>
          <el-form-item v-if="devConfig.sourceKind === 'JAR'" label="入口类">
            <el-input v-model="devConfig.entryClass" placeholder="com.example.Main" />
            <div class="text-muted">平台不去反编译 JAR 猜 main 方法在哪</div>
          </el-form-item>
          <el-form-item label="程序参数">
            <el-input v-model="devConfig.programArgs" placeholder="选填" />
          </el-form-item>
        </template>

        <el-form-item label="并行度">
          <el-input-number v-model="devConfig.parallelism" :min="1" :max="512" />
        </el-form-item>

        <template v-if="isStreaming">
          <el-form-item label="checkpoint">
            <el-input-number
              v-model="devConfig.checkpointIntervalMs"
              :min="1000"
              :step="10000"
            />
            <span class="text-muted" style="margin-left: 8px">
              毫秒。不配的话重启后会从头开始消费
            </span>
          </el-form-item>
          <el-form-item label="重启策略">
            <el-select v-model="devConfig.restartStrategy" style="width: 220px">
              <el-option label="指数退避" value="EXPONENTIAL" />
              <el-option label="固定间隔" value="FIXED_DELAY" />
              <el-option label="不重启" value="NONE" />
            </el-select>
          </el-form-item>
        </template>
      </template>

      <!-- ── 其余类型:JSON 兜底 ──────────────────────────────────── -->
      <el-form-item v-if="isRaw" label="配置(JSON)">
        <el-input v-model="rawConfigJson" type="textarea" :rows="10" class="text-mono" />
        <div class="text-muted">
          该类型的专用表单尚未落地,后端已支持 —— 按对应编译器的字段填写即可。
        </div>
      </el-form-item>

      <el-divider content-position="left">执行策略</el-divider>
      <el-form-item label="超时(毫秒)">
        <el-input-number v-model="form.timeoutMs" :min="1000" :step="60000" />
        <span class="text-muted" style="margin-left: 8px">
          超过则强制结束并记为超时,不占着执行器不放
        </span>
      </el-form-item>
      <el-form-item label="重试">
        <el-input-number v-model="form.retryMaxAttempts" :min="1" :max="10" />
        <span class="text-muted" style="margin: 0 8px">次(含首次),退避</span>
        <el-input-number v-model="form.retryBackoffSeconds" :min="0" :max="3600" :step="10" />
        <span class="text-muted" style="margin-left: 8px">秒起,每次翻倍</span>
        <div class="text-muted" style="margin-top: 4px; line-height: 1.6">
          连不上、认证失败这类"一行都没写"的失败会照常重试。
          若已经有数据写进目标端才失败,平台<b>不会</b>重投 ——
          重投会把已落盘的行再写一遍。要让重试在任何情况下都生效,把写入模式设为 OVERWRITE。
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
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
    </template>

    <DdlPreviewDrawer ref="ddlDrawer" @confirmed="onDdlConfirmed" />
  </el-dialog>
</template>

<style scoped>
.mappings {
  width: 100%;
}

.mappings__row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.ddl-list {
  width: 100%;
}

.ddl-list__row {
  display: flex;
  gap: 12px;
  align-items: center;
  line-height: 2;
}
</style>
