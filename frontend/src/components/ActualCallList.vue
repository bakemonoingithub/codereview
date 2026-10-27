<template>
  <div class="actual-call-list">
    <div class="call-list-head">
      <strong>逐次调用记录</strong>
      <span class="muted small">{{ calls.length }} 次尝试</span>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <div v-for="call in calls" :key="call.id" class="call-row">
      <div class="call-main">
        <div class="call-summary">
          <StatusBadge :tone="stateTone(call.state)">{{ stateText(call.state) }}</StatusBadge>
          <span class="muted small">第 {{ call.attempt }} 次传输尝试 · {{ formatTime(call.createdAt) }}</span>
        </div>
        <div v-if="showUnits" class="muted small">
          {{ call.generation ? '第 ' + call.generation + ' 代 · ' : '' }}
          {{ call.unitOrdinal ? '单元 ' + call.unitOrdinal : '单元未记录' }} · {{ call.purpose }}
        </div>
        <div v-if="call.errorCode" class="call-error small">错误码：{{ call.errorCode }}</div>
        <div v-if="call.errorMessage" class="call-error small">{{ call.errorMessage }}</div>
      </div>
      <a-button size="small" @click="emit('open', call.id)">完整输入 / 输出</a-button>
    </div>
    <div v-if="!calls.length && !error" class="call-empty muted">暂无已保存的模型调用记录。</div>
    <a-button v-if="hasMore" size="small" :loading="loadingMore" class="load-more" @click="emit('load-more')">
      加载更多调用
    </a-button>
  </div>
</template>

<script setup lang="ts">
import type { CallSummary, ReportCallSummary } from '@/api/v2'
import StatusBadge from '@/components/StatusBadge.vue'

type CallItem = Pick<ReportCallSummary, 'id' | 'attempt' | 'state' | 'errorCode' | 'createdAt'>
  & Partial<Pick<ReportCallSummary, 'errorMessage' | 'purpose'>>
  & Partial<Pick<CallSummary, 'generation' | 'unitOrdinal'>>

withDefaults(defineProps<{
  calls: CallItem[]
  formatTime: (value: string) => string
  showUnits?: boolean
  error?: string
  hasMore?: boolean
  loadingMore?: boolean
}>(), { showUnits: false, error: '', hasMore: false, loadingMore: false })

const emit = defineEmits<{
  open: [id: string]
  'load-more': []
}>()

function stateText(state: string) {
  return ({ RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[state] || state
}

function stateTone(state: string) {
  if (state === 'SUCCEEDED') return 'green'
  if (state === 'FAILED') return 'red'
  if (state === 'RUNNING') return 'blue'
  return 'default'
}
</script>

<style scoped lang="less">
.actual-call-list { min-width: 0; min-height: 320px; }
.call-list-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.call-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f2f5f9;
  :deep(.ant-btn) { flex-shrink: 0; }
}
.call-main { min-width: 0; overflow-wrap: anywhere; }
.call-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 8px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.call-error { color: var(--red); }
.call-empty { padding: 36px 0; text-align: center; }
.load-more { margin-top: 12px; }
</style>
