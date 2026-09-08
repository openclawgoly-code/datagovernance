<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { JobDefinition, WorkflowEdge, WorkflowNode } from '@/types/job'

/**
 * 工作流的节点与边编辑器(序号 22)。
 *
 * <b>没有做成拖拽画布。</b> 一个画布组件要处理布局、连线、缩放、撤销,那是
 * 几千行的投入;而这里真正要表达的东西只有两件:哪些节点、谁指向谁。
 * 表格能把这两件事说得同样清楚,而且它能显示画布显示不了的东西 ——
 * 每个节点引用的是哪个任务、那个任务发布了没有。
 *
 * 校验交给后端编译:环、孤岛、分支缺失都会带定位返回。前端做一遍等于把
 * 同一套规则写两份,而两份迟早会不一致。
 */

const props = defineProps<{
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  /** 可被引用的任务定义(已发布的);工作流自己不在其中 */
  candidates: JobDefinition[]
}>()

const emit = defineEmits<{
  'update:nodes': [WorkflowNode[]]
  'update:edges': [WorkflowEdge[]]
}>()

const localNodes = ref<WorkflowNode[]>([])
const localEdges = ref<WorkflowEdge[]>([])

watch(() => props.nodes, (value) => { localNodes.value = value.map((n) => ({ ...n })) },
  { immediate: true, deep: true })
watch(() => props.edges, (value) => { localEdges.value = value.map((e) => ({ ...e })) },
  { immediate: true, deep: true })

const SOURCES = [
  { value: 'UPSTREAM_STATUS', label: '上游状态' },
  { value: 'UPSTREAM_ROWS_WRITTEN', label: '上游写入行数' },
  { value: 'UPSTREAM_ROWS_READ', label: '上游读取行数' },
]
const OPERATORS = [
  { value: 'EQ', label: '=' },
  { value: 'NE', label: '≠' },
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '≥' },
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '≤' },
]

const nodeOptions = computed(() =>
  localNodes.value.map((n) => ({ value: n.id, label: `${n.name}(${n.id})` })),
)

/** 状态只能用 EQ / NE 比较 —— 与后端编译期的规则一致 */
function operatorsFor(source: string) {
  return source === 'UPSTREAM_STATUS'
    ? OPERATORS.filter((o) => o.value === 'EQ' || o.value === 'NE')
    : OPERATORS
}

function nextId(prefix: string): string {
  let n = 1
  while (localNodes.value.some((node) => node.id === `${prefix}${n}`)) {
    n++
  }
  return `${prefix}${n}`
}

function addTaskNode() {
  const id = nextId('task')
  localNodes.value.push({ id, name: id, kind: 'TASK', jobDefinitionId: null })
  push()
}

function addConditionNode() {
  const id = nextId('cond')
  localNodes.value.push({
    id, name: id, kind: 'CONDITION',
    condition: { source: 'UPSTREAM_ROWS_WRITTEN', operator: 'GT', value: '0' },
  })
  push()
}

function removeNode(index: number) {
  const removed = localNodes.value[index]
  localNodes.value.splice(index, 1)
  // 连带删掉指向它的边 —— 留着的话编译会报"边的终点不是任何一个节点",
  // 而用户明明只是删了一个节点
  localEdges.value = localEdges.value.filter(
    (e) => e.from !== removed.id && e.to !== removed.id,
  )
  push()
}

function addEdge() {
  if (localNodes.value.length < 2) {
    ElMessage.warning('至少要有两个节点才能连边')
    return
  }
  localEdges.value.push({ from: localNodes.value[0].id, to: localNodes.value[1].id, branch: null })
  push()
}

function removeEdge(index: number) {
  localEdges.value.splice(index, 1)
  push()
}

/** 这条边是不是从条件节点出发的 —— 只有那种才需要标分支 */
function isConditionSource(edge: WorkflowEdge): boolean {
  return localNodes.value.find((n) => n.id === edge.from)?.kind === 'CONDITION'
}

