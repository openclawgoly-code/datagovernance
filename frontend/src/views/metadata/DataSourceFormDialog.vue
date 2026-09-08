<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { dataSourceApi } from '@/api/datasource'
import { CREDENTIAL_AUTH_TYPE_LABELS, type CredentialAuthType } from '@/types/credential'
import type {
  ConnectivityResult,
  DataSource,
  DataSourceCatalogNode,
  DataSourceForm,
  DataSourceTypeInfo,
} from '@/types/datasource'

const props = defineProps<{
  types: DataSourceTypeInfo[]
  catalogs: DataSourceCatalogNode[]
}>()
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const submitting = ref(false)
const testing = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const testResult = ref<ConnectivityResult | null>(null)

/** 扩展参数用键值对数组编辑,提交时再转成对象 —— 对象在表单里没法增删行 */
const propertyRows = ref<{ key: string; value: string }[]>([])

const form = reactive<DataSourceForm & { authType: CredentialAuthType; secret: string }>({
  name: '',
  type: '',
  description: '',
  catalogId: undefined,
  host: '',
  port: undefined,
  databaseName: '',
  username: '',
  baseUrl: '',
  jdbcUrlOverride: '',
  connectTimeoutMs: 10000,
  readTimeoutMs: 30000,
  authType: 'PASSWORD',
  secret: '',
})

const selectedType = computed(() => props.types.find((t) => t.type === form.type) ?? null)
const family = computed(() => selectedType.value?.family ?? null)

/**
 * 表单结构完全由 family / capabilities 决定,<b>不写 if (type === 'MYSQL')</b>。
 * 每加一种数据源就要改前端,是架构风险 R7 的传导路径。
 */
const showJdbcFields = computed(() => family.value === 'RELATIONAL' || family.value === 'MPP')
const showFileFields = computed(() => family.value === 'FILE')
const showHttpFields = computed(() => family.value === 'HTTP')

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择数据源类型', trigger: 'change' }],
  host: showJdbcFields.value || showFileFields.value
    ? [{ required: !form.jdbcUrlOverride, message: '请输入主机地址', trigger: 'blur' }]
    : [],
  baseUrl: showHttpFields.value
    ? [{ required: true, message: '请输入接口地址', trigger: 'blur' }]
    : [],
}))

function open(row: DataSource | null) {
  editingId.value = row?.id ?? null
  testResult.value = null
  propertyRows.value = row?.properties
    ? Object.entries(row.properties).map(([key, value]) => ({ key, value }))
    : []

  Object.assign(form, {
    name: row?.name ?? '',
    type: row?.type ?? '',
    description: row?.description ?? '',
    catalogId: row?.catalogId ?? undefined,
    host: row?.host ?? '',
    port: row?.port ?? undefined,
    databaseName: row?.databaseName ?? '',
    username: row?.username ?? '',
    baseUrl: row?.baseUrl ?? '',
    jdbcUrlOverride: row?.jdbcUrlOverride ?? '',
    connectTimeoutMs: row?.connectTimeoutMs ?? 10000,
    readTimeoutMs: row?.readTimeoutMs ?? 30000,
    authType: 'PASSWORD',
    // 编辑时口令框留空 = 保持原口令不变,而不是改成空口令
    secret: '',
  })
  visible.value = true
}

/** 切换类型时自动填该类型的默认端口,省掉用户去查"Doris 的 FE 端口是多少" */
function onTypeChange(type: string) {
  const info = props.types.find((t) => t.type === type)
  if (info && !editingId.value) {
    form.port = info.defaultPort
    form.authType = info.family === 'HTTP' ? 'TOKEN' : 'PASSWORD'
  }
  testResult.value = null
}

function buildPayload(): DataSourceForm {
  const properties: Record<string, string> = {}
  for (const row of propertyRows.value) {
    if (row.key.trim()) {
      properties[row.key.trim()] = row.value
    }
  }

  const payload: DataSourceForm = {
    name: form.name,
    type: form.type,
    description: form.description || undefined,
    catalogId: form.catalogId || undefined,
    host: form.host || undefined,
    port: form.port,
    databaseName: form.databaseName || undefined,
    username: form.username || undefined,
    baseUrl: form.baseUrl || undefined,
    jdbcUrlOverride: form.jdbcUrlOverride || undefined,
    properties: Object.keys(properties).length > 0 ? properties : undefined,
    connectTimeoutMs: form.connectTimeoutMs,
    readTimeoutMs: form.readTimeoutMs,
  }

  // 只有真填了口令才带 inlineSecret。编辑时留空表示沿用已保存的凭据 ——
  // 后端据此决定是"更新凭据"还是"保持不变"。
  if (form.secret) {
    payload.inlineSecret = {
      authType: form.authType,
      username: form.username || undefined,
      secret: form.secret,
    }
  }
  return payload
}

async function onTest() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  testing.value = true
  testResult.value = null
  try {
    // 保存前试连:不落库、不改状态。让用户在填错时立刻知道,
    // 而不是保存完再回列表点一次测试。
    testResult.value = await dataSourceApi.testTransient(buildPayload())
  } finally {
    testing.value = false
  }
}

async function onSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload = buildPayload()
    if (editingId.value) {
      await dataSourceApi.update(editingId.value, payload)
      ElMessage.success('已保存。若修改了连接参数,状态已重置为待验证,请重新测试')
    } else {
      await dataSourceApi.create(payload)
      ElMessage.success('已创建,状态为待验证。请测试连通性后方可浏览结构')
    }
    visible.value = false
    emit('saved')
  } finally {
    submitting.value = false
  }
}

