<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workspaceApi } from '@/api/workspace'
import { userApi } from '@/api/user'
import type { Workspace } from '@/types/workspace'
import type { User } from '@/types/user'
import { confirmAction } from '@/utils/confirm'

const loading = ref(false)
const rows = ref<Workspace[]>([])
const users = ref<User[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({ code: '', name: '', description: '' })

const memberVisible = ref(false)
const memberTarget = ref<Workspace | null>(null)
const memberIds = ref<string[]>([])
/** 空间管理员 —— 与授权用户同一个抽屉里编辑,因为两者是同一次心智活动 */
const adminIds = ref<string[]>([])
const togglingId = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    rows.value = await workspaceApi.listAccessible()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  try {
    users.value = await userApi.list()
  } catch {
    // 没有用户管理权限时成员选择器为空,不影响空间列表本身
  }
})

function onCreate() {
  editingId.value = null
  Object.assign(form, { code: '', name: '', description: '' })
  dialogVisible.value = true
}

function onEdit(row: Workspace) {
  editingId.value = row.id
  Object.assign(form, { code: row.code, name: row.name, description: row.description ?? '' })
  dialogVisible.value = true
}

async function onSubmit() {
  submitting.value = true
  try {
    if (editingId.value) {
      await workspaceApi.update(editingId.value, form)
    } else {
      await workspaceApi.create(form)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onManageMembers(row: Workspace) {
  memberTarget.value = row
  const [members, admins] = await Promise.all([
    workspaceApi.listMemberIds(row.id),
    workspaceApi.listAdminIds(row.id).catch(() => [] as string[]),
  ])
  memberIds.value = members
  adminIds.value = admins
  memberVisible.value = true
}

async function onSaveMembers() {
  const target = memberTarget.value
  if (!target) return

  // 管理员必须先是成员。不在这里补上,保存后会得到一个"是管理员但进不去空间"
  // 的用户 —— 后端 grantAdmin 会自动补成员,但下拉框里不显示会让人以为漏了。
  const effectiveMembers = Array.from(new Set([...memberIds.value, ...adminIds.value]))

  const existingMembers = await workspaceApi.listMemberIds(target.id)
  const toAdd = effectiveMembers.filter((id) => !existingMembers.includes(id))
  const toRemove = existingMembers.filter((id) => !effectiveMembers.includes(id))

  if (toAdd.length > 0) {
    await workspaceApi.addMembers(target.id, toAdd)
  }
  for (const id of toRemove) {
    await workspaceApi.removeMember(target.id, id)
  }

  const existingAdmins = await workspaceApi.listAdminIds(target.id).catch(() => [] as string[])
  for (const id of adminIds.value.filter((x) => !existingAdmins.includes(x))) {
    await workspaceApi.grantAdmin(target.id, id)
  }
  for (const id of existingAdmins.filter((x) => !adminIds.value.includes(x))) {
    await workspaceApi.revokeAdmin(target.id, id)
  }

  ElMessage.success('授权用户与空间管理员已更新')
  memberVisible.value = false
}

/**
 * 启用 / 停用空间(功能 28)。
 *
 * 停用需要二次确认:它会让空间里所有非平台管理员立刻失去访问,
 * 影响面远大于列表上其它按钮。启用则不必确认 —— 那是恢复动作。
 */
async function onToggleStatus(row: Workspace) {
  const enabling = row.status !== 'ACTIVE'
  if (!enabling) {
    // ElMessageBox 在用户点「取消」时 reject。不接住的话会变成一条未处理的
    // Promise rejection —— 控制台报错,而用户只是改了主意。
    const confirmed = await confirmAction(
      `停用后,「${row.name}」内的所有授权用户将立刻无法访问其中的数据源、任务与凭据。` +
        '数据不会被删除,随时可以重新启用。确定停用吗?',
      '停用空间',
      { confirmButtonText: '确认停用' },
    )
    if (!confirmed) {
      return
    }
  }
  togglingId.value = row.id
  try {
    await workspaceApi.setStatus(row.id, enabling)
    ElMessage.success(enabling ? '空间已启用' : '空间已停用')
    await load()
  } finally {
    togglingId.value = null
  }
}

async function onRotateSecret(row: Workspace) {
  if (
    !(await confirmAction(
      '轮换后将生成新的鉴权密钥,旧密钥标记为待废弃但短期内仍可用。确定吗?',
      '轮换鉴权密钥',
    ))
  ) {
    return
  }
  const secret = await workspaceApi.rotateSecret(row.id)
  // 这是密钥唯一一次以明文出现的机会,必须用不会自动消失的对话框展示
  ElMessageBox.alert(
    `请立即复制并妥善保存,关闭后无法再次查看:\n\n${secret}`,
    '新的鉴权密钥',
    { type: 'success', confirmButtonText: '我已保存' },
  ).catch(() => {})
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
          title="「空间」是租户"
          description="平台内每个对象(数据源、任务、告警、审计)都按空间隔离。它与架构文档中的 Space(边界)同名但不同物。"
          style="flex: 1"
        />
        <el-button v-permission="'platform:workspace:create'" type="primary" @click="onCreate">
          新建空间
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="name" label="空间名称" min-width="160" />
        <el-table-column prop="code" label="空间标识" min-width="140">
          <template #default="{ row }"><span class="text-mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'platform:workspace:update'" link type="primary" @click="onEdit(row as Workspace)">
              编辑
            </el-button>
            <el-button v-permission="'platform:workspace:member'" link type="primary" @click="onManageMembers(row as Workspace)">
              授权用户
            </el-button>
            <!-- 启停用 workspace:create 而非 :update —— 见后端 WorkspaceController 的说明 -->
            <el-button
              v-permission="'platform:workspace:create'"
              link
              :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
              :loading="togglingId === row.id"
              @click="onToggleStatus(row as Workspace)"
            >
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'platform:workspace:update'" link type="warning" @click="onRotateSecret(row as Workspace)">
              轮换密钥
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑空间' : '新建空间'" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="空间标识" required>
          <el-input v-model="form.code" :disabled="!!editingId" placeholder="小写字母、数字、-、_" />
          <div v-if="editingId" class="text-muted">标识创建后不可修改 —— 它可能已被外部系统引用</div>
        </el-form-item>
        <el-form-item label="空间名称" required>
          <el-input v-model="form.name" />
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

    <el-dialog v-model="memberVisible" :title="`授权用户 · ${memberTarget?.name ?? ''}`" width="560px">
      <el-form label-width="100px">
        <el-form-item label="授权用户">
          <el-select v-model="memberIds" multiple filterable placeholder="选择可访问该空间的用户" style="width: 100%">
            <el-option
              v-for="u in users"
              :key="u.id"
              :label="`${u.displayName || u.username}(${u.username})`"
              :value="u.id"
            />
          </el-select>
          <div class="text-muted">平台管理员无需授权即可访问所有空间,不必在此选择。</div>
        </el-form-item>

        <el-form-item label="空间管理员">
          <el-select v-model="adminIds" multiple filterable placeholder="选择该空间的管理员" style="width: 100%">
            <el-option
              v-for="u in users"
              :key="u.id"
              :label="`${u.displayName || u.username}(${u.username})`"
              :value="u.id"
            />
          </el-select>
          <div class="text-muted">
            空间管理员在本空间内拥有全部操作权限,但不能新建、删除或停用空间本身。
            选中后会自动成为授权用户。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberVisible = false">取消</el-button>
        <el-button type="primary" @click="onSaveMembers">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