function onSourceChange(node: WorkflowNode) {
  // 从行数切到状态时,大小比较符不再合法,回退到 EQ
  if (node.condition && node.condition.source === 'UPSTREAM_STATUS'
      && !['EQ', 'NE'].includes(node.condition.operator)) {
    node.condition.operator = 'EQ'
  }
  push()
}

function push() {
  emit('update:nodes', localNodes.value.map((n) => ({ ...n })))
  emit('update:edges', localEdges.value.map((e) => ({ ...e })))
}
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
      <template #title>条件节点在平台内部求值,不下发执行引擎</template>
      它只能读上游执行的<b>事实</b>(状态、行数),读不到业务数据 ——
      这是刻意的边界,不是功能没做完。环、孤岛、分支缺失由编译检查,会指到具体节点。
    </el-alert>

    <div class="page-toolbar" style="margin-bottom: 8px">
      <span class="text-muted">节点({{ localNodes.length }})</span>
      <span>
        <el-button size="small" @click="addTaskNode">添加任务节点</el-button>
        <el-button size="small" @click="addConditionNode">添加条件节点</el-button>
      </span>
    </div>

    <el-table :data="localNodes" size="small" max-height="280">
      <el-table-column label="节点 ID" width="110">
        <template #default="{ row }">
          <el-input v-model="(row as WorkflowNode).id" size="small" @change="push" />
        </template>
      </el-table-column>
      <el-table-column label="名称" width="140">
        <template #default="{ row }">
          <el-input v-model="(row as WorkflowNode).name" size="small" @change="push" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="(row as WorkflowNode).kind === 'CONDITION' ? 'warning' : 'primary'">
            {{ (row as WorkflowNode).kind === 'CONDITION' ? '条件' : '任务' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="配置" min-width="320">
        <template #default="{ row }">
          <el-select
            v-if="(row as WorkflowNode).kind === 'TASK'"
            v-model="(row as WorkflowNode).jobDefinitionId"
            filterable
            placeholder="选择一个已发布的任务"
            size="small"
            style="width: 100%"
            @change="push"
          >
            <el-option
              v-for="c in candidates"
              :key="c.id"
              :label="`${c.name}(${c.jobTypeDisplayName} · ${c.statusDisplayName})`"
              :value="c.id"
            />
          </el-select>
          <div v-else-if="(row as WorkflowNode).condition" style="display: flex; gap: 6px">
            <el-select
              v-model="(row as WorkflowNode).condition!.source"
              size="small"
              style="width: 140px"
              @change="onSourceChange(row as WorkflowNode)"
            >
              <el-option v-for="s in SOURCES" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-select
              v-model="(row as WorkflowNode).condition!.operator"
              size="small"
              style="width: 80px"
              @change="push"
            >
              <el-option
                v-for="o in operatorsFor((row as WorkflowNode).condition!.source)"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
            <el-input
              v-model="(row as WorkflowNode).condition!.value"
              size="small"
              style="width: 120px"
              @change="push"
            />
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="removeNode($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="page-toolbar" style="margin: 12px 0 8px">
      <span class="text-muted">依赖边({{ localEdges.length }})</span>
      <el-button size="small" @click="addEdge">添加边</el-button>
    </div>

    <el-table :data="localEdges" size="small" max-height="220">
      <el-table-column label="从" min-width="160">
        <template #default="{ row }">
          <el-select v-model="(row as WorkflowEdge).from" size="small" style="width: 100%" @change="push">
            <el-option v-for="o in nodeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="到" min-width="160">
        <template #default="{ row }">
          <el-select v-model="(row as WorkflowEdge).to" size="small" style="width: 100%" @change="push">
            <el-option v-for="o in nodeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="分支" width="130">
        <template #default="{ row }">
          <!-- 任务节点的出边不该带分支标记:它只有一种"下一步" -->
          <el-select
            v-if="isConditionSource(row as WorkflowEdge)"
            v-model="(row as WorkflowEdge).branch"
            size="small"
            placeholder="必选"
            style="width: 100%"
            @change="push"
          >
            <el-option label="条件成立" value="TRUE" />
            <el-option label="条件不成立" value="FALSE" />
          </el-select>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="removeEdge($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
