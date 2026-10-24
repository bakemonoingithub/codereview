<template>
  <div>
    <div class="page-head">
      <div>
        <h1>代码浏览</h1>
        <p class="muted">浏览固定版本的代码，选择文件后发起专项审查。</p>
      </div>
      <a-button type="primary" :disabled="!selected.length || treeLoading || !!error" @click="launchSelected">
        审查已选文件（{{ selected.length }}）
      </a-button>
    </div>

    <LoadErrorAlert :message="error" @retry="retryBrowser" />

    <a-alert v-if="truncated" type="warning" show-icon
      message="宿主返回的文件清单不完整，只能浏览和选择当前已列出的文件。" class="mb16" />
    <a-card class="browser-card" :bordered="true">
      <div class="browser-toolbar">
        <div class="filters">
          <a-select
            v-model:value="projectId"
            placeholder="选择项目"
            style="width: 220px"
            :options="projectOptions"
            @change="onProjectChange"
          />
          <a-select
            v-model:value="branch"
            placeholder="分支"
            style="width: 180px"
            :loading="branchesLoading"
            :options="branchOptions"
            @change="onBranchChange"
          />
          <a-radio-group v-model:value="mode" button-style="solid" @change="onModeChange">
            <a-radio-button value="FILES">文件内容</a-radio-button>
            <a-radio-button value="DIFF">提交差异</a-radio-button>
          </a-radio-group>
          <a-button v-if="mode === 'DIFF'" :disabled="!branch" @click="openCommitPicker">
            {{ commitSha ? '更换提交' : '选择提交' }}
          </a-button>
          <span class="spacer" />
          <code v-if="version && mode === 'FILES'">{{ version.slice(0, 7) }}</code>
          <span v-if="version && mode === 'FILES'" class="muted small">固定版本</span>
        </div>
        <div v-if="mode === 'DIFF' && commitSha" class="commit-selection-summary">
          <code>{{ commitSha.slice(0, 7) }}</code>
          <span :title="selectedCommitLabel">{{ selectedCommitMessage || '提交说明暂不可用' }}</span>
          <span class="muted small">固定版本</span>
        </div>
      </div>

      <div class="browser-grid">
        <aside class="tree">
          <a-input
            v-model:value="query"
            allow-clear
            size="small"
            placeholder="筛选文件路径"
            class="mb8"
          />
          <a-spin :spinning="treeLoading">
            <a-empty v-if="!treeLoading && !treeData.length" description="没有可浏览的文件" />
            <a-tree
              v-else
              checkable
              :tree-data="treeData"
              :checked-keys="selected"
              :expanded-keys="expandedKeys"
              @check="onCheck"
              @select="onSelect"
              @expand="onExpand"
            >
              <template #title="{ dataRef }">
                <span
                  class="tree-label"
                  :class="{ 'file-link': dataRef.isLeaf }"
                  :title="dataRef.path || dataRef.key || dataRef.title"
                >{{ dataRef.title }}</span>
                <a-tag v-if="dataRef.status" class="status-tag" color="blue">
                  {{ statusText(dataRef.status) }}
                </a-tag>
              </template>
            </a-tree>
          </a-spin>
        </aside>

        <section ref="codePane" class="code-pane">
          <div class="code-head">
            <strong>{{ previewPath || '选择文件查看' }}</strong>
            <span class="spacer" />
            <span v-if="mode === 'DIFF'" class="muted small">提交差异</span>
            <span v-else class="muted small">完整原文</span>
          </div>
          <a-spin :spinning="previewLoading">
            <a-alert v-if="previewError" type="error" show-icon :message="previewError" />
            <template v-else-if="previewPath">
              <div v-if="mode === 'DIFF'" class="filters">
                <a-button size="small" @click="previewMode = 'changes'">改动</a-button>
                <a-button size="small" @click="previewMode = 'before'">修改前原文</a-button>
                <a-button size="small" @click="previewMode = 'after'">修改后原文</a-button>
              </div>
              <DiffViewer
                v-if="mode === 'DIFF' && previewMode === 'changes'"
                :path="previewPath"
                :patch="previewDiff?.patch || null"
                :status="previewDiff?.status"
                highlight
                empty-text="宿主未提供可显示的差异，请阅读修改前后原文"
              />
              <CodeViewer
                v-else-if="previewSource !== null"
                :text="previewSource"
                :path="previewPath"
              />
              <pre v-else-if="previewNote" class="code">{{ previewNote }}</pre>
            </template>
            <div v-else class="empty">从左侧选择文件查看原文或改动。</div>
          </a-spin>
        </section>
      </div>
    </a-card>

    <CommitPickerModal
      v-model:open="commitPickerOpen"
      :branch="branch"
      :commits="commits"
      :selected-sha="commitSha"
      :selected-commit="selectedCommitInfo"
      :has-more="commitsHasMore"
      :loading="commitsLoading"
      :loading-more="false"
      :error="pickerCommitsError"
      :located-commit="locatedCommit"
      :lookup-loading="commitLookupLoading"
      :lookup-error="commitLookupError"
      @confirm="confirmCommit"
      @load-more="loadMoreCommits"
      @refresh="refreshCommits"
      @retry="refreshCommits"
      @lookup="locateCommit"
      @clear-lookup="clearCommitLookup"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import CodeViewer from '@/components/CodeViewer.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import CommitPickerModal from '@/components/CommitPickerModal.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import {
  projectsApi,
  repositoryApi,
  type CommitInfo,
  type DiffSides,
  type ProjectView
} from '@/api/v2'
import {
  buildPathTree,
  checkedFileKeys,
  filterPathTree,
  leafKeys,
  mergeCheckedSelection,
  type FileTreeEntry,
  type FileTreeNode
} from '@/utils/pathTree'

