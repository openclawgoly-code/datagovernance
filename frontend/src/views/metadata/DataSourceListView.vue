<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dataSourceApi, dataSourceCatalogApi } from '@/api/datasource'
import { statusMeta } from '@/utils/datasource-status'
import DataSourceFormDialog from './DataSourceFormDialog.vue'
import CatalogBrowserDrawer from './CatalogBrowserDrawer.vue'
import ProbeScheduleDialog from './ProbeScheduleDialog.vue'
import SqlConsoleDrawer from './SqlConsoleDrawer.vue'
import type {
  ConnectivityResult,
  DataSource,
  DataSourceCatalogNode,
  DataSourceCatalogTree,
  DataSourceTypeInfo,
} from '@/types/datasource'

const loading = ref(false)
const rows = ref<DataSource[]>([])
const total = ref(0)
const types = ref<DataSourceTypeInfo[]>([])
const catalogTree = ref<DataSourceCatalogTree>({ nodes: [], uncategorizedCount: 0 })

const query = reactive({ page: 1, size: 20, type: '', keyword: '' })
/** 左树选中的目录;'' 表示全部,'__none__' 表示未分类 */
const selectedCatalog = ref('')

const formDialog = ref<InstanceType<typeof DataSourceFormDialog>>()
const browserDrawer = ref<InstanceType<typeof CatalogBrowserDrawer>>()
const probeDialog = ref<InstanceType<typeof ProbeScheduleDialog>>()
const sqlConsole = ref<InstanceType<typeof SqlConsoleDrawer>>()

/** 前端按目录过滤而不是让后端加参数:P1 单空间数据源数量有限,
 *  一次取回再过滤比给列表接口加一个只有这里用的参数更简单。 */
const visibleRows = computed(() => {
  if (selectedCatalog.value === '') return rows.value
  if (selectedCatalog.value === '__none__') return rows.value.filter((r) => !r.catalogId)
  return rows.value.filter((r) => r.catalogId === selectedCatalog.value)
})

const catalogTreeData = computed(() => [
  { id: '', name: `全部(${total.value})`, children: [] as DataSourceCatalogNode[] },
  {
    id: '__none__',
    name: `未分类(${catalogTree.value.uncategorizedCount})`,
    children: [] as DataSourceCatalogNode[],
  },
  ...catalogTree.value.nodes,
])

