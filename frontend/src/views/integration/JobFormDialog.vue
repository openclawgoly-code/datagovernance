<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { jobApi } from '@/api/job'
import { dataSourceApi } from '@/api/datasource'
import DdlPreviewDrawer from './DdlPreviewDrawer.vue'
import type { DataSource } from '@/types/datasource'
import type { JobDefinition, JobType, JobTypeInfo, TaskCatalogNode } from '@/types/job'

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

const isSync = computed(() => form.jobType === 'OFFLINE_SYNC')
const isMigration = computed(() => form.jobType === 'DB_MIGRATION')
const isRaw = computed(() => !!form.jobType && !isSync.value && !isMigration.value)

const selectedType = computed(() => types.value.find((t) => t.type === form.jobType) ?? null)

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  jobType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
}))

async function open(
  row: JobDefinition | null,
  typeList: JobTypeInfo[],
  catalogNodes: TaskCatalogNode[] = [],
) {
  types.value = typeList
  catalogs.value = catalogNodes
  editingId.value = row?.id ?? null
  ddlOverrides.value = {}

  Object.assign(form, {
    name: row?.name ?? '',
    jobType: row?.jobType ?? '',
    description: row?.description ?? '',
    catalogId: row?.catalogId ?? null,
    timeoutMs: row?.timeoutMs ?? 7200000,
    retryMaxAttempts: row?.retryMaxAttempts ?? 1,
    retryBackoffSeconds: row?.retryBackoffSeconds ?? 30,
  })

  const config = (row?.config ?? {}) as Record<string, unknown>
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

function onTypeChange() {
  mappingRows.value = []
  rawConfigJson.value = '{}'
  ddlOverrides.value = {}
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
