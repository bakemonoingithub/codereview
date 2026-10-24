<template>
  <a-modal
    :open="open"
    title="选择审查提交"
    width="min(860px, calc(100vw - 48px))"
    :footer="null"
    destroy-on-close
    @cancel="close"
  >
    <div class="picker-head">
      <div>
        <strong>分支 {{ branch || '—' }}</strong>
        <span class="muted">提交按时间从新到旧排列</span>
      </div>
      <a-button size="small" :loading="loading" @click="$emit('refresh')">刷新最近提交</a-button>
    </div>

    <a-alert v-if="error" type="error" show-icon :message="error" class="mb12">
      <template #action>
        <a-button size="small" :loading="loading || loadingMore" @click="$emit('retry')">重试</a-button>
      </template>
    </a-alert>

    <div class="picker-toolbar">
      <a-input
        v-model:value="query"
        allow-clear
        placeholder="筛选已加载提交的 SHA、说明或作者"
      />
      <span class="muted">已加载最近 {{ commits.length }} 条</span>
    </div>
    <p v-if="query && hasMore" class="muted search-note">
      搜索范围只包含已加载内容；未找到时可继续加载更早提交。
    </p>

    <div ref="listRef" class="commit-list" @scroll.passive="onScroll">
      <a-spin :spinning="loading && !commits.length">
        <a-empty
          v-if="!loading && !filteredCommits.length"
          :description="query ? '已加载提交中没有匹配项' : '该分支没有可读提交'"
        />
        <button
          v-for="commit in filteredCommits"
          :key="commit.sha"
          type="button"
          class="commit-row"
          :class="{ selected: draftSha === commit.sha }"
          @click="draftSha = commit.sha"
          @dblclick="confirm"
        >
          <span class="commit-main">
            <code>{{ commit.sha.slice(0, 10) }}</code>
            <strong :title="subject(commit.message)">{{ subject(commit.message) || '无提交说明' }}</strong>
          </span>
          <span class="commit-meta">
            <span>{{ commit.author || '未知作者' }}</span>
            <span>{{ formatDate(commit.date) || '时间未知' }}</span>
          </span>
        </button>

        <div v-if="commits.length" class="list-foot">
          <a-button
            v-if="hasMore"
            size="small"
            :loading="loadingMore"
            :disabled="loading"
            @click="$emit('load-more')"
          >
            加载更早提交
          </a-button>
          <span v-else class="muted">已到该分支最早提交</span>
        </div>
      </a-spin>
    </div>

    <div class="picker-foot">
      <span v-if="draftSha" class="selected-summary">
        已选 <code>{{ draftSha.slice(0, 10) }}</code>
      </span>
      <span v-else class="muted">请选择一条提交；双击提交也可直接确认。</span>
      <span class="spacer" />
      <a-button @click="close">取消</a-button>
      <a-button type="primary" :disabled="!draftSha" @click="confirm">使用这个提交</a-button>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CommitInfo } from '@/api/v2'

const props = withDefaults(defineProps<{
  open: boolean
  branch?: string
  commits: CommitInfo[]
  selectedSha?: string
  hasMore?: boolean
  loading?: boolean
  loadingMore?: boolean
  error?: string
}>(), {
  branch: '',
  selectedSha: '',
  hasMore: false,
  loading: false,
  loadingMore: false,
  error: ''
})

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'confirm', sha: string): void
  (event: 'load-more'): void
  (event: 'refresh'): void
  (event: 'retry'): void
}>()

const query = ref('')
const draftSha = ref('')
const listRef = ref<HTMLElement | null>(null)

const filteredCommits = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  if (!keyword) return props.commits
  return props.commits.filter((commit) =>
    [commit.sha, commit.message, commit.author]
      .some((value) => String(value || '').toLocaleLowerCase().includes(keyword))
  )
})

watch(() => props.open, (open) => {
  if (!open) return
  query.value = ''
  draftSha.value = props.selectedSha
})

watch(() => props.selectedSha, (sha) => {
  if (props.open) draftSha.value = sha
})

function close() {
  emit('update:open', false)
}

function confirm() {
  if (!draftSha.value) return
  emit('confirm', draftSha.value)
}

function subject(message: string): string {
  return String(message || '').split('\n')[0].trim()
}

function formatDate(date: string): string {
  return date ? String(date).replace('T', ' ').replace('Z', '').slice(0, 16) : ''
}

function onScroll() {
  const element = listRef.value
  if (!element || !props.hasMore || props.loading || props.loadingMore) return
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 32) {
    emit('load-more')
  }
}
</script>

<style scoped lang="less">
.picker-head,
.picker-toolbar,
.picker-foot,
.commit-main,
.commit-meta {
  display: flex;
  align-items: center;
}
.picker-head {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.picker-head > div {
  display: grid;
  gap: 2px;
}
.picker-toolbar {
  gap: 12px;
}
.picker-toolbar :deep(.ant-input-affix-wrapper) {
  flex: 1;
}
.search-note {
  margin: 8px 0 0;
  font-size: 12px;
}
.commit-list {
  margin-top: 12px;
  max-height: min(430px, calc(100vh - 330px));
  min-height: 180px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}
.commit-row {
  display: flex;
  width: 100%;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding: 11px 14px;
  border: 0;
  border-bottom: 1px solid #edf1f6;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.commit-row:hover {
  background: #f7faff;
}
.commit-row.selected {
  background: var(--soft);
  box-shadow: inset 3px 0 0 var(--blue);
}
.commit-main {
  min-width: 0;
  gap: 12px;
}
.commit-main code {
  flex: 0 0 auto;
}
.commit-main strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.commit-meta {
  flex: 0 0 auto;
  gap: 12px;
  color: var(--muted);
  font-size: 12px;
}
.list-foot {
  padding: 12px;
  text-align: center;
}
.picker-foot {
  gap: 10px;
  margin-top: 14px;
}
.selected-summary {
  min-width: 0;
}
.spacer {
  flex: 1;
}
.muted {
  color: var(--muted);
}
.mb12 {
  margin-bottom: 12px;
}
@media (max-width: 700px) {
  .commit-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
  }
  .commit-meta {
    padding-left: 0;
  }
}
</style>
