<template>
  <a-modal
    :open="open"
    title="选择审查结果"
    width="min(1040px, calc(100vw - 48px))"
    :footer="null"
    :mask-closable="false"
    destroy-on-close
    @cancel="close"
  >
    <div class="picker-toolbar">
      <a-select v-model:value="filterProject" allow-clear placeholder="全部项目" :options="projectOptions" @change="resetCandidates()" />
      <a-select v-model:value="filterKind" allow-clear placeholder="全部类型" :options="kindOptions" @change="resetCandidates()" />
      <span class="spacer" />
      <a-button size="small" :loading="loading" :disabled="loadingMore" @click="resetCandidates(false)">刷新结果</a-button>
    </div>
    <div class="search-row">
      <a-input v-model:value="query" allow-clear placeholder="筛选已加载结果的任务、项目、路径或版本" />
      <span class="muted loaded-count">已加载 {{ candidates.length }} 个</span>
    </div>
    <p class="muted search-note">关键词仅筛选已加载结果；项目和类型筛选覆盖全部候选。可跨筛选条件保留选择。</p>

    <div v-if="draft.size" class="selected-chips" aria-label="已选结果">
      <button v-for="item in draftList" :key="item.resultId" type="button" class="selected-chip"
        :title="`${taskLabel(item.taskName, item.taskId)} · ${pathTitle(item)}`"
        :aria-label="`移除 ${taskLabel(item.taskName, item.taskId)} ${pathTitle(item)}`"
        @click="draft.delete(item.resultId)">
        {{ taskLabel(item.taskName, item.taskId) }} · {{ fileName(item.path) }} <span aria-hidden="true">×</span>
      </button>
    </div>
    <p v-if="draft.size >= maxSelections" class="muted search-note">已达 {{ maxSelections }} 个结果上限，移除已选结果后可以继续选择。</p>
    <a-alert v-if="error" type="error" show-icon :message="error" class="load-error">
      <template #action><a-button size="small" :loading="loading || loadingMore" @click="retry">重试</a-button></template>
    </a-alert>

    <div class="result-list">
      <a-spin :spinning="loading">
        <a-empty v-if="!loading && !error && !filteredCandidates.length"
          :description="query ? '已加载结果中没有匹配项' : '没有可选的完整审查结果'" />
        <VirtualList v-if="filteredCandidates.length" ref="virtualListRef" :data="filteredCandidates"
          :height="360" :item-height="100" :full-height="false" item-key="resultId" @scroll="onScroll">
          <template #default="item">
            <div class="result-row" :class="{ selected: draft.has(item.resultId) }">
              <a-checkbox :checked="draft.has(item.resultId)" :disabled="selectionDisabled(item)"
                :aria-label="`选择 ${taskLabel(item.taskName, item.taskId)} ${pathTitle(item)}`" @change="toggle(item)" />
              <button type="button" class="result-main" :aria-pressed="draft.has(item.resultId)"
                :disabled="selectionDisabled(item)" @click="toggle(item, $event)">
                <strong :title="taskLabel(item.taskName, item.taskId)">{{ taskLabel(item.taskName, item.taskId) }}</strong>
                <span class="target" :title="pathTitle(item)">{{ item.kind === 'AGENT' ? 'Agent 调查结果' : fileName(item.path) }}</span>
                <span class="muted result-meta" :title="`${item.projectName || item.projectId} · ${kindText(item.kind)} · ${item.headSha || '—'} · ${resultTime(item.resultCreatedAt)}`">
                  {{ item.projectName || item.projectId }} · {{ kindText(item.kind) }} · {{ shortSha(item.headSha) }} · {{ resultTime(item.resultCreatedAt) }}
                </span>
              </button>
              <div class="result-status">
                <a-tag v-if="item.kind === 'AGENT'" :color="item.answerStatus === 'ANSWERED' ? 'green' : 'orange'">
                  {{ item.answerStatus === 'ANSWERED' ? '已回答' : '材料不足' }}
                </a-tag>
                <span v-else class="muted small">{{ item.kind === 'ISSUE' ? `${item.issueCount} 个问题` : '结果级反馈' }}</span>
                <span v-if="item.kind === 'AGENT' && item.unresolvedCount" class="muted small">未解决 {{ item.unresolvedCount }} 项</span>
                <span v-else class="muted small">{{ feedbackText(item) }}</span>
                <span class="muted small">{{ item.kind === 'AGENT' ? 'Agent 调查' : item.sourceKind === 'DIFF' ? '提交差异' : '文件内容' }}</span>
              </div>
              <a-button type="link" size="small" :disabled="item.kind !== 'AGENT' && !item.targetId" @click="emit('preview', item)">查看结果</a-button>
            </div>
          </template>
        </VirtualList>
        <div class="list-foot">
          <a-button v-if="hasMore" size="small" :loading="loadingMore" :disabled="loading || !!error" @click="loadMore">加载更多结果</a-button>
          <span v-else-if="!loading && !error" class="muted small">已加载完当前筛选下的结果</span>
        </div>
      </a-spin>
    </div>
    <div class="picker-foot">
      <span>已选 {{ draft.size }}/{{ maxSelections }} 个结果</span>
      <a-button size="small" :disabled="!draft.size" @click="draft.clear()">清空选择</a-button>
      <span class="spacer" />
      <a-button @click="close">取消</a-button>
      <a-button type="primary" @click="confirm">使用所选结果</a-button>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import VirtualList from 'ant-design-vue/es/vc-virtual-list'
