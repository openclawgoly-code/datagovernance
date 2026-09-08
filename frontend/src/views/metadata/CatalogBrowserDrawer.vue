<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dataSourceApi } from '@/api/datasource'
import type { ColumnInfo } from '@/types/catalog'
import type { DataSource } from '@/types/datasource'

/**
 * 库表结构浏览(功能 7)。
 *
 * <b>树有几级完全由 capabilities 决定,不看具体类型</b>:
 *   hasSchemaLevel=true   库 → 模式 → 表 → 列   (PostgreSQL / Oracle / SQLServer / 达梦)
 *   hasSchemaLevel=false  库 → 表 → 列          (MySQL / Doris / StarRocks)
 *   canBrowseFiles=true   目录 → 文件            (FTP / SFTP)
 * 加一种新引擎时这里一行都不用改 —— 前提是后端如实声明了能力。
 */

interface TreeNode {
  id: string
  label: string
  kind: 'database' | 'schema' | 'table' | 'file' | 'dir'
  database?: string
  schema?: string
  table?: string
  path?: string
  isLeaf?: boolean
}

const visible = ref(false)
const current = ref<DataSource | null>(null)
const columns = ref<ColumnInfo[]>([])
const columnsTitle = ref('')
const loadingColumns = ref(false)
/** 每次打开重建一次树:换数据源后旧节点必须全部丢弃 */
const treeKey = ref(0)

function open(row: DataSource) {
  current.value = row
  columns.value = []
  columnsTitle.value = ''
  treeKey.value += 1
  visible.value = true
}

const capabilities = () => {
  // 后端 DataSourceView 不带 capabilities,由类型信息提供;
  // 这里退化用 family 判断,与 capabilities 的语义一致(family 是它的来源)
  const family = current.value?.family
  return {
    fileBased: family === 'FILE',
    hasSchemaLevel: family === 'RELATIONAL' && current.value?.type !== 'MYSQL',
    browsable: family === 'RELATIONAL' || family === 'MPP',
  }
}

/**
 * el-tree 的懒加载。resolve 必须被调用,否则节点会永远转圈。
 *
 * 形参类型放宽成 Element Plus 的 `LoadFunction` 能接受的形状:它内部把 node
 * 声明为自己的 Node 类,而我们只用到 level 与 data 两个字段。与其为了对齐
 * 一个内部类型去 import 它(那会让组件耦合到 Element Plus 的实现细节),
 * 不如在这里收窄一次 —— 下面立刻把 data 断言回自己的 TreeNode。
 */
async function loadNode(
  rawNode: { level: number; data: unknown },
  resolve: (data: TreeNode[]) => void,
) {
  const node = { level: rawNode.level, data: rawNode.data as TreeNode }
  const ds = current.value
  if (!ds) {
    resolve([])
    return
  }
  const caps = capabilities()

  try {
    if (caps.fileBased) {
      const path = node.level === 0 ? ds.databaseName || '/' : node.data.path
      const page = await dataSourceApi.browseCatalog(ds.id, { path: path ?? '/' })
      resolve(
        (page.files ?? []).map((f) => ({
          id: f.path,
          label: f.name,
          kind: f.directory ? 'dir' : 'file',
          path: f.path,
          isLeaf: !f.directory,
        })),
      )
      return
    }

    // 第 0 层:库
    if (node.level === 0) {
      const page = await dataSourceApi.browseCatalog(ds.id, {})
      resolve(
        (page.databases ?? []).map((d) => ({
          id: `db:${d.name}`,
          label: d.name,
          kind: 'database',
          database: d.name,
        })),
      )
      return
    }

    const data = node.data
    // 库 → 模式(三级树)或 表(两级树)
    if (data.kind === 'database') {
      const page = await dataSourceApi.browseCatalog(ds.id, { database: data.database })
      if (caps.hasSchemaLevel && (page.schemas?.length ?? 0) > 0) {
        resolve(
          page.schemas.map((s) => ({
            id: `schema:${data.database}.${s.name}`,
            label: s.name,
            kind: 'schema',
            database: data.database,
            schema: s.name,
          })),
        )
      } else {
        resolve(
          (page.tables ?? []).map((t) => ({
            id: `table:${data.database}.${t.name}`,
            label: `${t.name}${t.kind === 'VIEW' ? '(视图)' : ''}`,
            kind: 'table',
            database: data.database,
            table: t.name,
          })),
        )
      }
      return
    }

    // 模式 → 表
    if (data.kind === 'schema') {
      const page = await dataSourceApi.browseCatalog(ds.id, {
        database: data.database,
        schema: data.schema,
      })
      resolve(
        (page.tables ?? []).map((t) => ({
          id: `table:${data.database}.${data.schema}.${t.name}`,
          label: `${t.name}${t.kind === 'VIEW' ? '(视图)' : ''}`,
          kind: 'table',
          database: data.database,
          schema: data.schema,
          table: t.name,
        })),
      )
      return
    }

    resolve([])
  } catch {
    // 拦截器已经提示过错误,这里只要保证节点不卡在加载中
    resolve([])
  }
}