const router = useRouter()
const projects = ref<ProjectView[]>([])
const projectId = ref('')
const branches = ref<string[]>([])
const branch = ref('')
const mode = ref<'FILES' | 'DIFF'>('FILES')
const commits = ref<CommitInfo[]>([])
const commitSha = ref('')
const confirmedCommit = ref<CommitInfo | null>(null)
const commitPickerOpen = ref(false)
const locatedCommit = ref<CommitInfo | null>(null)
const commitLookupLoading = ref(false)
const commitLookupError = ref('')
const pickerCommitsError = ref('')
const version = ref('')
const entries = ref<FileTreeEntry[]>([])
const tree = ref<FileTreeNode[]>([])
const selected = ref<string[]>([])
const expandedKeys = ref<string[]>([])
const query = ref('')
const codePane = ref<HTMLElement | null>(null)
const previewPath = ref('')
const previewMode = ref<'changes' | 'before' | 'after'>('changes')
const previewContent = ref('')
const previewDiff = ref<DiffSides | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const treeLoading = ref(false)
const branchesLoading = ref(false)
const commitsLoading = ref(false)
const error = ref('')
let epoch = 0
let previewEpoch = 0
let branchEpoch = 0
let commitsEpoch = 0
let commitLookupEpoch = 0
let disposed = false
const truncated = ref(false)
const commitsHasMore = ref(false)
let commitsPage = 0
const sourceKey = () => JSON.stringify([projectId.value, branch.value, mode.value])

watch(() => [projectId.value, branch.value, mode.value, commitSha.value], (next, previous) => {
  epoch++
  previewEpoch++
  entries.value = []
  tree.value = []
  version.value = ''
  selected.value = []
  previewPath.value = ''
  previewContent.value = ''
  previewDiff.value = null
  previewError.value = ''
  previewLoading.value = false
  treeLoading.value = false
  truncated.value = false
  if (next.slice(0, 3).some((value, index) => value !== previous?.[index])) {
    commitsEpoch++
    commits.value = []
    commitsPage = 0
    commitsHasMore.value = false
    pickerCommitsError.value = ''
    confirmedCommit.value = null
    clearCommitLookup()
  }
}, { flush: 'sync' })

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: project.name }))
)
const branchOptions = computed(() => branches.value.map((item) => ({ value: item, label: item })))
const selectedCommitInfo = computed(() =>
  commits.value.find((commit) => commit.sha === commitSha.value) ||
  (locatedCommit.value?.sha === commitSha.value ? locatedCommit.value : null) ||
  (confirmedCommit.value?.sha === commitSha.value ? confirmedCommit.value : null)
)
const selectedCommitLabel = computed(() => {
  const commit = selectedCommitInfo.value
  if (commit) return `${commit.sha.slice(0, 7)} · ${(commit.message || '').split('\n')[0]}`
  return commitSha.value ? `${commitSha.value.slice(0, 7)} · 已选择提交` : ''
})
const selectedCommitMessage = computed(() =>
  (selectedCommitInfo.value?.message || '').split('\n')[0].trim()
)
const treeData = computed(() => filterPathTree(tree.value, query.value))

