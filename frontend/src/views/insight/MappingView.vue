<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { insightApi } from '@/api/insight'
import type { InsightSupersonicMapping } from '@/types/insight'
import { formatDateTime } from '@/utils/format'

const rows = ref<InsightSupersonicMapping[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const draft = ref({ platformUsername: '', supersonicUsername: '', credentialId: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await insightApi.mappings()
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!draft.value.platformUsername.trim() || !draft.value.supersonicUsername.trim()
    || !draft.value.credentialId.trim()) {
    ElMessage.warning('平台用户、SuperSonic 用户和凭据 ID 都不能为空')
    return
  }
  await insightApi.saveMapping({
    platformUsername: draft.value.platformUsername.trim(),
    supersonicUsername: draft.value.supersonicUsername.trim(),
    credentialId: draft.value.credentialId.trim(),
  })
  ElMessage.success('映射已保存')
  dialogVisible.value = false
  draft.value = { platformUsername: '', supersonicUsername: '', credentialId: '' }
  await load()
}

async function remove(id: string) {
  await ElMessageBox.confirm('删除映射后该用户将回退到服务账号,确定吗?', '删除映射', {
    type: 'warning',
  })
  await insightApi.deleteMapping(id)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-container mapping">
    <el-card shadow="never">
      <template #header>
        <div class="mapping__header">
          <div>
            <span>问数账号映射</span>
            <div class="text-muted">
              平台用户 → SuperSonic 账号;口令通过平台凭据托管,这里只保存 credentialId
            </div>
          </div>
          <el-button type="primary" @click="dialogVisible = true">新增映射</el-button>
        </div>
      </template>

      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="platformUsername" label="平台用户" min-width="140" />
        <el-table-column prop="supersonicUsername" label="SuperSonic 用户" min-width="160" />
        <el-table-column prop="credentialId" label="凭据 ID" min-width="220" show-overflow-tooltip />
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="remove(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="还没有映射,问数会使用服务账号" />
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增映射" width="520px">
      <el-form label-position="top">
        <el-form-item label="平台用户名">
          <el-input v-model="draft.platformUsername" placeholder="例如 admin" />
        </el-form-item>
        <el-form-item label="SuperSonic 用户名">
          <el-input v-model="draft.supersonicUsername" placeholder="SuperSonic 登录名" />
        </el-form-item>
        <el-form-item label="凭据 ID">
          <el-input v-model="draft.credentialId" placeholder="在「凭据管理」中创建 PASSWORD 凭据后复制 ID" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mapping__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
</style>
