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
        <span class="muted">当前分支的提交历史</span>
      </div>
      <a-button size="small" :loading="loading" :disabled="loadingMore" @click="$emit('refresh')">刷新最近提交</a-button>
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
        placeholder="筛选已加载提交，或粘贴完整 SHA 定位"
        @press-enter="lookup"
      />
      <a-button :disabled="!isFullSha" :loading="lookupLoading" @click="lookup">按 SHA 定位</a-button>
      <span class="muted loaded-count">已加载 {{ commits.length }} 条</span>
    </div>
    <p class="muted search-note">
      关键词仅筛选已加载提交的 SHA、说明或作者；完整 40 位 SHA 可直接定位当前仓库中的提交。<template v-if="multiple">点击多条提交即可连续勾选。</template>
    </p>
    <p v-if="multiple && maxSelections && draftShas.length >= maxSelections" class="muted search-note">
      已达选择上限；移除已选提交后可以继续选择。
    </p>
    <div v-if="displayedSelectedShas.length" class="selected-commits" aria-label="已选择的提交">
      <a-tag
        v-for="sha in displayedSelectedShas"
        :key="sha"
        closable
        :title="sha"
        @close="removeDraftCommit(sha)"
      >
        <a class="selected-commit-link" href="#" @click.prevent.stop="locateDraftCommit(sha)">
          {{ sha.slice(0, 10) }}
        </a>
      </a-tag>
    </div>

    <a-alert v-if="lookupError" type="error" show-icon :message="lookupError" class="lookup-result" />
    <div v-for="entry in separateCommits" :key="entry.commit.sha" class="lookup-result">
      <p class="muted search-note">{{ entry.label }}</p>
      <button
        type="button"
        class="commit-row located-row"
        :id="locatedRowId(entry.commit.sha)"
        :class="{ selected: isSelected(entry.commit.sha) }"
        :aria-pressed="isSelected(entry.commit.sha)"
        @click="selectCommit(entry.commit.sha, $event)"
        @dblclick="onDoubleClick"
      >
        <span class="commit-main">
          <code>{{ entry.commit.sha.slice(0, 10) }}</code>
          <strong :title="subject(entry.commit.message)">{{ subject(entry.commit.message) || '无提交说明' }}</strong>
        </span>
        <span class="commit-meta">
          <span>{{ entry.commit.author || '未知作者' }}</span>
          <span>{{ formatDate(entry.commit.date) || '时间未知' }}</span>
        </span>
      </button>
    </div>

    <div ref="listRef" class="commit-list" @scroll.passive="onScroll">
      <a-spin :spinning="loading && !commits.length">
        <a-empty
          v-if="!loading && !filteredCommits.length"
          :description="query ? '已加载提交中没有匹配项' : '该分支没有可读提交'"
        />
        <VirtualList
          v-if="filteredCommits.length"
          ref="virtualListRef"
          :data="filteredCommits"
          :height="320"
          :item-height="64"
          :full-height="false"
          item-key="sha"
          @scroll="onScroll"
        >
          <template #default="commit">
            <button
              type="button"
              class="commit-row"
              :class="{ selected: isSelected(commit.sha) }"
              :aria-pressed="isSelected(commit.sha)"
              @click="selectCommit(commit.sha, $event)"
              @dblclick="onDoubleClick"
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
          </template>
        </VirtualList>

        <div v-if="commits.length" class="list-foot">
          <a-button
            v-if="hasMore"
            size="small"
            :loading="loadingMore"
            :disabled="loading || !!error"
            @click="$emit('load-more')"
          >
            加载更早提交
          </a-button>
          <span v-else-if="!error && !loading && !loadingMore" class="muted">已加载完该分支提交历史</span>
        </div>
      </a-spin>
    </div>

    <div class="picker-foot">
      <span v-if="multiple" class="selected-summary">
        已选 {{ draftShas.length }}<template v-if="maxSelections">/{{ maxSelections }}</template> 条提交
      </span>
      <span v-else-if="draftSha" class="selected-summary">
        已选 <code>{{ draftSha.slice(0, 10) }}</code>
      </span>
      <span v-else class="muted">请选择一条提交；双击提交也可直接确认。</span>
      <span class="spacer" />
      <a-button @click="close">取消</a-button>
      <a-button v-if="multiple" type="primary" :disabled="!draftShas.length" @click="confirm">
        使用所选提交
      </a-button>
      <a-button v-else type="primary" :disabled="!draftSha" @click="confirm">使用这个提交</a-button>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import VirtualList from 'ant-design-vue/es/vc-virtual-list'
import type { CommitInfo } from '@/api/v2'

const props = withDefaults(defineProps<{
  open: boolean
  branch?: string
  commits: CommitInfo[]
  multiple?: boolean
  selectedShas?: string[]
  maxSelections?: number
  selectedSha?: string
  selectedCommit?: CommitInfo | null
  hasMore?: boolean
  loading?: boolean
  loadingMore?: boolean
  error?: string
  locatedCommit?: CommitInfo | null
  lookupLoading?: boolean
  lookupError?: string
}>(), {
  branch: '',
  multiple: false,
  selectedShas: () => [],
  maxSelections: 0,
  selectedSha: '',
  selectedCommit: null,
  hasMore: false,
  loading: false,
  loadingMore: false,
  error: '',
  locatedCommit: null,
  lookupLoading: false,
  lookupError: ''
})

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'confirm', sha: string): void
  (event: 'confirm-many', shas: string[]): void
  (event: 'load-more'): void
  (event: 'refresh'): void
  (event: 'retry'): void
  (event: 'lookup', sha: string): void
  (event: 'clear-lookup'): void
}>()