const previewSource = computed<string | null>(() => {
  if (!previewPath.value) return null
  if (mode.value === 'FILES') return previewContent.value
  if (previewMode.value !== 'before' && previewMode.value !== 'after') return null
  const side = previewMode.value === 'before' ? previewDiff.value?.before : previewDiff.value?.after
  if (!side || side.status !== 'PRESENT') return null
  return side.content || ''
})
const previewNote = computed(() => {
  if (mode.value !== 'DIFF' || (previewMode.value !== 'before' && previewMode.value !== 'after')) return ''
  const side = previewMode.value === 'before' ? previewDiff.value?.before : previewDiff.value?.after
  if (!side || side.status === 'PRESENT') return ''
  return side.status === 'MISSING'
    ? `该侧文件不存在（${side.reason || '未知原因'}）`
    : `该侧原文不可读取（${side.reason || '读取失败'}）`
})

onMounted(async () => {
  try {
    const page = await projectsApi.list({ limit: 100 })
    if (disposed) return
    projects.value = page.items
    let saved: any = null
    try { saved = JSON.parse(sessionStorage.getItem('code-browser-scene') || 'null') } catch { /* 无现场 */ }
    if (saved && projects.value.some(project => project.id === saved.projectId)
      && typeof saved.branch === 'string' && ['FILES', 'DIFF'].includes(saved.mode)) {
      projectId.value = saved.projectId
      branch.value = saved.branch
      mode.value = saved.mode
      commitSha.value = typeof saved.commitSha === 'string' ? saved.commitSha : ''
      query.value = typeof saved.query === 'string' ? saved.query : ''
      const sceneIdentity = () => JSON.stringify([sourceKey(), commitSha.value])
      const sceneKey = sceneIdentity()
      if (!await loadBranches() || disposed) return
      if (!branches.value.includes(branch.value)) {
        const fallback = branches.value[0] || ''
        if (!fallback) {
          error.value = '该项目没有可选择的分支'
          return
        }
        branch.value = fallback
        message.warning('原分支已不存在，已改用 ' + fallback)
      } else if (sceneKey !== sceneIdentity()) {
        return
      }
      await reload(saved.mode === 'FILES' ? saved.version : undefined)
      if (disposed || error.value || sceneKey !== sceneIdentity()) return
      const available = new Set(entries.value.map(entry => entry.path))
      selected.value = Array.isArray(saved.selected) ? saved.selected.filter((path: unknown) =>
        typeof path === 'string' && available.has(path)) : []
      if (saved.selected?.length !== selected.value.length) {
        message.warning('部分原选择已不可用，已移除，请核对范围')
      }
      if (Array.isArray(saved.expandedKeys)) expandedKeys.value = saved.expandedKeys.filter((key: unknown) => typeof key === 'string')
      if (available.has(saved.previewPath)) {
        await onSelect([], { node: { isLeaf: true, path: saved.previewPath } as FileTreeNode })
      }
    } else if (projects.value.length) {
      projectId.value = projects.value[0].id
      await onProjectChange()
    }
  } catch (failure) {
    if (!disposed) error.value = (failure as Error).message || '项目加载失败'
  }
})

function saveScene() {
  try {
    sessionStorage.setItem('code-browser-scene', JSON.stringify({
      projectId: projectId.value, branch: branch.value, mode: mode.value,
      commitSha: commitSha.value, version: version.value, query: query.value,
      selected: selected.value, expandedKeys: expandedKeys.value, previewPath: previewPath.value
    }))
  } catch { /* 存储不可用不阻断浏览 */ }
}
onBeforeUnmount(() => { saveScene(); disposed = true; epoch++; previewEpoch++; branchEpoch++; commitsEpoch++ })

