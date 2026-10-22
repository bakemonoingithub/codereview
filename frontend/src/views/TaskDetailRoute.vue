<template>
  <a-spin v-if="!routeKind" :spinning="true" class="route-spin" />
  <AgentTaskDetail v-else-if="routeKind === 'AGENT'" />
  <TaskDetail v-else />
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { tasksApi } from '@/api/v2'
import TaskDetail from '@/views/TaskDetail.vue'
import AgentTaskDetail from '@/views/AgentTaskDetail.vue'

/**
 * 任务详情按路线分流：Agent 使用独立的进度／调用／结果阅读，专项沿用原详情页。
 * 只读取一次路线；读取失败时回落到专项页面，由原页面呈现具体错误。
 */
const current = useRoute()
const routeKind = ref('')

onMounted(async () => {
  try {
    const task = await tasksApi.detail(String(current.params.id))
    routeKind.value = task.route
  } catch {
    routeKind.value = 'SPECIALIST'
  }
})
</script>

<style scoped>
.route-spin {
  display: block;
  padding: 80px 0;
  text-align: center;
}
</style>