import { reportsApi, type ProjectView, type ReportCandidate } from '@/api/v2'
import { fileName, taskLabel } from '@/utils/taskLabel'
import { feedbackText, kindText, pathTitle, resultTime, shortSha } from '@/utils/reportCandidates'

const props = defineProps<{ open: boolean; selected: ReportCandidate[]; projects: ProjectView[] }>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [items: ReportCandidate[]]
  preview: [item: ReportCandidate]
}>()
const maxSelections = 200
const draft = reactive(new Map<string, ReportCandidate>())
const draftList = computed(() => [...draft.values()])
const query = ref('')
const filterProject = ref<string | undefined>()
const filterKind = ref<string | undefined>()
const candidates = ref<ReportCandidate[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const virtualListRef = ref<{ scrollTo: (offset: number) => void } | null>(null)
let epoch = 0
let disposed = false
let failedMore = false
const kindOptions = [
  { value: 'ISSUE', label: '问题审查' }, { value: 'PATTERN', label: '设计模式' },
  { value: 'COUPLING', label: '耦合度' }, { value: 'AGENT', label: 'Agent' }
]
const projectOptions = computed(() => props.projects.map(project => ({ value: project.id, label: project.name })))
const filteredCandidates = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  return !keyword ? candidates.value : candidates.value.filter(item =>
    [taskLabel(item.taskName, item.taskId), item.projectName, item.projectId, item.path, item.oldPath,
      item.headSha, item.baseSha, kindText(item.kind)]
      .some(value => String(value || '').toLocaleLowerCase().includes(keyword)))
})

function invalidate() {
  epoch++
  loading.value = false
  loadingMore.value = false
}
watch(() => props.open, open => {
  if (!open) { invalidate(); return }
  query.value = ''
  draft.clear()
  props.selected.forEach(item => draft.set(item.resultId, item))
  void resetCandidates()
}, { immediate: true, flush: 'sync' })
watch(query, () => virtualListRef.value?.scrollTo(0))
onBeforeUnmount(() => { disposed = true; invalidate() })

async function resetCandidates(clear = true) {
  if (!props.open || disposed) return
  if (clear) {
    candidates.value = []
    nextCursor.value = null
    hasMore.value = false
  }
  virtualListRef.value?.scrollTo(0)
  await readPage(false)
}

