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
          <a-button size="small" :type="part === 'usage' ? 'primary' : 'default'" @click="part = 'usage'">
            调用用量
          </a-button>
          <span class="spacer" />
          <a-checkbox v-model:checked="wrapLines">自动换行</a-checkbox>
          <span class="muted small">保存的是当时实际发送的内容，后续配置调整不会改变此处</span>
        </div>
        <div class="code" tabindex="0" role="region" aria-label="实际调用内容">
          <pre class="call-content" :class="{ 'wrap-lines': wrapLines }"><code v-html="highlightedCurrent"></code></pre>
        </div>
      </template>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { highlightSource } from '@/utils/markdown'
import { modelCallsApi, tasksApi, type CallDetailFull } from '@/api/v2'

const props = defineProps<{ open: boolean; taskId?: string; callId: string }>()
const emit = defineEmits<{ (event: 'update:open', value: boolean): void }>()

let loadEpoch = 0
const loading = ref(false)
const error = ref('')
const call = ref<CallDetailFull | null>(null)
const part = ref<'request' | 'response' | 'usage'>('request')
const wrapLines = ref(true)

const current = computed(() => {
  if (!call.value) return ''
  const value = part.value === 'request' ? call.value.requestBody
    : part.value === 'usage' ? call.value.usageJson : call.value.responseBody
  if (!value) {
    return part.value === 'request' ? '（未保存请求正文）'
      : part.value === 'usage' ? '（未返回用量信息）' : '（没有模型返回或调用未成功）'
  }
  return pretty(value)
})

const jsonLike = computed(() => current.value.startsWith('{') || current.value.startsWith('['))
const highlightedCurrent = computed(() => highlightSource(current.value, jsonLike.value ? 'json' : ''))

watch(
  () => [props.open, props.taskId, props.callId] as const,
  async ([open]) => {
    const epoch = ++loadEpoch
    call.value = null
    if (!open || !props.callId) return
    loading.value = true
    error.value = ''
    part.value = 'request'
    try {
      const loaded = props.taskId
        ? await tasksApi.call(props.taskId, props.callId)
        : await modelCallsApi.detail(props.callId)
      if (epoch !== loadEpoch) return
      call.value = loaded
      if (!call.value.requestBody && call.value.responseBody) {
        part.value = 'response'
      }
    } catch (failure) {
      if (epoch === loadEpoch) error.value = (failure as Error).message || '调用记录读取失败'
    } finally {
      if (epoch === loadEpoch) loading.value = false
    }
  },
  { immediate: true, flush: 'sync' }
)

onBeforeUnmount(() => { loadEpoch++ })

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
  loadEpoch++
  emit('update:open', false)
}
</script>

<style scoped lang="less">
.mb12 {
  margin-bottom: 12px;
}
.muted {
  color: var(--muted);
}
.small {
  font-size: 12px;
}
.tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.code {
  min-width: 0;
  max-width: 100%;
  max-height: 58vh;
  overflow: auto;
  overscroll-behavior: contain;
  margin: 0;
  background: #f6f8fc;
  border: 1px solid var(--line);
  border-radius: 6px;
}
.call-content {
  margin: 0;
  padding: 12px 16px;
  overflow: visible;
  background: transparent;
  font: 13px/22px Consolas, Monaco, monospace;
  white-space: pre;
}
.call-content.wrap-lines {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.call-content code {
  font: inherit;
  padding: 0;
  background: transparent;
  white-space: inherit;
  overflow-wrap: inherit;
}
</style>
