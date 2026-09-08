<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dataSourceApi } from '@/api/datasource'
import type { DataSource } from '@/types/datasource'

/** 周期连通性检查开关(功能 6 的后半句)。 */
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const submitting = ref(false)
const current = ref<DataSource | null>(null)
const enabled = ref(false)
const intervalMinutes = ref(30)

function open(row: DataSource) {
  current.value = row
  enabled.value = Boolean(row.probeEnabled)
  intervalMinutes.value = row.probeIntervalMinutes ?? 30
  visible.value = true
}

async function onSubmit() {
  const ds = current.value
  if (!ds) return
  submitting.value = true
  try {
    await dataSourceApi.setProbeSchedule(ds.id, enabled.value, intervalMinutes.value)
    ElMessage.success(enabled.value ? '已开启周期检查' : '已关闭周期检查')
    visible.value = false
    emit('saved')
  } finally {
    submitting.value = false
  }
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="周期连通性检查" width="520px">
    <el-form label-width="120px">
      <el-form-item label="数据源">
        <span>{{ current?.name }}</span>
      </el-form-item>

      <el-form-item label="开启周期检查">
        <el-switch v-model="enabled" />
      </el-form-item>

      <el-form-item v-if="enabled" label="检查间隔">
        <el-input-number v-model="intervalMinutes" :min="5" :max="1440" :step="5" />
        <span class="text-muted" style="margin-left: 8px">分钟(下限 5)</span>
      </el-form-item>

      <el-alert type="info" :closable="false" show-icon>
        周期检查会按间隔持续连接目标库,对生产库是真实负载,因此默认关闭。
        <br />
        它与手工测试的区别在失败后的落点:<b>周期检查失败会把「可用」标记为「不可达」</b>
        (说明环境出了状况),而手工测试失败回到「待验证」(说明配置可能没配对)。
      </el-alert>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>
