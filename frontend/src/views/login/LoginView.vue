<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }],
}

async function onSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const result = await login({ username: form.username, password: form.password })
    auth.applySession(result)

    // 后端在有多个可访问空间时不自动选中,由用户在顶栏切换。
    // 这里不强制弹窗要求先选 —— 工作台在无空间时会给出引导,体验更连贯。
    if (!result.currentWorkspaceId && result.workspaces.length > 1) {
      ElMessage.info(`你有 ${result.workspaces.length} 个可访问空间,请在右上角选择`)
    }

    const redirect = (route.query.redirect as string) || '/workbench'
    router.replace(redirect)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="login__card" shadow="always">
      <template #header>
        <div class="login__title">数据治理平台</div>
        <div class="login__subtitle">数据集成 · 数据开发 · 运维监控 · 数据服务</div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" autocomplete="username">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="口令"
            show-password
            autocomplete="current-password"
            @keyup.enter="onSubmit"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            class="login__submit"
            :loading="submitting"
            native-type="submit"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="login__hint">
        首次部署的管理员初始口令由后端启动日志给出(未配置 DG_ADMIN_PASSWORD 时随机生成,仅打印一次)
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f3a5f 0%, #2d5a87 100%);
}

.login__card {
  width: 400px;
}

.login__title {
  font-size: 20px;
  font-weight: 600;
  text-align: center;
}

.login__subtitle {
  margin-top: 6px;
  text-align: center;
  font-size: 12px;
  color: #909399;
}

.login__submit {
  width: 100%;
}

.login__hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