async function load() {
  loading.value = true
  try {
    const page = await dataSourceApi.list({
      page: query.page,
      size: query.size,
      type: query.type || undefined,
      keyword: query.keyword || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadCatalogs() {
  try {
    catalogTree.value = await dataSourceCatalogApi.tree()
  } catch {
    // 没有目录管理权限的用户仍应能看数据源列表,失败就退化成"只有全部"
  }
}

onMounted(async () => {
  types.value = await dataSourceApi.types()
  await Promise.all([load(), loadCatalogs()])
})

function onCreate() {
  formDialog.value?.open(null)
}

function onEdit(row: DataSource) {
  formDialog.value?.open(row)
}

function onBrowse(row: DataSource) {
  browserDrawer.value?.open(row)
}

function onProbe(row: DataSource) {
  probeDialog.value?.open(row)
}

function onQuery(row: DataSource) {
  sqlConsole.value?.open(row)
}

/** 连通性测试。结果用长驻通知而不是一闪而过的 toast —— 用户往往需要
 *  把失败原因抄给 DBA,一秒就消失的提示帮不上忙。 */
async function onTest(row: DataSource) {
  const result: ConnectivityResult = await dataSourceApi.test(row.id)
  await load()

  if (result.success) {
    ElMessageBox.alert(
      `${result.message}\n服务端版本:${result.serverVersion ?? '未知'}\n耗时:${result.latencyMillis} ms`,
      '连接成功',
      { type: 'success', confirmButtonText: '知道了' },
    ).catch(() => {})
  } else {
    ElMessageBox.alert(
      `${result.message}\n\n错误分类:${result.errorCode}\n技术详情:${result.detail ?? '无'}`,
      '连接失败',
      { type: 'error', confirmButtonText: '知道了' },
    ).catch(() => {})
  }
}

async function onToggleEnabled(row: DataSource) {
  if (row.status === 'DISABLED') {
    await dataSourceApi.enable(row.id)
    ElMessage.success('已启用。停用期间目标端可能已变化,请重新测试连通性')
  } else {
    await ElMessageBox.confirm(
      `停用「${row.name}」后,它将不能被新任务引用。确定吗?`,
      '停用数据源',
      { type: 'warning' },
    )
    await dataSourceApi.disable(row.id)
    ElMessage.success('已停用')
  }
  await load()
}

async function onDelete(row: DataSource) {
  await ElMessageBox.confirm(
    `确定删除「${row.name}」吗?若它已被任务引用,请改用停用。`,
    '删除数据源',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
  )
  await dataSourceApi.remove(row.id)
  ElMessage.success('已删除')
  await Promise.all([load(), loadCatalogs()])
}

function onCatalogClick(node: { id: string }) {
  selectedCatalog.value = node.id
}

function onPageChange(page: number) {
  query.page = page
  load()
}

function onPageSizeChange(size: number) {
  query.size = size
  query.page = 1
  load()
}

async function onSaved() {
  await Promise.all([load(), loadCatalogs()])
}

async function onCreateCatalog() {
  try {
    const { value } = await ElMessageBox.prompt('请输入目录名称', '新建数据源目录', {
      inputPattern: /\S+/,
      inputErrorMessage: '目录名称不能为空',
    })
    const parentId =
      selectedCatalog.value && !selectedCatalog.value.startsWith('__') ? selectedCatalog.value : null
    await dataSourceCatalogApi.create({ parentId, name: value })
    ElMessage.success('目录已创建')
    await loadCatalogs()
  } catch {
    /* 取消 */
  }
}

async function onDeleteCatalog() {
  const id = selectedCatalog.value
  if (!id || id.startsWith('__') || id === '') {
    ElMessage.warning('请先选中一个目录')
    return
  }
  try {
    await ElMessageBox.confirm('确定删除该目录吗?非空目录无法删除。', '删除目录', {
      type: 'warning',
    })
  } catch {
    return
  }
  await dataSourceCatalogApi.remove(id)
  ElMessage.success('目录已删除')
  selectedCatalog.value = ''
  await loadCatalogs()
}
</script>

<template>
  <div class="page-container">
    <el-row :gutter="12">
      <!-- 左:数据源目录(功能5)。人工维护的组织结构,不是库表结构。 -->
      <el-col :span="5">
        <el-card shadow="never" class="catalog-panel">
          <template #header>
            <div class="catalog-panel__header">
              <span>数据源目录</span>
              <span>
                <el-button
                  v-permission="'metadata:catalog:manage'"
                  link
                  type="primary"
                  @click="onCreateCatalog"
                >
                  新建
                </el-button>
                <el-button
                  v-permission="'metadata:catalog:manage'"
                  link
                  type="danger"
                  @click="onDeleteCatalog"
                >
                  删除
                </el-button>
              </span>
            </div>
          </template>

          <el-tree
            :data="catalogTreeData"
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            :current-node-key="selectedCatalog"
            highlight-current
            default-expand-all
            @node-click="onCatalogClick"
          >
            <template #default="{ data }">
              <span>
                {{ data.name }}
                <span v-if="data.dataSourceCount != null" class="text-muted">
                  ({{ data.dataSourceCount }})
                </span>
              </span>
            </template>
          </el-tree>
        </el-card>
      </el-col>

      <!-- 右:数据源列表 -->
      <el-col :span="19">
        <el-card shadow="never">
          <div class="page-toolbar">
            <div class="page-toolbar__filters">
              <el-select
                v-model="query.type"
                placeholder="全部类型"
                clearable
                style="width: 180px"
                @change="load"
              >
                <el-option
                  v-for="t in types"
                  :key="t.type"
                  :label="t.displayName"
                  :value="t.type"
                />
              </el-select>
              <el-input
                v-model="query.keyword"
                placeholder="按名称搜索"
                clearable
                style="width: 200px"
                @keyup.enter="load"
                @clear="load"
              />
              <el-button @click="load">查询</el-button>
            </div>

            <el-button
              v-permission="'metadata:datasource:create'"
              type="primary"
              @click="onCreate"
            >
              新建数据源
            </el-button>
          </div>

          <el-table :data="visibleRows" v-loading="loading" row-key="id">
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="typeDisplayName" label="类型" width="120" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tooltip :content="statusMeta(row.status).hint" placement="top">
                  <el-tag :type="statusMeta(row.status).type" size="small">
                    {{ statusMeta(row.status).label }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="连接" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="text-mono">
                  {{ row.baseUrl || `${row.host ?? '-'}:${row.port ?? '-'}/${row.databaseName ?? ''}` }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="周期检查" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.probeEnabled" type="success" size="small" effect="plain">
                  每 {{ row.probeIntervalMinutes }} 分钟
                </el-tag>
                <span v-else class="text-muted">未开启</span>
              </template>
            </el-table-column>
            <el-table-column label="最近测试" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.lastTestAt" class="text-muted">
                  {{ row.lastTestSuccess ? '成功' : '失败' }} ·
                  {{ row.lastTestMessage }}
                </span>
                <span v-else class="text-muted">从未测试</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="380" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-permission="'metadata:datasource:test'"
                  link
                  type="primary"
                  @click="onTest(row as DataSource)"
                >
                  测试
                </el-button>
                <el-button
                  v-permission="'metadata:datasource:browse'"
                  link
                  type="primary"
                  :disabled="row.status !== 'AVAILABLE'"
                  :title="row.status !== 'AVAILABLE' ? '需先通过连通性测试' : ''"
                  @click="onBrowse(row as DataSource)"
                >
                  结构
                </el-button>
                <el-button
                  v-permission="'metadata:datasource:query'"
                  link
                  type="primary"
                  :disabled="row.status !== 'AVAILABLE' || (row.family !== 'RELATIONAL' && row.family !== 'MPP')"
                  :title="row.family !== 'RELATIONAL' && row.family !== 'MPP' ? '该类型没有 SQL 概念' : ''"
                  @click="onQuery(row as DataSource)"
                >
                  查询
                </el-button>
                <el-button
                  v-permission="'metadata:datasource:update'"
                  link
                  type="primary"
                  @click="onProbe(row as DataSource)"
                >
                  周期
                </el-button>
                <el-button
                  v-permission="'metadata:datasource:update'"
                  link
                  type="primary"
                  @click="onEdit(row as DataSource)"
                >
                  编辑
                </el-button>
                <el-button
                  v-permission="'metadata:datasource:update'"
                  link
                  type="warning"
                  @click="onToggleEnabled(row as DataSource)"
                >
                  {{ row.status === 'DISABLED' ? '启用' : '停用' }}
                </el-button>
                <el-button
                  v-permission="'metadata:datasource:delete'"
                  link
                  type="danger"
                  @click="onDelete(row as DataSource)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="pager"
            layout="total, sizes, prev, pager, next"
            :total="total"
            :current-page="query.page"
            :page-size="query.size"
            :page-sizes="[10, 20, 50, 100]"
            @update:current-page="onPageChange"
            @update:page-size="onPageSizeChange"
          />
        </el-card>
      </el-col>
    </el-row>

    <DataSourceFormDialog
      ref="formDialog"
      :types="types"
      :catalogs="catalogTree.nodes"
      @saved="onSaved"
    />
    <CatalogBrowserDrawer ref="browserDrawer" />
    <ProbeScheduleDialog ref="probeDialog" @saved="load" />
    <SqlConsoleDrawer ref="sqlConsole" />
  </div>
</template>

<style scoped>
.catalog-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
