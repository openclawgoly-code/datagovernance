<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CompileResponse } from '@/types/job'

/**
 * 编译诊断。
 *
 * <b>存在的理由是「错误要指到具体字段」。</b> 一个二十字段的同步任务编译失败,
 * 只说「字段类型不兼容」等于什么都没说 —— 用户要逐个字段去猜是哪一个。
 * 所以每条诊断都显示 location(出问题的字段/节点/表名)与 hint(怎么改)。
 *
 * 成功但有 WARNING 时也弹:有损的类型映射能跑,但半年后会表现为
 * 「报表对不上账」,那时已经无从追溯。
 */

const visible = ref(false)
const jobName = ref('')
const result = ref<CompileResponse | null>(null)

const errors = computed(
  () => result.value?.diagnostics.filter((d) => d.severity === 'ERROR') ?? [],
)
const warnings = computed(
  () => result.value?.diagnostics.filter((d) => d.severity === 'WARNING') ?? [],
)

function open(name: string, compileResult: CompileResponse) {
  jobName.value = name
  result.value = compileResult
  visible.value = true
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`编译结果 · ${jobName}`"
    width="720px"
    destroy-on-close
  >
    <template v-if="result">
      <el-alert
        :type="result.succeeded ? (warnings.length ? 'warning' : 'success') : 'error'"
        :closable="false"
        show-icon
        :title="result.summary"
      />

      <template v-if="errors.length">
        <h4>错误({{ errors.length }})—— 必须修复才能发布</h4>
        <div v-for="(d, i) in errors" :key="`e${i}`" class="diag diag--error">
          <div class="diag__head">
            <el-tag type="danger" size="small">{{ d.stage }}</el-tag>
            <span v-if="d.location" class="text-mono diag__location">{{ d.location }}</span>
          </div>
          <div>{{ d.message }}</div>
          <div v-if="d.hint" class="text-muted">建议:{{ d.hint }}</div>
        </div>
      </template>

      <template v-if="warnings.length">
        <h4>提醒({{ warnings.length }})—— 不阻断,但值得看一眼</h4>
        <div v-for="(d, i) in warnings" :key="`w${i}`" class="diag diag--warning">
          <div class="diag__head">
            <el-tag type="warning" size="small">{{ d.stage }}</el-tag>
            <span v-if="d.location" class="text-mono diag__location">{{ d.location }}</span>
          </div>
          <div>{{ d.message }}</div>
          <div v-if="d.hint" class="text-muted">建议:{{ d.hint }}</div>
        </div>
      </template>

      <el-empty
        v-if="!errors.length && !warnings.length"
        description="编译通过,没有任何提醒"
        :image-size="80"
      />
    </template>

    <template #footer>
      <el-button type="primary" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
h4 {
  margin: 16px 0 8px;
}

.diag {
  padding: 8px 12px;
  margin-bottom: 8px;
  border-left: 3px solid;
  border-radius: 0 4px 4px 0;
  background: var(--el-fill-color-lighter);
}

.diag--error {
  border-left-color: var(--el-color-danger);
}

.diag--warning {
  border-left-color: var(--el-color-warning);
}

.diag__head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}

.diag__location {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
