<template>
  <div ref="wrapRef" class="commit-table" @scroll.passive="onScroll">
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" class="mb8">
        <template #action>
          <a-button size="small" @click="$emit('retry')">重试</a-button>
        </template>
      </a-alert>
      <a-empty v-else-if="!loading && !commits.length" description="该分支暂无提交" />
      <a-table
        v-else
        size="small"
        row-key="sha"
        :data-source="commits"
        :columns="columns"
        :pagination="false"
        :custom-row="customRow"
        :row-class-name="rowClass"
      />
      <div v-if="commits.length" class="footer">
        <a-button v-if="hasMore" size="small" :loading="loadingMore" @click="$emit('load-more')">
          加载更多
        </a-button>
        <span v-else class="done">已到最早一条</span>
      </div>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

export interface CommitRow {
  sha: string
  message?: string
  author?: string
  date?: string
}

const props = withDefaults(
  defineProps<{
    commits: CommitRow[]
    selected?: string
    hasMore?: boolean
    loading?: boolean
    loadingMore?: boolean
    error?: string
  }>(),
  {
    selected: '',
    hasMore: false,
    loading: false,
    loadingMore: false,
    error: ''
  }
)

const emit = defineEmits<{
  (e: 'select', sha: string): void
  (e: 'load-more'): void
  (e: 'retry'): void
}>()

const wrapRef = ref<HTMLElement>()

const columns = [
  { title: 'SHA', dataIndex: 'sha', width: 78, customRender: ({ text }: any) => (text || '').slice(0, 7) },
  { title: '提交信息', dataIndex: 'message', ellipsis: true },
  { title: '作者', dataIndex: 'author', width: 90, ellipsis: true },
  {
    title: '时间',
    dataIndex: 'date',
    width: 118,
    customRender: ({ text }: any) => (text ? String(text).replace('T', ' ').slice(0, 16) : '')
  }
]

function customRow(record: CommitRow) {
  return {
    onClick: () => emit('select', record.sha),
    style: { cursor: 'pointer' }
  }
}

function rowClass(record: CommitRow) {
  return record.sha === props.selected ? 'commit-row-active' : ''
}

// 滚动到底自动加载下一页（加载按钮作为兜底）
function onScroll() {
  const el = wrapRef.value
  if (!el || !props.hasMore || props.loadingMore || props.loading) {
    return
  }
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 24) {
    emit('load-more')
  }
}
</script>

<style scoped lang="less">
.commit-table {
  max-height: 260px;
  overflow: auto;
  .footer {
    padding: 6px 0;
    text-align: center;
  }
  .done {
    color: #999;
    font-size: 12px;
  }
  .mb8 {
    margin-bottom: 8px;
  }
  :deep(.commit-row-active) td {
    background: #e6f4ff !important;
  }
}
</style>
