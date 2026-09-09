<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ruleApi } from '@/api/rule'
import type { Rule, RuleCategory, RuleKind, RuleKindInfo } from '@/types/rule'

const loading = ref(false)
const rows = ref<Rule[]>([])
const kinds = ref<RuleKindInfo[]>([])
const categoryFilter = ref<RuleCategory | ''>('')
const dialogVisible = ref(false)
const submitting = ref(false)
const editingRule = ref<Rule | null>(null)

const form = reactive<{ name: string; kind: RuleKind | ''; description: string; params: Record<string, string> }>({
  name: '',
  kind: '',
  description: '',
  params: {},
})

const currentKind = computed(() => kinds.value.find((k) => k.kind === form.kind) ?? null)

/**
 * 参数的显示顺序。
 *
 * 后端用 Map.of 构造 paramSpec,而 Map.of 的迭代顺序是<b>不保证</b>的 ——
 * 直接照着渲染,同一个规则在两台机器上字段顺序可能不同。所以这里自己定序:
 * 必填在前(用户先看到非填不可的),其余按名字排,结果稳定可预期。
 */
const orderedParams = computed(() => {
  const k = currentKind.value
  if (!k) return []
  const required = new Set(k.requiredParams)
  // 顺序照后端给的来 —— RuleKind 里参数是按"先决定语义的、再细化的"写的
  // (脱敏的 mode 在最前,因为其余四个参数的含义都取决于它)。
  // 前端按字母排会把 mode 排到第四位,用户在还不知道自己选的是哪种模式时
  // 就要先填 keepPrefix。
  return Object.entries(k.paramSpec).map(([name, hint]) => ({
    name,
    hint,
    required: required.has(name),
  }))
})

const filteredRows = computed(() =>
  categoryFilter.value ? rows.value.filter((r) => r.category === categoryFilter.value) : rows.value,
)

/**
 * 从参数说明里认出可选值。
 *
 * paramSpec 的说明是写给人看的,但其中一部分本身就枚举了合法取值
 * ("UPPER / LOWER"、"BOTH / LEADING / TRAILING")。认出来渲染成下拉,
 * 比让用户照着提示手敲一个全大写单词强得多 —— 敲错了要等到执行期才知道。
 *
 * 认不出来就退回文本框。<b>宁可少认,不可认错</b>:把自由文本误判成枚举,
 * 用户就再也填不进他要的值了。
 */
function choicesOf(hint: string): string[] | null {
  if (/true\s*\/\s*false/i.test(hint)) return ['true', 'false']

  const parts = hint.split('/').map((s) => s.trim())
  if (parts.length < 2) return null

  // 每一段都必须<b>整段</b>就是一个全大写标识符(可带一个括号说明),
  // 而不是"以它开头"。这条严格性是买来的:maskChar 的说明是
  // "PARTIAL / FIXED:替换字符,默认 *" —— 那个斜杠分隔的是<b>适用模式</b>,
  // 不是取值。只要求"以大写词开头"的话,它会被渲染成 PARTIAL/FIXED 下拉,
  // 而用户想填的 * 或 # 从此填不进去。
  const tokens = parts.map((p) => {
    const m = p.match(/^([A-Z][A-Z_0-9]+)\s*(?:\([^)]*\))?$/)
    return m ? m[1] : null
  })
  return tokens.every(Boolean) ? (tokens as string[]) : null
}

async function load() {
  loading.value = true
  try {
    rows.value = await ruleApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  kinds.value = await ruleApi.kinds()
  await load()
})

function onKindChange() {
  // 换种类就清空参数:上一种的参数放到这一种上没有意义,留着只会被静默忽略
  form.params = {}
}

function onCreate() {
  editingRule.value = null
  Object.assign(form, { name: '', kind: '', description: '', params: {} })
  dialogVisible.value = true
}

function onEdit(row: Rule) {
  editingRule.value = row
  Object.assign(form, {
    name: row.name,
    kind: row.kind,
    description: row.description ?? '',
    params: { ...row.params },
  })
  dialogVisible.value = true
}