const query = ref('')
const draftSha = ref('')
const draftShas = ref<string[]>([])
const pendingLocateSha = ref('')
const listRef = ref<HTMLElement | null>(null)
const virtualListRef = ref<{ scrollTo: (top: number) => void } | null>(null)
const isFullSha = computed(() => /^[a-f0-9]{40}$/i.test(query.value.trim()))
const separateCommits = computed(() => {
  const entries: { label: string; commit: CommitInfo }[] = []
  if (props.locatedCommit) entries.push({
    label: 'SHA 定位结果（当前仓库，未核对是否属于所选分支）',
    commit: props.locatedCommit
  })
  if (props.selectedCommit?.sha === props.selectedSha
    && !props.commits.some((commit) => commit.sha === props.selectedSha)
    && props.locatedCommit?.sha !== props.selectedSha) entries.push({
    label: '当前已选（不在已加载的分支历史中）',
    commit: props.selectedCommit
  })
  return entries
})
const displayedSelectedShas = computed(() =>
  props.multiple ? draftShas.value : (draftSha.value ? [draftSha.value] : [])
)

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
  draftShas.value = [...props.selectedShas]
}, { immediate: true })

watch(query, () => {
  virtualListRef.value?.scrollTo(0)
  emit('clear-lookup')
})

watch(() => props.commits, (commits, previous) => {
  if (previous?.length && (commits.length <= previous.length || commits[0]?.sha !== previous[0]?.sha)) {
    virtualListRef.value?.scrollTo(0)
  }
})

watch(() => props.branch, () => {
  query.value = ''
  draftSha.value = props.selectedSha
  draftShas.value = [...props.selectedShas]
})

watch(() => props.selectedSha, (sha) => {
  if (props.open) draftSha.value = sha
})

watch(() => props.locatedCommit?.sha, async (sha) => {
  if (!sha || pendingLocateSha.value !== sha) return
  await nextTick()
  document.getElementById(locatedRowId(sha))?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  pendingLocateSha.value = ''
})

function close() {
  emit('update:open', false)
}

function confirm() {
  if (props.multiple) {
    if (draftShas.value.length) emit('confirm-many', [...draftShas.value])
    return
  }
  if (!draftSha.value) return
  emit('confirm', draftSha.value)
}

function isSelected(sha: string): boolean {
  return props.multiple ? draftShas.value.includes(sha) : draftSha.value === sha
}

function selectCommit(sha: string, event?: MouseEvent) {
  if (props.multiple && event && event.detail > 1) return
  if (!props.multiple) {
    draftSha.value = sha
    return
  }
  const index = draftShas.value.indexOf(sha)
  if (index >= 0) {
    draftShas.value.splice(index, 1)
  } else if (!props.maxSelections || draftShas.value.length < props.maxSelections) {
    draftShas.value.push(sha)
  }
}

function removeDraftCommit(sha: string) {
  if (props.multiple) {
    draftShas.value = draftShas.value.filter((selected) => selected !== sha)
  } else if (draftSha.value === sha) {
    draftSha.value = ''
  }
}

async function locateDraftCommit(sha: string) {
  const index = props.commits.findIndex((commit) => commit.sha === sha)
  if (index >= 0) {
    query.value = ''
    await nextTick()
    virtualListRef.value?.scrollTo(index * 64)
    return
  }
  if (props.locatedCommit?.sha === sha) {
    document.getElementById(locatedRowId(sha))?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    return
  }
  if (document.getElementById(locatedRowId(sha))) {
    document.getElementById(locatedRowId(sha))?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    return
  }
  if (/^[a-f0-9]{40}$/i.test(sha)) {
    pendingLocateSha.value = sha
    emit('lookup', sha.toLowerCase())
  }
}

function locatedRowId(sha: string): string {
  return `located-commit-${sha}`
}

function onDoubleClick() {
  if (!props.multiple) confirm()
}

function lookup() {
  if (isFullSha.value && !props.lookupLoading) emit('lookup', query.value.trim().toLowerCase())
}

function subject(message: string): string {
  return String(message || '').split('\n')[0].trim()
}

function formatDate(date: string): string {
  return date ? String(date).replace('T', ' ').replace('Z', '').slice(0, 16) : ''
}

function onScroll(event?: Event) {
  const element = event?.target instanceof HTMLElement ? event.target : listRef.value
  if (!element || !props.open || !props.hasMore || props.loading || props.loadingMore || props.error) return
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
.selected-commits {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 76px;
  margin: 0 0 12px;
  overflow-y: auto;
}
.selected-commits :deep(.ant-tag) {
  margin-inline-end: 0;
  font-family: var(--font-mono);
}
.selected-commit-link {
  color: var(--blue);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.loaded-count {
  flex: 0 0 auto;
}
.picker-toolbar :deep(.ant-input-affix-wrapper) {
  flex: 1;
}
.search-note {
  margin: 8px 0 0;
  font-size: 12px;
}
.commit-meta span:first-child {
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lookup-result {
  margin-top: 12px;
}
.located-row {
  margin-top: 6px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.commit-list {
  margin-top: 12px;
  min-height: 180px;
  overflow: hidden;
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
  min-height: 64px;
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
