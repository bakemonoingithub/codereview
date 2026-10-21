<template>
  <div>
    <div class="page-head">
      <div>
        <h2>代码浏览</h2>
        <p class="muted">按固定版本浏览文件与提交差异，勾选文件后带入发起审查。</p>
      </div>
      <a-button type="primary" :disabled="!selected.length || treeLoading || !!error" @click="launchSelected">
        审查已选文件（{{ selected.length }}）
      </a-button>
    </div>

    <a-alert v-if="error" type="error" show-icon :message="error" class="mb16">
      <template #action><a-button size="small" @click="retryBrowser">重试</a-button></template>
    </a-alert>

    <a-alert v-if="truncated" type="warning" show-icon
      message="宿主返回的文件清单不完整，只能浏览和选择当前已列出的文件。" class="mb16" />
    <a-card>
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
        <a-select
          v-if="mode === 'DIFF'"
          v-model:value="commitSha"
          placeholder="选择提交"
          style="width: 320px"
          :loading="commitsLoading"
          :options="commitOptions"
          @change="onCommitChange"
        />
        <a-button v-if="mode === 'DIFF' && commitsHasMore" size="small"
          :loading="commitsLoading" @click="loadMoreCommits">加载更多提交</a-button>
        <span class="spacer" />
        <code v-if="version">{{ version.slice(0, 7) }}</code>
        <span v-if="version" class="muted small">固定版本</span>
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
                <span :class="{ 'file-link': dataRef.isLeaf }">{{ dataRef.title }}</span>
                <a-tag v-if="dataRef.status" class="status-tag" color="blue">
                  {{ statusText(dataRef.status) }}
                </a-tag>
              </template>
            </a-tree>
          </a-spin>
        </aside>

        <section class="code-pane">
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
              <pre v-else class="code">{{ previewText }}</pre>
            </template>
            <div v-else class="empty">从左侧选择文件查看原文或改动。</div>
          </a-spin>
        </section>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import DiffViewer from '@/components/DiffViewer.vue'
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
const version = ref('')
const entries = ref<FileTreeEntry[]>([])
const tree = ref<FileTreeNode[]>([])
const selected = ref<string[]>([])
const expandedKeys = ref<string[]>([])
const query = ref('')
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
  }
}, { flush: 'sync' })

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: project.name }))
)
const branchOptions = computed(() => branches.value.map((item) => ({ value: item, label: item })))
const commitOptions = computed(() =>
  commits.value.map((commit) => ({
    value: commit.sha,
    label: `${commit.sha.slice(0, 7)} · ${(commit.message || '').split('\n')[0]}`
  }))
)
const treeData = computed(() => filterPathTree(tree.value, query.value))

const previewText = computed(() => {
  if (mode.value === 'FILES') return numbered(previewContent.value)
  const side = previewMode.value === 'before' ? previewDiff.value?.before : previewDiff.value?.after
  if (!side) return ''
  if (side.status === 'PRESENT') return numbered(side.content || '')
  return side.status === 'MISSING' ? `该侧文件不存在（${side.reason || '未知原因'}）`
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
      if (!await loadBranches() || sceneKey !== sceneIdentity()) return
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
  branch.value = project?.currentBranch || 'main'
  commitSha.value = ''
  const id = projectId.value
  if (!await loadBranches()) return
  if (disposed || id !== projectId.value) return
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

async function onBranchChange() { commitSha.value = ''; await reload() }
async function onModeChange() { commitSha.value = ''; await reload() }
async function onCommitChange() { await reload() }

async function loadMoreCommits() {
  if (commitsLoading.value || !commitsHasMore.value) return
  try { if (await loadCommits(commitsPage + 1)) error.value = '' }
  catch (failure) { error.value = (failure as Error).message || '更多提交读取失败' }
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
    return true
  } catch (failure) {
    if (request !== commitsEpoch || key !== sourceKey() || disposed) return false
    throw failure
  } finally {
    if (request === commitsEpoch) commitsLoading.value = false
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

function numbered(text: string): string {
  if (!text) return ''
  return text.split('\n').map((line, index) =>
    `${String(index + 1).padStart(5, ' ')} | ${line}`).join('\n')
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
  align-items: flex-start;
  margin-bottom: 18px;
}
.page-head h2 {
  margin: 0 0 4px;
}
.muted {
  color: #69798d;
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
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.spacer {
  flex: 1;
}
.browser-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  min-height: 560px;
  border: 1px solid #e3e9f0;
  border-radius: 8px;
  overflow: hidden;
}
.tree {
  border-right: 1px solid #e3e9f0;
  padding: 12px;
  overflow: auto;
  background: #fbfcfe;
}
.code-pane {
  min-width: 0;
  overflow: auto;
  background: #fbfcfe;
}
.code-head {
  padding: 10px 14px;
  background: #fff;
  border-bottom: 1px solid #e3e9f0;
  display: flex;
  gap: 10px;
  align-items: center;
}
.code {
  margin: 0;
  padding: 12px;
  font: 12px/1.8 Consolas, 'Cascadia Code', monospace;
  white-space: pre;
}
.empty {
  padding: 40px;
  text-align: center;
  color: #69798d;
}
.file-link {
  color: #2463d5;
  cursor: pointer;
}
.status-tag {
  margin-left: 6px;
}
</style>