async function onProjectChange() {
  const project = projects.value.find((item) => item.id === projectId.value)
  const preferred = project?.currentBranch || ''
  commitSha.value = ''
  const id = projectId.value
  if (!await loadBranches()) return
  if (disposed || id !== projectId.value) return
  const next = preferred && branches.value.includes(preferred)
    ? preferred
    : (branches.value[0] || '')
  if (!next) {
    branch.value = ''
    error.value = '该项目没有可选择的分支'
    return
  }
  branch.value = next
  await reload()
}

async function loadBranches() {
  if (!projectId.value) return false
  const request = ++branchEpoch
  const id = projectId.value
  branches.value = []
  branchesLoading.value = true
  try {
    const loaded = await repositoryApi.branches(id)
    if (request !== branchEpoch || id !== projectId.value || disposed) return false
    branches.value = loaded
    return true
  } catch (failure) {
    if (request === branchEpoch && id === projectId.value && !disposed) {
      error.value = (failure as Error).message || '分支列表读取失败'
    }
    return false
  } finally {
    if (request === branchEpoch) branchesLoading.value = false
  }
}

async function retryBrowser() {
  if (!await loadBranches()) return
  await reload()
}

async function onBranchChange() { commitSha.value = ''; confirmedCommit.value = null; await reload() }
async function onModeChange() { commitSha.value = ''; confirmedCommit.value = null; await reload() }
async function openCommitPicker() {
  commitPickerOpen.value = true
  pickerCommitsError.value = ''
  if (!commits.value.length) {
    try { await loadCommits(1) }
    catch (failure) { pickerCommitsError.value = (failure as Error).message || '提交列表读取失败' }
  }
}

async function confirmCommit(sha: string) {
  commitPickerOpen.value = false
  if (commitSha.value === sha) return
  confirmedCommit.value = commits.value.find((commit) => commit.sha === sha) ||
    (locatedCommit.value?.sha === sha ? locatedCommit.value : null)
  locatedCommit.value = null
  commitSha.value = sha
  await reload()
}

async function refreshCommits() {
  pickerCommitsError.value = ''
  try {
    if (await loadCommits(1)) error.value = ''
  } catch (failure) {
    pickerCommitsError.value = (failure as Error).message || '提交列表读取失败'
  }
}

async function loadMoreCommits() {
  if (commitsLoading.value || !commitsHasMore.value) return
  pickerCommitsError.value = ''
  try { await loadCommits(commitsPage + 1) }
  catch (failure) { pickerCommitsError.value = (failure as Error).message || '更多提交读取失败' }
}

async function loadCommits(page: number) {
  const request = ++commitsEpoch
  const key = sourceKey()
  commitsLoading.value = true
  try {
    const loaded = await repositoryApi.commits(projectId.value, branch.value, page, 30)
    if (request !== commitsEpoch || key !== sourceKey() || disposed) return false
    commits.value = page === 1 ? loaded.commits : [...commits.value, ...loaded.commits]
    commitsPage = page
    commitsHasMore.value = loaded.hasMore
    pickerCommitsError.value = ''
    return true
  } catch (failure) {
    if (request !== commitsEpoch || key !== sourceKey() || disposed) return false
    throw failure
  } finally {
    if (request === commitsEpoch) commitsLoading.value = false
  }
}

function clearCommitLookup() {
  commitLookupEpoch++
  locatedCommit.value = null
  commitLookupLoading.value = false
  commitLookupError.value = ''
}

