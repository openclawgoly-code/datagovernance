<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type ElTree } from 'element-plus'
import { roleApi } from '@/api/role'
import type { Role } from '@/types/role'
import type { PermissionNode } from '@/types/permission'
import { confirmDanger } from '@/utils/confirm'

const loading = ref(false)
const rows = ref<Role[]>([])
const permissionTree = ref<PermissionNode[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const treeRef = ref<InstanceType<typeof ElTree>>()
const form = reactive({ code: '', name: '', description: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await roleApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  permissionTree.value = await roleApi.permissions()
})

function onCreate() {
  editingId.value = null
  Object.assign(form, { code: '', name: '', description: '' })
  dialogVisible.value = true
  // 等对话框里的树渲染出来再清空勾选
  setTimeout(() => treeRef.value?.setCheckedKeys([], false), 0)
}

function onEdit(row: Role) {
  editingId.value = row.id
  Object.assign(form, { code: row.code, name: row.name, description: row.description ?? '' })
  dialogVisible.value = true
  setTimeout(() => treeRef.value?.setCheckedKeys(row.permissionCodes ?? [], false), 0)
}

async function onSubmit() {
  submitting.value = true
  try {
    // 半选的父节点也要提交:菜单节点本身就是一个权限码,
    // 只勾了子操作却不给菜单,用户会看不到入口
    const checked = (treeRef.value?.getCheckedKeys(false) ?? []) as string[]
    const halfChecked = (treeRef.value?.getHalfCheckedKeys() ?? []) as string[]
    const permissionCodes = [...checked, ...halfChecked]

    if (editingId.value) {
      await roleApi.update(editingId.value, { ...form, permissionCodes })
    } else {
      await roleApi.create({ ...form, permissionCodes })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: Role) {
  if (
    !(await confirmDanger(
      `确定删除角色「${row.name}」吗?已授予该角色的用户会立即失去对应权限。`,
      '删除角色',
    ))
  ) {
    return
  }
  await roleApi.remove(row.id)
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
          title="内置角色对所有空间可见,但不可修改"
          description="允许某个空间改内置角色,等于让一个租户影响其他租户。需要定制请新建空间级角色。"
          style="flex: 1"
        />
        <el-button v-permission="'platform:role:create'" type="primary" @click="onCreate">
          新建角色
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="name" label="角色名称" min-width="160" />
        <el-table-column prop="code" label="标识" min-width="160">
          <template #default="{ row }"><span class="text-mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag :type="row.builtIn ? 'info' : 'success'" size="small" effect="plain">
              {{ row.builtIn ? '平台内置' : '本空间' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权限数" width="90">
          <template #default="{ row }">{{ row.permissionCodes?.length ?? 0 }}</template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'platform:role:update'"
              link
              type="primary"
              :disabled="row.builtIn"
              @click="onEdit(row as Role)"
            >
              编辑
            </el-button>
            <el-button
              v-permission="'platform:role:delete'"
              link
              type="danger"
              :disabled="row.builtIn"
              @click="onDelete(row as Role)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑角色' : '新建角色'" width="640px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="角色标识" required>
          <el-input v-model="form.code" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="菜单权限">
          <el-tree
            ref="treeRef"
            :data="permissionTree"
            show-checkbox
            node-key="code"
            default-expand-all
            :props="{ label: 'name', children: 'children' }"
            class="perm-tree"
          >
            <template #default="{ data }">
              <span>
                {{ data.name }}
                <!-- ownerSpace 显示出来是有意的:它让「基础配置菜单横跨三个 Space」
                     这条架构结论在界面上就能看见,而不是只写在文档里 -->
                <span class="text-muted">{{ data.ownerSpace }}</span>
              </span>
            </template>
          </el-tree>
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
.perm-tree {
  width: 100%;
  max-height: 340px;
  overflow: auto;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 8px;
}
</style>
