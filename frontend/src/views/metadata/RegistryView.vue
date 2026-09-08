<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { registryApi } from '@/api/registry'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type { RegistryArtifact, RegistryEdge, RegistryVersion } from '@/types/registry'

/**
 * 数据集与模型注册中心(序号 34 的契约第 3、4 条)。
 *
 * <p>序号 34「高质量数据集制备」<b>已确认独立立项</b>(架构风险 R1)。这个页面
 * 不是那个平台的界面,而是它与本平台之间接口的可视化 —— 让人看得见:
 * 数据集与模型在这里注册,内容存对象存储,字段到医学概念的映射写在这里。
 *
 * <p>所以页面上没有"上传数据集"按钮:内容不经过这个平台。
 */

const loading = ref(false)
const rows = ref<RegistryArtifact[]>([])
const total = ref(0)
const versions = ref<RegistryVersion[]>([])
const edges = ref<RegistryEdge[]>([])

const query = reactive({ page: 1, size: 20, kind: '', keyword: '' })
const detailVisible = ref(false)
const detailArtifact = ref<RegistryArtifact | null>(null)

const registerVisible = ref(false)
const publishVisible = ref(false)
const edgeVisible = ref(false)
const submitting = ref(false)

const registerForm = reactive({ kind: 'DATASET', name: '', description: '' })
const publishForm = reactive({
  contentUri: '', itemCount: 0, sizeBytes: 0, checksumSha256: '', metadataJson: '',
})
const edgeForm = reactive({
  fromId: '', toId: '', toLabel: '', confidence: 1, origin: 'MANUAL',
})

const KINDS = [
  { value: 'DATASET', label: '数据集' },
  { value: 'MODEL', label: '模型' },
  { value: 'ONTOLOGY', label: '本体' },
]

function kindLabel(kind: string): string {
  return KINDS.find((k) => k.value === kind)?.label ?? kind
}