/** 点到表就把字段列在右侧,不再往树里塞第四层 —— 字段几十上百个,树装不下 */
async function onNodeClick(data: TreeNode) {
  const ds = current.value
  if (!ds || data.kind !== 'table') {
    return
  }
  loadingColumns.value = true
  columnsTitle.value = [data.database, data.schema, data.table].filter(Boolean).join('.')
  try {
    const page = await dataSourceApi.browseCatalog(ds.id, {
      database: data.database,
      schema: data.schema,
      table: data.table,
    })
    columns.value = page.columns ?? []
  } finally {
    loadingColumns.value = false
  }
}

async function onRefresh() {
  const ds = current.value
  if (!ds) return
  // refresh=true 跳过快照强制重新探测目标库
  await dataSourceApi.browseCatalog(ds.id, {}, true)
  treeKey.value += 1
  columns.value = []
  columnsTitle.value = ''
  ElMessage.success('已重新探测目标端结构')
}

defineExpose({ open })
</script>

<template>
  <el-drawer v-model="visible" size="72%" :title="`结构浏览 · ${current?.name ?? ''}`">
    <template #header>
      <div class="drawer-header">
        <span>结构浏览 · {{ current?.name }}</span>
        <el-button size="small" @click="onRefresh">刷新(重新探测)</el-button>
      </div>
    </template>

    <el-row :gutter="12" class="browser">
      <el-col :span="9">
        <el-card shadow="never" class="browser__tree">
          <el-tree
            :key="treeKey"
            lazy
            :load="loadNode"
            :props="{ label: 'label', isLeaf: 'isLeaf' }"
            node-key="id"
            highlight-current
            @node-click="onNodeClick"
          />
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never">
          <template #header>
            <span v-if="columnsTitle" class="text-mono">{{ columnsTitle }}</span>
            <span v-else class="text-muted">在左侧点开一张表以查看字段</span>
          </template>

          <el-table :data="columns" v-loading="loadingColumns" size="small" max-height="560">
            <el-table-column prop="name" label="字段" min-width="140" />
            <el-table-column prop="rawType" label="原始类型" width="140">
              <template #default="{ row }">
                <span class="text-mono">{{ row.rawType }}</span>
              </template>
            </el-table-column>
            <el-table-column label="规范类型" width="140">
              <template #default="{ row }">
                <!-- UNKNOWN 要显眼:它意味着这个类型还没有安全的跨库映射,
                     P2 做异构建表时需要人工决策 -->
                <el-tag
                  :type="row.canonicalType === 'UNKNOWN' ? 'warning' : 'info'"
                  size="small"
                  effect="plain"
                >
                  {{ row.canonicalType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="主键" width="60">
              <template #default="{ row }">
                <el-icon v-if="row.primaryKey" color="#e6a23c"><Key /></el-icon>
              </template>
            </el-table-column>
            <el-table-column label="可空" width="60">
              <template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column prop="comment" label="注释" min-width="140" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </el-drawer>
</template>

<style scoped>
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 16px;
}

.browser__tree {
  min-height: 400px;
  max-height: 620px;
  overflow: auto;
}
</style>