/** 目录下拉用扁平化的树,缩进靠前缀表达 —— el-select 不支持树形选项 */
const flatCatalogs = computed(() => {
  const out: { id: string; label: string }[] = []
  const walk = (nodes: DataSourceCatalogNode[], depth: number) => {
    for (const node of nodes) {
      out.push({ id: node.id, label: `${'　'.repeat(depth)}${node.name}` })
      walk(node.children ?? [], depth + 1)
    }
  }
  walk(props.catalogs, 0)
  return out
})

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editingId ? '编辑数据源' : '新建数据源'"
    width="720px"
    destroy-on-close
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" placeholder="同一空间内唯一" />
      </el-form-item>

      <el-form-item label="类型" prop="type">
        <el-select
          v-model="form.type"
          placeholder="请选择"
          :disabled="!!editingId"
          style="width: 100%"
          @change="onTypeChange"
        >
          <el-option v-for="t in types" :key="t.type" :label="t.displayName" :value="t.type">
            <span>{{ t.displayName }}</span>
            <span class="text-muted" style="float: right">{{ t.family }}</span>
          </el-option>
        </el-select>
        <div v-if="editingId" class="text-muted">
          类型不可变更 —— 换类型等于换了一个东西,连接语义与类型映射全变。如需更换请新建。
        </div>
      </el-form-item>

      <el-form-item label="所属目录">
        <el-select v-model="form.catalogId" placeholder="未分类" clearable style="width: 100%">
          <el-option v-for="c in flatCatalogs" :key="c.id" :label="c.label" :value="c.id" />
        </el-select>
      </el-form-item>

      <!-- JDBC 类(关系型 / MPP) -->
      <template v-if="showJdbcFields">
        <el-form-item label="主机" prop="host">
          <el-input v-model="form.host" placeholder="IP 或域名" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
          <span class="text-muted" style="margin-left: 8px">
            默认 {{ selectedType?.defaultPort }}
          </span>
        </el-form-item>
        <el-form-item label="库名">
          <el-input v-model="form.databaseName" placeholder="Oracle / 达梦填服务名或 SID" />
        </el-form-item>
        <el-form-item label="直填 JDBC URL">
          <el-input v-model="form.jdbcUrlOverride" placeholder="留空则按主机端口自动拼装" />
          <div class="text-muted">填写后优先于主机/端口/库名,用于 failover、Kerberos 等特殊连接串</div>
        </el-form-item>
      </template>

      <!-- 文件类(FTP / SFTP) -->
      <template v-if="showFileFields">
        <el-form-item label="主机" prop="host">
          <el-input v-model="form.host" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
        </el-form-item>
        <el-form-item label="根目录">
          <el-input v-model="form.databaseName" placeholder="如 /data/exchange" />
        </el-form-item>
      </template>

      <!-- 接口类(RestAPI) -->
      <template v-if="showHttpFields">
        <el-form-item label="接口地址" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://example.com/api" />
        </el-form-item>
      </template>

      <!-- 认证。口令提交后由后端存入 Platform 的凭据托管,数据源只保存引用。 -->
      <template v-if="form.type">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="off" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-radio-group v-model="form.authType">
            <el-radio
              v-for="(label, value) in CREDENTIAL_AUTH_TYPE_LABELS"
              :key="value"
              :value="value"
            >
              {{ label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.authType !== 'NONE'" label="口令 / Token">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editingId ? '留空表示不修改' : ''"
          />
          <div class="text-muted">
            口令由平台的凭据托管统一加密保存,数据源本身只持有一个不可解密的引用;
            任何查询接口都不会回显它。
          </div>
        </el-form-item>
      </template>

      <el-form-item label="扩展参数">
        <div class="props">
          <div v-for="(row, index) in propertyRows" :key="index" class="props__row">
            <el-input v-model="row.key" placeholder="参数名" style="width: 200px" />
            <el-input v-model="row.value" placeholder="参数值" style="width: 240px" />
            <el-button link type="danger" @click="propertyRows.splice(index, 1)">删除</el-button>
          </div>
          <el-button link type="primary" @click="propertyRows.push({ key: '', value: '' })">
            + 添加参数
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="超时(毫秒)">
        <el-input-number v-model="form.connectTimeoutMs" :min="1000" :max="300000" :step="1000" />
        <span class="text-muted" style="margin: 0 8px">连接</span>
        <el-input-number v-model="form.readTimeoutMs" :min="1000" :max="300000" :step="1000" />
        <span class="text-muted" style="margin-left: 8px">读取</span>
      </el-form-item>

      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>

      <!-- 试连结果就地展示,不用弹窗打断填写 -->
      <el-alert
        v-if="testResult"
        :type="testResult.success ? 'success' : 'error'"
        :closable="false"
        show-icon
      >
        <div>{{ testResult.message }}</div>
        <div v-if="testResult.success" class="text-muted">
          服务端版本:{{ testResult.serverVersion ?? '未知' }} · 耗时 {{ testResult.latencyMillis }} ms
        </div>
        <div v-else class="text-muted">
          错误分类:{{ testResult.errorCode }}<span v-if="testResult.detail"> · {{ testResult.detail }}</span>
        </div>
      </el-alert>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button :loading="testing" @click="onTest">测试连接</el-button>
      <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.props {
  width: 100%;
}

.props__row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
</style>