async function load() {
  loading.value = true
  try {
    const page = await registryApi.list({
      page: query.page,
      size: query.size,
      kind: query.kind || undefined,
      keyword: query.keyword || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadEdges() {
  edges.value = await registryApi.edges({ relation: 'MAPS_TO' })
}

onMounted(async () => {
  await Promise.all([load(), loadEdges()])
})

function onFilterChange() {
  query.page = 1
  load()
}

async function openDetail(row: RegistryArtifact) {
  detailArtifact.value = row
  versions.value = await registryApi.versions(row.id)
  detailVisible.value = true
}

function openRegister() {
  Object.assign(registerForm, { kind: 'DATASET', name: '', description: '' })
  registerVisible.value = true
}

async function submitRegister() {
  if (!registerForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  submitting.value = true
  try {
    await registryApi.register({
      kind: registerForm.kind,
      name: registerForm.name.trim(),
      description: registerForm.description || undefined,
    })
    ElMessage.success('已注册。内容在发布版本时以地址给出')
    registerVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function openPublish(row: RegistryArtifact) {
  detailArtifact.value = row
  Object.assign(publishForm, {
    contentUri: '', itemCount: 0, sizeBytes: 0, checksumSha256: '', metadataJson: '',
  })
  publishVisible.value = true
}

async function submitPublish() {
  if (!detailArtifact.value) return
  if (!publishForm.contentUri.trim()) {
    ElMessage.warning('请填写内容地址')
    return
  }
  submitting.value = true
  try {
    await registryApi.publishVersion(detailArtifact.value.id, {
      contentUri: publishForm.contentUri.trim(),
      itemCount: publishForm.itemCount || undefined,
      sizeBytes: publishForm.sizeBytes || undefined,
      checksumSha256: publishForm.checksumSha256 || undefined,
      metadataJson: publishForm.metadataJson || undefined,
    })
    ElMessage.success('新版本已发布。已发布的版本不可修改')
    publishVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: RegistryArtifact) {
  if (!(await confirmDanger(
    `确定删除「${row.name}」吗?版本记录会保留 —— 「这个模型用了哪版数据」在之后仍要能回答。`,
    '删除注册项',
  ))) {
    return
  }
  await registryApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

function openEdge() {
  Object.assign(edgeForm, {
    fromId: '', toId: '', toLabel: '', confidence: 1, origin: 'MANUAL',
  })
  edgeVisible.value = true
}

async function submitEdge() {
  if (!edgeForm.fromId.trim() || !edgeForm.toId.trim()) {
    ElMessage.warning('字段与概念都要填')
    return
  }
  submitting.value = true
  try {
    await registryApi.addEdge({
      fromType: 'COLUMN',
      fromId: edgeForm.fromId.trim(),
      relation: 'MAPS_TO',
      toType: 'CONCEPT',
      toId: edgeForm.toId.trim(),
      toLabel: edgeForm.toLabel || undefined,
      confidence: edgeForm.confidence,
      origin: edgeForm.origin,
    })
    ElMessage.success('映射已建立')
    edgeVisible.value = false
    await loadEdges()
  } finally {
    submitting.value = false
  }
}

async function removeEdge(row: RegistryEdge) {
  if (!(await confirmDanger(`确定删除这条语义映射吗?`, '删除映射'))) {
    return
  }
  await registryApi.removeEdge(row.id)
  await loadEdges()
}

function humanSize(bytes: number | null): string {
  if (!bytes) return '—'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}
</script>

<template>
  <div class="page-container">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
      <template #title>这里只登记标识与版本,内容存对象存储</template>
      影像、标注文件与模型权重不经过本平台 —— 它只记「有这么一个数据集、
      它有几版、每版在哪」。这样平台的血缘与影响分析才看得见它们,
      而不必再建第二个注册中心。
    </el-alert>

    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-select
            v-model="query.kind"
            placeholder="全部种类"
            clearable
            style="width: 130px"
            @change="onFilterChange"
          >
            <el-option v-for="k in KINDS" :key="k.value" :label="k.label" :value="k.value" />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="搜索名称"
            clearable
            style="width: 200px"
            @keyup.enter="onFilterChange"
            @clear="onFilterChange"
          />
          <el-button @click="onFilterChange">查询</el-button>
        </div>
        <el-button
          v-permission="'metadata:registry:manage'"
          type="primary"
          @click="openRegister"
        >
          注册
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" @click="openDetail(row as RegistryArtifact)">
              {{ (row as RegistryArtifact).name }}
            </el-link>
            <div v-if="(row as RegistryArtifact).description" class="text-muted">
              {{ (row as RegistryArtifact).description }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="种类" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ kindLabel((row as RegistryArtifact).kind) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="版本" width="110">
          <template #default="{ row }">
            <!-- 0 版要显式说出来:它与"有一版"完全不同 -->
            <span v-if="!(row as RegistryArtifact).latestVersion" class="text-muted">
              尚无版本
            </span>
            <span v-else>v{{ (row as RegistryArtifact).latestVersion }}</span>
          </template>
        </el-table-column>

        <el-table-column label="产出自" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="(row as RegistryArtifact).producedByExecutionId" class="text-mono">
              {{ (row as RegistryArtifact).producedByExecutionId }}
            </span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="注册时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime((row as RegistryArtifact).createdAt) }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'metadata:registry:manage'"
              link
              type="primary"
              @click="openPublish(row as RegistryArtifact)"
            >
              发布版本
            </el-button>
            <el-button
              v-permission="'metadata:registry:manage'"
              link
              type="danger"
              @click="onDelete(row as RegistryArtifact)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">
            还没有注册项。数据集与模型由训练任务产出后注册进来。
          </div>
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

    <!-- ── 语义映射(契约第 4 条)──────────────────────────────────── -->
    <el-card shadow="never" style="margin-top: 12px">
      <template #header>
        <div class="page-toolbar" style="margin: 0">
          <span>语义映射 —— 字段到医学概念</span>
          <el-button
            v-permission="'metadata:semantic:manage'"
            size="small"
            type="primary"
            @click="openEdge"
          >
            新增映射
          </el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>概念的定义归 Intelligence,平台只记这条边</template>
        把字段挂到医学概念上之后,血缘与影响分析就能回答「改这个字段会影响哪些
        用到糖尿病概念的模型」。概念用本体 IRI 标识,平台不拥有本体定义。
      </el-alert>

      <el-table :data="edges" size="small">
        <el-table-column label="字段" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-mono">{{ (row as RegistryEdge).fromId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="80" align="center">
          <template #default>
            <span class="text-muted">映射到</span>
          </template>
        </el-table-column>
        <el-table-column label="概念" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ (row as RegistryEdge).toLabel ?? '—' }}</div>
            <div class="text-muted text-mono">{{ (row as RegistryEdge).toId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="140">
          <template #default="{ row }">
            <!-- 自动抽取的要能一眼分出来:它们需要人工复核 -->
            <el-tag
              :type="(row as RegistryEdge).origin === 'AUTO' ? 'warning' : 'success'"
              size="small"
            >
              {{ (row as RegistryEdge).origin === 'AUTO' ? '自动抽取' : '人工确认' }}
            </el-tag>
            <span v-if="(row as RegistryEdge).origin === 'AUTO'" class="text-muted">
              {{ ((row as RegistryEdge).confidence * 100).toFixed(0) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button
              v-permission="'metadata:semantic:manage'"
              link
              type="danger"
              size="small"
              @click="removeEdge(row as RegistryEdge)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="text-muted" style="padding: 16px">还没有语义映射</div>
        </template>
      </el-table>
    </el-card>

    <!-- ── 对话框 ──────────────────────────────────────────────────── -->
    <el-dialog v-model="registerVisible" title="注册数据集 / 模型" width="520px" destroy-on-close>
      <el-form :model="registerForm" label-width="90px">
        <el-form-item label="种类">
          <el-radio-group v-model="registerForm.kind">
            <el-radio v-for="k in KINDS" :key="k.value" :value="k.value">{{ k.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="registerForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="registerForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRegister">注册</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="publishVisible" title="发布新版本" width="560px" destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>已发布的版本不可修改</template>
        要改内容就再发一版 —— 一个可变的数据集版本意味着"上个月那次评测"
        今天再跑可能是另一个结果。
      </el-alert>
      <el-form :model="publishForm" label-width="110px">
        <el-form-item label="内容地址" required>
          <el-input v-model="publishForm.contentUri" placeholder="s3://bucket/path/ 或 hdfs://..." />
          <div class="text-muted">只登记地址,内容不上传到本平台</div>
        </el-form-item>
        <el-form-item label="样本数">
          <el-input-number v-model="publishForm.itemCount" :min="0" />
        </el-form-item>
        <el-form-item label="大小(字节)">
          <el-input-number v-model="publishForm.sizeBytes" :min="0" :step="1048576" />
        </el-form-item>
        <el-form-item label="内容摘要">
          <el-input v-model="publishForm.checksumSha256" placeholder="SHA-256,选填" />
        </el-form-item>
        <el-form-item label="元信息">
          <el-input
            v-model="publishForm.metadataJson"
            type="textarea"
            :rows="3"
            class="text-mono"
            placeholder='{"modality": "DX", "metrics": {"auc": 0.94}}'
          />
          <div class="text-muted">自由形状的 JSON,平台不解释它</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitPublish">发布</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="edgeVisible" title="新增语义映射" width="560px" destroy-on-close>
      <el-form :model="edgeForm" label-width="110px">
        <el-form-item label="字段" required>
          <el-input
            v-model="edgeForm.fromId"
            placeholder="数据源ID:库.模式.表.字段"
            class="text-mono"
          />
          <div class="text-muted">
            用可拼可拆的形式,而不是指向目录快照的外键 —— 快照会随刷新重建,
            而这条映射不该因此丢掉
          </div>
        </el-form-item>
        <el-form-item label="概念 IRI" required>
          <el-input
            v-model="edgeForm.toId"
            placeholder="http://snomed.info/id/73211009"
            class="text-mono"
          />
        </el-form-item>
        <el-form-item label="概念名称">
          <el-input v-model="edgeForm.toLabel" placeholder="如:糖尿病" />
        </el-form-item>
        <el-form-item label="来源">
          <el-radio-group v-model="edgeForm.origin">
            <el-radio value="MANUAL">人工确认</el-radio>
            <el-radio value="AUTO">自动抽取</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="edgeForm.origin === 'AUTO'" label="置信度">
          <el-input-number v-model="edgeForm.confidence" :min="0" :max="1" :step="0.05" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="edgeVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdge">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="版本历史" width="720px">
      <el-table :data="versions" size="small">
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ (row as RegistryVersion).version }}</template>
        </el-table-column>
        <el-table-column label="内容地址" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-mono">{{ (row as RegistryVersion).contentUri }}</span>
          </template>
        </el-table-column>
        <el-table-column label="样本数" width="100" align="right">
          <template #default="{ row }">{{ (row as RegistryVersion).itemCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="大小" width="100" align="right">
          <template #default="{ row }">{{ humanSize((row as RegistryVersion).sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="派生自" width="120">
          <template #default="{ row }">
            <span v-if="(row as RegistryVersion).derivedFromVersionId" class="text-muted">
              上一版
            </span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime((row as RegistryVersion).createdAt) }}
          </template>
        </el-table-column>
        <template #empty>
          <div class="text-muted" style="padding: 16px">还没有发布过版本</div>
        </template>
      </el-table>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>
