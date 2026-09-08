<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { jobApi } from '@/api/job'
import { formatDateTime } from '@/utils/format'
import type { JobDefinition, SchedulePreview } from '@/types/job'

/**
 * 周期调度配置(功能 16「支持 Cron 表达式的周期调度策略配置」)。
 *
 * <b>先预览后绑定。</b> Cron 表达式是出了名的容易写错,而写错的后果要等到
 * 该跑的时候没跑才发现。这里在绑定前把"接下来五次什么时候跑"摆出来,
 * 让用户自己确认 —— 这比任何表达式校验都有效。
 */

const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const submitting = ref(false)
const previewing = ref(false)
const target = ref<JobDefinition | null>(null)
const preview = ref<SchedulePreview | null>(null)
const previewError = ref('')

const form = reactive({
  cronExpression: '0 0 2 * * *',
  timezone: 'Asia/Shanghai',
  misfirePolicy: 'SKIP',
})

/** 常用表达式。多数用户要的就是这几个,不该逼他们去查 Cron 语法。 */
const PRESETS = [
  { label: '每天凌晨 2 点', value: '0 0 2 * * *' },
  { label: '每小时整点', value: '0 0 * * * *' },
  { label: '每 30 分钟', value: '0 0,30 * * * *' },
  { label: '每周一凌晨 3 点', value: '0 0 3 * * MON' },
  { label: '每月 1 号凌晨 1 点', value: '0 0 1 1 * *' },
]

const TIMEZONES = ['Asia/Shanghai', 'UTC', 'Asia/Tokyo', 'America/New_York', 'Europe/London']

const MISFIRE_POLICIES = [
  { value: 'SKIP', label: '跳过本次', hint: '上一次还没跑完就跳过。默认,也是数据同步类任务的稳妥选择' },
  { value: 'QUEUE', label: '排队补跑', hint: '等上一次结束后立刻补跑一次(只补一次,不累积)' },
  { value: 'CONCURRENT', label: '允许并发', hint: '只在任务幂等且轻量时才该选 —— 否则会堆出多个实例冲击目标库' },
]

function open(row: JobDefinition) {
  target.value = row
  preview.value = null
  previewError.value = ''
  form.cronExpression = row.cronExpression || '0 0 2 * * *'
  form.timezone = row.cronTimezone || 'Asia/Shanghai'
  form.misfirePolicy = row.misfirePolicy || 'SKIP'
  visible.value = true
  onPreview()
}

async function onPreview() {
  previewing.value = true
  previewError.value = ''
  try {
    preview.value = await jobApi.previewSchedule({
      cronExpression: form.cronExpression,
      timezone: form.timezone,
      misfirePolicy: form.misfirePolicy,
    })
  } catch (e) {
    // 表达式非法是预览的正常结果之一,就地显示而不是弹窗打断填写
    preview.value = null
    previewError.value = e instanceof Error ? e.message : '表达式无效'
  } finally {
    previewing.value = false
  }
}

function usePreset(value: string) {
  form.cronExpression = value
  onPreview()
}

async function onSubmit() {
  const row = target.value
  if (!row) return
  submitting.value = true
  try {
    await jobApi.bindSchedule(row.id, {
      cronExpression: form.cronExpression,
      timezone: form.timezone,
      misfirePolicy: form.misfirePolicy,
    })
    ElMessage.success('调度已绑定')
    visible.value = false
    emit('saved')
  } finally {
    submitting.value = false
  }
}

async function onUnbind() {
  const row = target.value
  if (!row) return
  await jobApi.unbindSchedule(row.id)
  ElMessage.success('已解绑调度。任务回到已发布,仍可手工执行')
  visible.value = false
  emit('saved')
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`周期调度 · ${target?.name ?? ''}`"
    width="640px"
    destroy-on-close
  >
    <el-form :model="form" label-width="110px">
      <el-form-item label="常用">
        <el-space wrap>
          <el-button
            v-for="p in PRESETS"
            :key="p.value"
            size="small"
            @click="usePreset(p.value)"
          >
            {{ p.label }}
          </el-button>
        </el-space>
      </el-form-item>

      <el-form-item label="Cron 表达式" required>
        <el-input v-model="form.cronExpression" class="text-mono" @change="onPreview" />
        <div class="text-muted">
          6 段格式:秒 分 时 日 月 周。从 crontab 抄来的 5 段会自动补上秒位。
        </div>
      </el-form-item>

      <el-form-item label="时区">
        <el-select v-model="form.timezone" style="width: 100%" @change="onPreview">
          <el-option v-for="tz in TIMEZONES" :key="tz" :label="tz" :value="tz" />
        </el-select>
        <div class="text-muted">
          跨时区团队里「每天凌晨两点」是谁的两点,必须说清楚。
        </div>
      </el-form-item>

      <el-form-item label="并发策略">
        <el-radio-group v-model="form.misfirePolicy">
          <el-radio v-for="p in MISFIRE_POLICIES" :key="p.value" :value="p.value">
            {{ p.label }}
          </el-radio>
        </el-radio-group>
        <div class="text-muted">
          {{ MISFIRE_POLICIES.find((p) => p.value === form.misfirePolicy)?.hint }}
        </div>
      </el-form-item>

      <!-- 预览是这个对话框存在的核心理由,不是附加信息 -->
      <el-form-item label="接下来会跑">
        <el-alert v-if="previewError" type="error" :closable="false" show-icon
                  :title="previewError" />
        <div v-else-if="preview" v-loading="previewing" class="preview">
          <div v-for="(t, i) in preview.upcomingFireTimes" :key="i" class="preview__row">
            <span class="text-muted">第 {{ i + 1 }} 次</span>
            <span class="text-mono">{{ formatDateTime(t) }}</span>
          </div>
        </div>
        <span v-else class="text-muted">填写表达式后自动预览</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button v-if="target?.cronExpression" type="warning" plain @click="onUnbind">
        解绑调度
      </el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!!previewError || !preview"
        @click="onSubmit"
      >
        绑定
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.preview {
  width: 100%;
  padding: 8px 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}

.preview__row {
  display: flex;
  gap: 12px;
  line-height: 1.9;
}
</style>
