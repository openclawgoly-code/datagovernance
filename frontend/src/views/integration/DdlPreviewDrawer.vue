<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ddlApi } from '@/api/job'
import type { DdlPreviewRequest, DdlPreviewResponse } from '@/types/job'

/**
 * 建表语句预览与修改(功能 9「预览并修改建表语句」)。
 *
 * <b>为什么这一步不能省。</b> 类型映射再周密也有平台猜不到的地方 —— 目标端
 * 要不要分区、副本数几个、字符集用哪个、某个字段实际上只存两位小数。
 * 平台生成合理的初稿,人来定稿。全自动会在生产上建出一堆需要事后改的表,
 * 全手工则让「整库迁移」名不副实。
 *
 * <b>warnings 必须显示。</b> 整库迁移最常见的事故是某个字段悄悄变窄了
 * (VARCHAR 截断、时区丢失、精度降级),而这类降级只会在这里出现一次 ——
 * 错过了就再没有第二次提醒。
 */

const emit = defineEmits<{ confirmed: [table: string, script: string] }>()

const visible = ref(false)
const loading = ref(false)
const sourceTable = ref('')
const result = ref<DdlPreviewResponse | null>(null)
const script = ref('')
const error = ref('')
/** 用户改过就不再被重新生成覆盖 —— 否则他的修改会被静默丢弃 */
const edited = ref(false)

async function open(request: DdlPreviewRequest, existingScript?: string) {
  sourceTable.value = request.sourceTable
  visible.value = true
  loading.value = true
  error.value = ''
  edited.value = false
  result.value = null

  try {
    const response = await ddlApi.preview(request)
    result.value = response
    // 已有自定义语句时优先显示它,并标记为已编辑
    if (existingScript) {
      script.value = existingScript
      edited.value = true
    } else {
      script.value = response.script
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '生成建表语句失败'
  } finally {
    loading.value = false
  }
}

function onRegenerate() {
  if (result.value) {
    script.value = result.value.script
    edited.value = false
    ElMessage.info('已恢复为平台生成的初稿')
  }
}

function onConfirm() {
  if (!script.value.trim()) {
    ElMessage.warning('建表语句不能为空')
    return
  }
  emit('confirmed', sourceTable.value, script.value)
  visible.value = false
}

defineExpose({ open })
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="`建表语句 · ${sourceTable}`"
    size="55%"
    :append-to-body="true"
  >
    <div v-loading="loading">
      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

      <template v-if="result">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="源表">{{ result.sourceTable }}</el-descriptions-item>
          <el-descriptions-item label="目标表">
            <span class="text-mono">{{ result.targetTable }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="目标类型">{{ result.targetType }}</el-descriptions-item>
          <el-descriptions-item label="语句数">{{ result.statements.length }}</el-descriptions-item>
        </el-descriptions>

        <!--
          降级提醒放在语句上方而不是折叠在角落:这是整个抽屉里最容易被略过、
          却最可能酿成事故的信息。
        -->
        <el-alert
          v-if="result.warnings.length"
          type="warning"
          :closable="false"
          show-icon
          :title="`${result.warnings.length} 条类型转换提醒 —— 请逐条确认`"
          style="margin-top: 12px"
        >
          <ul class="warnings">
            <li v-for="(w, i) in result.warnings" :key="i">{{ w }}</li>
          </ul>
        </el-alert>

        <div class="editor-head">
          <span>建表语句(可直接修改)</span>
          <el-button v-if="edited" link type="primary" @click="onRegenerate">
            恢复平台初稿
          </el-button>
        </div>
        <el-input
          v-model="script"
          type="textarea"
          :rows="16"
          class="text-mono"
          @input="edited = true"
        />
        <div class="text-muted">
          确认后,执行整库迁移时会用<b>这一份</b>而不是重新生成 —— 你的修改不会被覆盖。
        </div>

        <div v-if="Object.keys(result.supportedOptions).length" class="text-muted options">
          <div>该目标类型支持的建表选项(在任务配置里填):</div>
          <div v-for="(desc, key) in result.supportedOptions" :key="key">
            <span class="text-mono">{{ key }}</span> —— {{ desc }}
          </div>
        </div>
      </template>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!result" @click="onConfirm">
        使用这份语句
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.warnings {
  margin: 4px 0 0;
  padding-left: 18px;
}

.editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 16px 0 6px;
}

.options {
  margin-top: 12px;
  line-height: 1.8;
}
</style>
