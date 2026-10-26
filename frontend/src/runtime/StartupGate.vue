<template>
  <main class="startup-card" aria-live="polite">
    <div class="startup-symbol" aria-hidden="true">{{ failed ? '!' : '…' }}</div>
    <h1>{{ failed ? '暂时无法打开应用' : '正在准备应用' }}</h1>
    <p>{{ failed ? '无法读取应用配置，请确认服务可用后重试。' : '正在读取应用配置，请稍候。' }}</p>
    <button v-if="failed" type="button" @click="run">重新加载</button>
  </main>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { loadRuntimeConfig } from './config'
const props = defineProps<{ ready: () => Promise<void> }>()
const failed = ref(false)
let running = false
async function run() {
  if (running) return
  running = true
  failed.value = false
  try {
    await loadRuntimeConfig()
    await props.ready()
  } catch { failed.value = true }
  finally { running = false }
}
onMounted(run)
</script>
<style scoped>
.startup-card { margin: 12vh auto; max-width: 460px; background: white; border: 1px solid #e4e8ef; border-radius: 12px; padding: 40px; text-align: center; color: #242c3c; }
.startup-symbol { font-size: 32px; color: #d48806; }
h1 { font-size: 22px; }
p { color: #626d7e; line-height: 1.8; }
button { background: #1677ff; color: white; border: 1px solid #1677ff; border-radius: 6px; padding: 9px 18px; cursor: pointer; }
</style>
