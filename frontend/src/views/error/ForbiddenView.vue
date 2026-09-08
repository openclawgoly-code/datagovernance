<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="403" sub-title="当前空间下你没有访问该页面的权限">
    <template #extra>
      <!-- 提示"换个空间试试",因为同一个用户在不同空间下的权限是不同的,
           这是本平台一个容易让人困惑的地方,值得在错误页里直说 -->
      <p class="hint">同一账号在不同空间下的权限可能不同,可尝试在右上角切换空间。</p>
      <el-button type="primary" @click="router.push('/workbench')">返回工作台</el-button>
    </template>
  </el-result>
</template>

<style scoped>
.hint {
  color: #909399;
  font-size: 13px;
  margin-bottom: 12px;
}
</style>
