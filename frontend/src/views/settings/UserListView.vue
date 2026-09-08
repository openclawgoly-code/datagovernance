<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '@/api/user'
import { roleApi } from '@/api/role'
import type { User } from '@/types/user'
import type { Role } from '@/types/role'
import { confirmDanger } from '@/utils/confirm'

const loading = ref(false)
const keyword = ref('')
const rows = ref<User[]>([])
const roles = ref<Role[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  username: '',
  password: '',
  displayName: '',
  email: '',
  phone: '',
  platformAdmin: false,
})

const roleVisible = ref(false)
const roleTarget = ref<User | null>(null)
const selectedRoleIds = ref<string[]>([])

async function load() {
  loading.value = true
  try {
    rows.value = await userApi.list(keyword.value || undefined)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  try {
    roles.value = await roleApi.list()
  } catch {
    /* 无角色读取权限时不影响用户列表 */
  }
})

function onCreate() {
  editingId.value = null
  Object.assign(form, {
    username: '', password: '', displayName: '', email: '', phone: '', platformAdmin: false,
  })
  dialogVisible.value = true
}

function onEdit(row: User) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    password: '',
    displayName: row.displayName ?? '',
    email: row.email ?? '',
    phone: row.phone ?? '',
    platformAdmin: row.platformAdmin,
  })
  dialogVisible.value = true
}

async function onSubmit() {
  submitting.value = true
  try {
    if (editingId.value) {
      await userApi.update(editingId.value, {
        displayName: form.displayName,
        email: form.email,
        phone: form.phone,
      })
    } else {
      await userApi.create({
        username: form.username,
        password: form.password,
        displayName: form.displayName,
        email: form.email,
        phone: form.phone,
        platformAdmin: form.platformAdmin,
      })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onResetPassword(row: User) {
  try {
    const { value } = await ElMessageBox.prompt(
      `为「${row.username}」设置新口令(至少 8 位)`,
      '重置口令',
      { inputType: 'password', inputPattern: /.{8,}/, inputErrorMessage: '口令至少 8 位' },
    )
    await userApi.resetPassword(row.id, value)
    ElMessage.success('口令已重置')
  } catch {
    /* 取消 */
  }
}

async function onToggleStatus(row: User) {
  const enable = row.status !== 'ACTIVE'
  await userApi.setStatus(row.id, enable)
  ElMessage.success(enable ? '已启用' : '已停用')
  await load()
}

async function onDelete(row: User) {
  if (!(await confirmDanger(`确定删除用户「${row.username}」吗?`, '删除用户'))) {
    return
  }
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

async function onManageRoles(row: User) {
  roleTarget.value = row
  selectedRoleIds.value = await userApi.listRoleIds(row.id)
  roleVisible.value = true
}

async function onSaveRoles() {
  const target = roleTarget.value
  if (!target) return
  await userApi.assignRoles(target.id, selectedRoleIds.value)
  ElMessage.success('角色已更新')
  roleVisible.value = false
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-input
            v-model="keyword"
            placeholder="按用户名搜索"
            clearable
            style="width: 220px"
            @keyup.enter="load"
            @clear="load"
          />
          <el-button @click="load">查询</el-button>
        </div>
        <el-button v-permission="'platform:user:create'" type="primary" @click="onCreate">
          新建用户
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="displayName" label="姓名" min-width="120" />
        <el-table-column label="身份" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.platformAdmin" type="danger" size="small">平台管理员</el-tag>
            <span v-else class="text-muted">普通用户</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'platform:user:update'" link type="primary" @click="onEdit(row as User)">编辑</el-button>
            <el-button v-permission="'platform:user:assign-role'" link type="primary" @click="onManageRoles(row as User)">角色</el-button>
            <el-button v-permission="'platform:user:update'" link type="primary" @click="onResetPassword(row as User)">重置口令</el-button>
            <el-button v-permission="'platform:user:update'" link type="warning" @click="onToggleStatus(row as User)">
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'platform:user:delete'" link type="danger" @click="onDelete(row as User)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑用户' : '新建用户'" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="初始口令" required>
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
          <div class="text-muted">至少 8 位。用户口令以 BCrypt 单向散列保存,忘记只能重置。</div>
        </el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item v-if="!editingId" label="平台管理员">
          <el-switch v-model="form.platformAdmin" />
          <div class="text-muted">平台管理员可跨空间操作,是唯一被允许绕过空间隔离的身份。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleVisible" :title="`分配角色 · ${roleTarget?.username ?? ''}`" width="520px">
      <el-select v-model="selectedRoleIds" multiple filterable style="width: 100%" placeholder="选择角色">
        <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
      </el-select>
      <div class="text-muted" style="margin-top: 8px">
        角色授予是<b>按空间</b>的:这里的改动只影响当前空间,同一用户在别的空间下的角色不受影响。
      </div>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" @click="onSaveRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
