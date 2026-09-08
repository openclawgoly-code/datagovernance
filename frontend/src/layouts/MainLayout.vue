<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { switchWorkspace, logout as logoutApi } from '@/api/auth'
import MenuTree from './MenuTree.vue'
import { confirmAction } from '@/utils/confirm'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const switching = ref(false)

/**
 * 侧边栏菜单直接用后端下发的树。
 *
 * 前端不硬编码菜单,理由不只是"配置化":菜单项与权限码一一对应,
 * 前端硬编码一份等于把权限模型复制了一份,两份迟早不一致 ——
 * 而不一致的表现是用户看得见一个点进去报 403 的菜单。
 */
const menus = computed(() => auth.menus)

const activeMenu = computed(() => route.path)

const breadcrumbs = computed(() => {
  const title = route.meta.title as string | undefined
  return title ? [title] : []
})

async function onSwitchWorkspace(workspaceId: string) {
  if (workspaceId === auth.workspaceId || switching.value) {
    return
  }
  switching.value = true
  try {
    // 切换空间必须换令牌:令牌里的空间由服务端签名背书,
    // 只改前端状态而不换令牌,后端认的还是旧空间
    const result = await switchWorkspace({ workspaceId })
    auth.applySession(result)
    ElMessage.success(`已切换到「${auth.currentWorkspace?.name ?? workspaceId}」`)
    // 回工作台:当前页面的数据属于上一个空间,留在原地会看到一片空白或残留
    router.replace('/workbench')
  } finally {
    switching.value = false
  }
}

async function onLogout() {
  const confirmed = await confirmAction('确定要退出登录吗?', '退出登录', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
  })
  if (!confirmed) {
    return
  }
  try {
    await logoutApi()
  } catch {
    // 服务端不维护会话状态,登出失败也应该让用户走
  }
  auth.clearSession()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="layout__aside">
      <div class="layout__brand">数据治理平台</div>
      <el-scrollbar>
        <el-menu :default-active="activeMenu" router unique-opened class="layout__menu">
          <MenuTree :items="menus" />
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="layout__header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="crumb in breadcrumbs" :key="crumb">
            {{ crumb }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div class="layout__header-right">
          <!-- 空间切换。「空间」是租户概念,不是架构里的 Space。 -->
          <el-select
            :model-value="auth.workspaceId"
            :loading="switching"
            placeholder="选择空间"
            size="default"
            style="width: 180px"
            @change="onSwitchWorkspace"
          >
            <!--
              停用的空间照样列出来但不可选:隐藏它会让用户以为空间被删了,
              而选中它只会换来满屏 403。禁用项加上后缀说明原因。
            -->
            <el-option
              v-for="ws in auth.workspaces"
              :key="ws.id"
              :label="ws.status === 'ACTIVE' ? ws.name : `${ws.name}(已停用)`"
              :value="ws.id"
              :disabled="ws.status !== 'ACTIVE'"
            />
          </el-select>

          <el-dropdown @command="onLogout">
            <span class="layout__user">
              {{ auth.user?.displayName || auth.user?.username || '未登录' }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="layout__main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.layout__aside {
  background-color: #304156;
  display: flex;
  flex-direction: column;
}

.layout__brand {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
  flex-shrink: 0;
}

.layout__menu {
  border-right: none;
  background-color: #304156;
}

.layout__menu :deep(.el-menu-item),
.layout__menu :deep(.el-sub-menu__title) {
  color: #bfcbd9;
}

.layout__menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background-color: #263445;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.layout__header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.layout__user {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #606266;
}

.layout__main {
  background-color: #f5f7fa;
  padding: 0;
  overflow: auto;
}
</style>
