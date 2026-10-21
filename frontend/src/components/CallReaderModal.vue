<template>
  <a-modal
    :open="open"
    title="实际调用"
    width="min(1000px, calc(100vw - 48px))"
    :footer="null"
    destroy-on-close
    @cancel="close"
  >
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <template v-else-if="call">
        <a-descriptions :column="2" size="small" bordered class="mb12">
          <a-descriptions-item label="状态">{{ call.state }}</a-descriptions-item>
          <a-descriptions-item label="第几次">{{ call.attempt }}</a-descriptions-item>
          <a-descriptions-item label="用途">{{ call.purpose }}</a-descriptions-item>
          <a-descriptions-item label="耗时">
            {{ duration(call.startedAt, call.finishedAt) }}
          </a-descriptions-item>
          <a-descriptions-item v-if="call.errorCode" label="错误码">{{ call.errorCode }}</a-descriptions-item>
          <a-descriptions-item v-if="call.errorMessage" label="错误" :span="2">
            {{ call.errorMessage }}
          </a-descriptions-item>
        </a-descriptions>
        <div class="tabs">
          <a-button size="small" :type="part === 'request' ? 'primary' : 'default'" @click="part = 'request'">
            完整请求
          </a-button>
          <a-button size="small" :type="part === 'response' ? 'primary' : 'default'" @click="part = 'response'">
            模型返回
          </a-button>
          <span class="spacer" />
          <span class="muted small">保存的是当时实际发送内容，修改策略不会改变此处</span>
        </div>
        <pre class="code">{{ current }}</pre>
      </template>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { tasksApi, type CallDetailFull } from '@/api/v2'

const props = defineProps<{ open: boolean; taskId: string; callId: string }>()
const emit = defineEmits<{ (event: 'update:open', value: boolean): void }>()

const loading = ref(false)
const error = ref('')
const call = ref<CallDetailFull | null>(null)
const part = ref<'request' | 'response'>('request')

const current = computed(() => {
  if (!call.value) return ''
  const value = part.value === 'request' ? call.value.requestBody : call.value.responseBody
  if (!value) {
    return part.value === 'request' ? '（未保存请求正文）' : '（没有模型返回或调用未成功）'
  }
  return pretty(value)
})

watch(
  () => [props.open, props.callId] as const,
  async ([open]) => {
    if (!open || !props.callId) return
    loading.value = true
    error.value = ''
    part.value = 'request'
    try {
      call.value = await tasksApi.call(props.taskId, props.callId)
      if (!call.value.requestBody && call.value.responseBody) {
        part.value = 'response'
      }
    } catch (failure) {
      error.value = (failure as Error).message || '调用记录读取失败'
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

function pretty(value: string): string {
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function duration(start: string | null, end: string | null): string {
  if (!start || !end) return '—'
  const millis = new Date(end).getTime() - new Date(start).getTime()
  return millis >= 0 ? `${(millis / 1000).toFixed(1)} 秒` : '—'
}

function close() {
  emit('update:open', false)
}
</script>

<style scoped lang="less">
.mb12 {
  margin-bottom: 12px;
}
.muted {
  color: #69798d;
}
.small {
  font-size: 12px;
}
.tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
}
.spacer {
  flex: 1;
}
.code {
  max-height: 58vh;
  overflow: auto;
  margin: 0;
  padding: 12px;
  background: #f6f8fc;
  border: 1px solid #e3e9f0;
  border-radius: 6px;
  font: 12px/1.75 Consolas, 'Cascadia Code', monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