async function locateCommit(sha: string) {
  if (!projectId.value || !commitPickerOpen.value || !/^[a-f0-9]{40}$/i.test(sha)) return
  const epoch = ++commitLookupEpoch
  const id = projectId.value
  locatedCommit.value = null
  commitLookupError.value = ''
  commitLookupLoading.value = true
  try {
    const detail = await repositoryApi.commit(id, sha)
    if (epoch !== commitLookupEpoch || id !== projectId.value || !commitPickerOpen.value) return
    locatedCommit.value = { sha: detail.sha, message: detail.message, author: detail.author, date: detail.date }
  } catch (failure) {
    if (epoch !== commitLookupEpoch || id !== projectId.value || !commitPickerOpen.value) return
    commitLookupError.value = (failure as Error).message || '无法定位提交，请核对完整 SHA'
  } finally {
    if (epoch === commitLookupEpoch) commitLookupLoading.value = false
  }
}

async function reload(fixedSha?: unknown) {
  if (!projectId.value || !branch.value) return
  let request = ++epoch
  previewEpoch++
  const id = projectId.value
  const branchName = branch.value
  const sourceMode = mode.value
  const previousVersion = version.value
  treeLoading.value = true
  error.value = ''
  try {
    let loadedEntries: FileTreeEntry[]
    let sha: string
    let incomplete = false
    if (sourceMode === 'FILES') {
      sha = typeof fixedSha === 'string' && /^[a-f0-9]{40}$/i.test(fixedSha)
        ? fixedSha : await repositoryApi.head(id, branchName)
      if (request !== epoch || disposed) return
      const list = await repositoryApi.paths(id, sha)
      if (request !== epoch || disposed) return
      incomplete = list.truncated
      loadedEntries = list.entries.filter(entry => entry.type === 'blob' && entry.reviewable)
        .map(entry => ({ path: entry.path, reviewable: true }))
    } else {
      if (!commitsPage) await loadCommits(1)
      if (request !== epoch || disposed) return
      if (!commitSha.value && commits.value.length) {
        commitSha.value = commits.value[0].sha
        request = ++epoch
        treeLoading.value = true
      }
      sha = commitSha.value
      if (!sha) { entries.value = []; tree.value = []; version.value = ''; return }
      const detail = await repositoryApi.commit(id, sha)
      if (request !== epoch || disposed) return
      incomplete = detail.truncated
      loadedEntries = detail.files.filter(file => file.reviewable).map(file => ({
        path: file.path, reviewable: true, status: file.status, oldPath: file.previousPath
      }))
    }
    if (previousVersion && previousVersion !== sha) {
      selected.value = []
      previewPath.value = ''
      message.warning('版本已变化，请重新选择文件')
    }
    version.value = sha
    entries.value = loadedEntries
    truncated.value = incomplete
    const available = new Set(entries.value.map(entry => entry.path))
    selected.value = selected.value.filter(path => available.has(path))
    tree.value = buildPathTree(entries.value)
    expandAll()
  } catch (failure) {
    if (request === epoch && !disposed) error.value = (failure as Error).message || '代码列表读取失败'
  } finally {
    if (request === epoch && !disposed) treeLoading.value = false
  }
}

function onCheck(keys: (string | number)[]) {
  const visible = leafKeys(treeData.value)
  selected.value = mergeCheckedSelection(selected.value, visible,
    checkedFileKeys(keys, treeData.value))
}

function onExpand(keys: (string | number)[]) {
  expandedKeys.value = keys.map(String)
}

function expandAll() {
  const folders: string[] = []
  const walk = (nodes: FileTreeNode[]) => {
    for (const node of nodes) {
      if (!node.isLeaf) {
        folders.push(node.key)
        walk(node.children ?? [])
      }
    }
  }
  walk(tree.value)
  expandedKeys.value = folders
}

async function onSelect(_keys: (string | number)[], info: { node?: { dataRef?: FileTreeNode } & FileTreeNode }) {
  const node = info?.node?.dataRef ?? info?.node
  if (!node?.isLeaf || !node.path || treeLoading.value || !version.value) return
  const request = ++previewEpoch
  const id = projectId.value
  const sha = version.value
  const sourceMode = mode.value
  previewMode.value = 'changes'
  previewPath.value = node.path
  previewContent.value = ''
  await nextTick()
  codePane.value?.scrollTo({ top: 0 })
  previewDiff.value = null
  previewError.value = ''
  previewLoading.value = true
  try {
    if (sourceMode === 'DIFF') {
      const loaded = await repositoryApi.diff(id, sha, node.path)
      if (request === previewEpoch && !disposed) previewDiff.value = loaded
    } else {
      const snapshot = await repositoryApi.file(id, sha, node.path)
      if (request === previewEpoch && !disposed) previewContent.value = snapshot.content
    }
  } catch (failure) {
    if (request === previewEpoch && !disposed) previewError.value = (failure as Error).message || '文件读取失败'
  } finally {
    if (request === previewEpoch && !disposed) previewLoading.value = false
  }
}