async function readPage(append: boolean) {
  if (!props.open || disposed) return
  const cursor = append ? nextCursor.value : null
  if (append && (!cursor || loading.value || loadingMore.value)) return
  const requestEpoch = append ? epoch : ++epoch
  if (append) loadingMore.value = true
  else { loading.value = true; loadingMore.value = false }
  error.value = ''
  try {
    const page = await reportsApi.candidates({
      projectId: filterProject.value, kind: filterKind.value,
      ...(cursor ? { cursor } : {}), limit: 20
    })
    if (disposed || !props.open || requestEpoch !== epoch) return
    candidates.value = [...new Map([
      ...(append ? candidates.value : []), ...page.items
    ].map(item => [item.resultId, item])).values()]
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    failedMore = false
  } catch (failure) {
    if (disposed || !props.open || requestEpoch !== epoch) return
    failedMore = append
    error.value = (failure as Error).message || '可选审查结果读取失败，请重试。'
  } finally {
    if (requestEpoch === epoch) { loading.value = false; loadingMore.value = false }
  }
}

async function loadMore() {
  if (!hasMore.value || error.value) return
  await readPage(true)
}
async function retry() { await readPage(failedMore) }
function selectionDisabled(item: ReportCandidate) { return !draft.has(item.resultId) && draft.size >= maxSelections }
function toggle(item: ReportCandidate, event?: MouseEvent) {
  if (event && event.detail > 1) return
  if (draft.has(item.resultId)) draft.delete(item.resultId)
  else if (!selectionDisabled(item)) draft.set(item.resultId, item)
}
function close() { invalidate(); emit('update:open', false) }
function confirm() {
  emit('confirm', [...draft.values()])
  close()
}
function onScroll(event: Event) {
  const target = event.target as HTMLElement | null
  if (target && target.scrollTop + target.clientHeight >= target.scrollHeight - 32) void loadMore()
}
</script>

<style scoped lang="less">
.picker-toolbar, .search-row, .picker-foot, .result-row { display: flex; align-items: center; gap: 12px; }
.picker-toolbar { flex-wrap: wrap; }
.picker-toolbar :deep(.ant-select) { min-width: 160px; }
.search-row { margin-top: 12px; }
.search-row :deep(.ant-input-affix-wrapper) { flex: 1; }
.loaded-count { flex-shrink: 0; }
.search-note { margin: 8px 0 12px; font-size: 12px; }
.selected-chips { display: flex; flex-wrap: wrap; gap: 6px; max-height: 78px; overflow: auto; margin-bottom: 12px; }
.selected-chip { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border: 1px solid #c7ddff; border-radius: 6px; background: #eff6ff; color: #245bb3; padding: 3px 8px; cursor: pointer; }
.load-error { margin-bottom: 12px; }
.result-list { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: #fff; }
.result-row { height: 100px; padding: 10px 14px; border-bottom: 1px solid #edf1f6; }
.result-row:hover { background: #f7faff; }
.result-row.selected { background: var(--soft, #eff6ff); box-shadow: inset 3px 0 0 var(--blue, #2563eb); }
.result-main { display: grid; gap: 3px; flex: 1; min-width: 0; text-align: left; border: 0; padding: 0; background: transparent; color: inherit; cursor: pointer; }
.result-main:disabled { cursor: default; }
.result-main strong, .result-main span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.target { font-size: 13px; }
.result-meta { font-size: 12px; }
.result-status { flex: 0 0 104px; display: grid; gap: 2px; }
.result-status :deep(.ant-tag) { width: fit-content; margin: 0; }
.list-foot { padding: 10px; text-align: center; }
.picker-foot { flex-wrap: wrap; margin-top: 14px; }
.spacer { flex: 1; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
@media (max-width: 650px) {
  .result-row { display: grid; grid-template-columns: 20px minmax(0, 1fr) 70px; column-gap: 6px; row-gap: 0; padding: 10px 8px; }
  .result-main { grid-column: 2; grid-row: 1 / 3; }
  .result-row > :first-child { grid-column: 1; grid-row: 1 / 3; }
  .result-status { grid-column: 3; grid-row: 1; min-width: 0; }
  .result-row > :last-child { grid-column: 3; grid-row: 2; padding: 0; }
  .loaded-count { display: none; }
  .picker-toolbar :deep(.ant-select) { flex: 1; min-width: 120px; }
}
</style>
