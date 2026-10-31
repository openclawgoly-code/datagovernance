<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import RouteProgress from '@/components/common/RouteProgress.vue'
import AppBootSkeleton from '@/components/common/AppBootSkeleton.vue'

// 应用根组件。路由之外不承担业务职责；这里只负责首屏骨架与全局路由进度。
const router = useRouter()
const booting = ref(true)

onMounted(async () => {
  try {
    await router.isReady()
  } finally {
    booting.value = false
  }
})
</script>

<template>
  <RouteProgress />
  <AppBootSkeleton v-if="booting" />
  <RouterView v-else />
</template>