function launchSelected() {
  if (treeLoading.value || error.value || !version.value) return
  if (!selected.value.length) {
    message.warning('请先勾选至少一个文件')
    return
  }
  const selectedEntries = entries.value.filter((entry) => selected.value.includes(entry.path))
  try {
  sessionStorage.setItem('review-launch-prefill', JSON.stringify({
    projectId: projectId.value,
    branch: branch.value,
    sourceKind: mode.value,
    observedSha: mode.value === 'FILES' ? version.value : '',
    commitSha: mode.value === 'DIFF' ? commitSha.value : '',
    targets: selectedEntries.map((entry) => ({ path: entry.path, oldPath: entry.oldPath || null }))
  }))
  } catch { message.error('无法保存已选范围，请重试'); return }
  saveScene()
  router.push({ path: '/reviews/new', query: { from: 'browser' } })
}

function statusText(status: string): string {
  return ({ added: '新增', modified: '修改', removed: '删除', renamed: '重命名' } as Record<string, string>)[status] || status
}
</script>

<style scoped lang="less">
.page-head {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  margin-bottom: 32px;
}
.page-head h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.4;
}
.page-head p {
  margin: 8px 0 0;
  font-size: 15px;
}
.browser-card :deep(.ant-card-body) {
  padding: 0;
}
.muted {
  color: var(--muted);
}
.small {
  font-size: 12px;
}
.mb8 {
  margin-bottom: 8px;
}
.mb16 {
  margin-bottom: 16px;
}
.filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 0;
  padding: 20px 24px;
  border-bottom: 1px solid var(--line);
}
.browser-toolbar {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.browser-toolbar > .filters {
  padding-bottom: 10px;
  border-bottom: 0;
}
.commit-selection-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  margin: 0 24px;
  color: var(--muted);
}
.commit-selection-summary code {
  flex: 0 0 auto;
  padding: 2px 6px;
  border: 1px solid var(--line);
  border-radius: 5px;
}
.commit-selection-summary span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.spacer {
  flex: 1;
}
.browser-grid {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 540px;
  height: calc(100vh - 280px);
  overflow: hidden;
}
.tree {
  min-width: 0;
  min-height: 0;
  border-right: 1px solid var(--line);
  padding: 18px;
  overflow: auto;
  background: #fff;
}
.tree :deep(.ant-tree-treenode) {
  width: max-content;
  min-width: 100%;
}
.tree :deep(.ant-tree-node-content-wrapper) {
  overflow: visible;
}
.tree :deep(.tree-label) {
  white-space: nowrap;
}
.code-pane {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  background: #fcfdfe;
}
.code-head {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 12px 20px;
  background: #fff;
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 12px;
  align-items: center;
}
.code-head strong {
  overflow-wrap: anywhere;
}
.code {
  margin: 0;
  padding: 12px;
  font: 12px/1.8 Consolas, 'Cascadia Code', monospace;
  white-space: pre;
}
.empty {
  padding: 48px 25px;
  text-align: center;
  color: var(--muted);
}
@media (max-width: 1100px) {
  .browser-grid {
    grid-template-columns: 240px minmax(0, 1fr);
  }
}
@media (max-width: 760px) {
  .page-head {
    flex-wrap: wrap;
    align-items: flex-start;
  }
  .page-head h1 {
    font-size: 26px;
  }
  .browser-grid {
    grid-template-columns: 1fr;
    height: auto;
  }
  .tree {
    max-height: 210px;
  }
}
.file-link {
  color: var(--blue);
  cursor: pointer;
}
.status-tag {
  margin-left: 6px;
}
</style>