async function onSubmit() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入规则名称')
    return
  }
  if (!form.kind) {
    ElMessage.warning('请选择规则种类')
    return
  }
  const missing = (currentKind.value?.requiredParams ?? []).filter((p) => !form.params[p]?.trim())
  if (missing.length) {
    ElMessage.warning(`还有必填参数没填:${missing.join('、')}`)
    return
  }
  submitting.value = true
  try {
    // 只提交填了值的参数。空串提交上去会被当成"显式设成空",
    // 而多数参数的语义是"没填就用默认值"
    const params: Record<string, string> = {}
    for (const [k, v] of Object.entries(form.params)) {
      if (v !== undefined && v !== null && String(v).trim() !== '') params[k] = String(v)
    }
    const payload = {
      name: form.name,
      kind: form.kind as RuleKind,
      description: form.description || undefined,
      params,
    }
    if (editingRule.value) {
      await ruleApi.update(editingRule.value.id, payload)
    } else {
      await ruleApi.create(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: Rule) {
  await ElMessageBox.confirm(
    `确定删除规则「${row.name}」吗?`,
    '删除规则',
    { type: 'warning' },
  )
  await ruleApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

function paramSummary(row: Rule): string {
  const entries = Object.entries(row.params ?? {})
  if (!entries.length) return '（用默认值）'
  return entries.map(([k, v]) => `${k}=${v}`).join('  ')
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="规则在这里定义，在任务的字段上生效"
          description="一个字段可以挂多条规则，顺序有意义 —— 先去空格再判空，和先判空再去空格，对全是空白的值结果完全不同。被任务引用中的规则不能删除。"
          style="flex: 1"
        />
        <el-button v-permission="'metadata:rule:manage'" type="primary" @click="onCreate">
          新建规则
        </el-button>
      </div>

      <div class="page-toolbar__filters">
        <el-radio-group v-model="categoryFilter" size="small">
          <el-radio-button value="">全部（{{ rows.length }}）</el-radio-button>
          <el-radio-button value="CLEANSE">清洗</el-radio-button>
          <el-radio-button value="TRANSFORM">转换</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="filteredRows" v-loading="loading">
        <el-table-column prop="name" label="名称" min-width="170" />
        <el-table-column label="种类" width="150">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.kindDisplayName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="归类" width="90">
          <template #default="{ row }">
            <span class="text-muted">{{ row.categoryDisplayName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="参数" min-width="260">
          <template #default="{ row }">
            <span class="text-mono">{{ paramSummary(row as Rule) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column label="被引用" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.referenceCount > 0" size="small" type="warning" effect="plain">
              {{ row.referenceCount }} 个任务
            </el-tag>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'metadata:rule:manage'" link type="primary" @click="onEdit(row as Rule)">
              编辑
            </el-button>
            <!-- 被引用时就地说明为什么删不了,而不是让用户点下去吃一个 409 -->
            <el-tooltip
              v-if="row.referenceCount > 0"
              content="有任务正在引用它。删掉之后，那些任务的下一次执行就会漏出未处理的原始值。"
              placement="top"
            >
              <span><el-button link type="danger" disabled>删除</el-button></span>
            </el-tooltip>
            <el-button
              v-else
              v-permission="'metadata:rule:manage'"
              link
              type="danger"
              @click="onDelete(row as Rule)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingRule ? '编辑规则' : '新建规则'" width="600px">
      <el-form :model="form" label-width="112px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="给它一个在任务里一眼认得出的名字" />
        </el-form-item>

        <el-form-item label="种类" required>
          <el-select
            v-model="form.kind"
            style="width: 100%"
            :disabled="!!editingRule && editingRule.referenceCount > 0"
            @change="onKindChange"
          >
            <el-option-group
              v-for="cat in ['CLEANSE', 'TRANSFORM']"
              :key="cat"
              :label="cat === 'CLEANSE' ? '清洗' : '转换'"
            >
              <el-option
                v-for="k in kinds.filter((x) => x.category === cat)"
                :key="k.kind"
                :label="k.displayName"
                :value="k.kind"
              />
            </el-option-group>
          </el-select>
          <!-- 改种类等于改变所有引用它的任务的行为,而那些任务的负责人不会收到通知 -->
          <div v-if="editingRule && editingRule.referenceCount > 0" class="text-muted rule-hint">
            已被 {{ editingRule.referenceCount }} 个任务引用，不能改种类。
            换一种处理方式请新建一条规则，再到任务里替换。
          </div>
        </el-form-item>

        <template v-if="currentKind">
          <el-divider content-position="left">
            <span class="text-muted">参数</span>
          </el-divider>

          <el-form-item
            v-for="p in orderedParams"
            :key="p.name"
            :label="p.name"
            :required="p.required"
          >
            <el-select
              v-if="choicesOf(p.hint)"
              v-model="form.params[p.name]"
              style="width: 100%"
              clearable
            >
              <el-option v-for="c in choicesOf(p.hint)!" :key="c" :label="c" :value="c" />
            </el-select>
            <el-input v-else v-model="form.params[p.name]" :placeholder="p.hint" />
            <div class="text-muted rule-hint">{{ p.hint }}</div>
          </el-form-item>

          <el-alert
            v-if="form.kind === 'MASK' || form.kind === 'DECRYPT'"
            type="warning"
            :closable="false"
            show-icon
            class="rule-alert"
            :title="form.kind === 'MASK' ? '盐与密钥不写在这里' : '密钥不写在这里'"
            description="填的是凭据 ID，不是密钥本身。密钥存在凭据托管里，规则只持有一个不可解密的引用 —— 规则会随任务定义一起被导出和查看，把密钥写进参数等于把它公开。"
          />
        </template>

        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="写清楚它解决什么问题，别人接手时不用猜"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rule-hint {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 2px;
}
.rule-alert {
  margin-bottom: 18px;
}
</style>
