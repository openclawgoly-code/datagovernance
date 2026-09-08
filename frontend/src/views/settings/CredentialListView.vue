<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { credentialApi } from '@/api/credential'
import { CREDENTIAL_AUTH_TYPE_LABELS, type Credential, type CredentialAuthType } from '@/types/credential'

const loading = ref(false)
const rows = ref<Credential[]>([])
const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)

const form = reactive({
  name: '',
  authType: 'PASSWORD' as CredentialAuthType,
  username: '',
  secret: '',
  description: '',
})

async function load() {
  loading.value = true
  try {
    rows.value = await credentialApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onCreate() {
  editingId.value = null
  Object.assign(form, { name: '', authType: 'PASSWORD', username: '', secret: '', description: '' })
  dialogVisible.value = true
}

function onEdit(row: Credential) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    authType: row.authType,
    username: '',
    // 编辑时口令必然为空:后端从不回显它,前端也没有可回填的值
    secret: '',
    description: row.description ?? '',
  })
  dialogVisible.value = true
}

async function onSubmit() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入凭据名称')
    return
  }
  submitting.value = true
  try {
    const payload = {
      name: form.name,
      authType: form.authType,
      username: form.username || undefined,
      // 留空表示保持原口令不变,而不是改成空口令
      secret: form.secret || undefined,
      description: form.description || undefined,
    }
    if (editingId.value) {
      await credentialApi.update(editingId.value, payload)
    } else {
      await credentialApi.create(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: Credential) {
  await ElMessageBox.confirm(
    `确定删除凭据「${row.name}」吗?引用它的数据源将无法连接。`,
    '删除凭据',
    { type: 'warning' },
  )
  await credentialApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
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
          title="凭据内容不可查看"
          description="平台只保存加密后的凭据,任何接口都不会回显明文或密文。忘记口令只能重新设置。"
          style="flex: 1"
        />
        <el-button v-permission="'platform:credential:create'" type="primary" @click="onCreate">
          新建凭据
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column label="认证方式" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">
              {{ CREDENTIAL_AUTH_TYPE_LABELS[row.authType as CredentialAuthType] ?? row.authType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'platform:credential:update'" link type="primary" @click="onEdit(row as Credential)">
              编辑
            </el-button>
            <el-button v-permission="'platform:credential:delete'" link type="danger" @click="onDelete(row as Credential)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑凭据' : '新建凭据'" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="form.authType" style="width: 100%">
            <el-option
              v-for="(label, value) in CREDENTIAL_AUTH_TYPE_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.authType === 'BASIC' || form.authType === 'PASSWORD'" label="用户名">
          <el-input v-model="form.username" autocomplete="off" />
        </el-form-item>
        <el-form-item v-if="form.authType !== 'NONE'" label="口令 / Token">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editingId ? '留空表示不修改' : ''"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
