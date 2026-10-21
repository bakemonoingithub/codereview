<template>
  <div>
    <div class="page-head">
      <div>
        <h2>代码浏览</h2>
        <p class="muted">按固定版本浏览文件与提交差异，勾选文件后带入发起审查。</p>
      </div>
      <a-button type="primary" :disabled="!selected.length" @click="launchSelected">
        审查已选文件（{{ selected.length }}）
      </a-button>
    </div>

    <a-alert v-if="error" type="error" show-icon :message="error" class="mb16">
      <template #action><a-button size="small" @click="reload">重试</a-button></template>
    </a-alert>

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
              <DiffViewer
                v-if="mode === 'DIFF'"
                :path="previewPath"
                :patch="previewDiff?.patch || null"
                highlight
                empty-text="该文件没有可显示的差异"
              />
              <pre v-else class="code">{{ numbered(previewContent) }}</pre>
            </template>
            <div v-else class="empty">从左侧选择文件查看原文或改动。</div>
          </a-spin>
        </section>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
const previewContent = ref('')
const previewDiff = ref<DiffSides | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const treeLoading = ref(false)
const branchesLoading = ref(false)
const commitsLoading = ref(false)
const error = ref('')
let epoch = 0

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

onMounted(async () => {
  try {
    const page = await projectsApi.list({ limit: 100 })
    projects.value = page.items
    if (projects.value.length) {
      projectId.value = projects.value[0].id
      await onProjectChange()
    }
  } catch (failure) {
    error.value = (failure as Error).message || '项目加载失败'
  }
})

async function onProjectChange() {
  const project = projects.value.find((item) => item.id === projectId.value)
  branch.value = project?.currentBranch || 'main'
  selected.value = []
  previewPath.value = ''
  await loadBranches()
  await reload()
}

async function loadBranches() {
  if (!projectId.value) return
  branchesLoading.value = true
  try {
    branches.value = await repositoryApi.branches(projectId.value)
    if (!branches.value.includes(branch.value) && branches.value.length) {
      branch.value = branches.value[0]
    }
  } catch (failure) {
    error.value = (failure as Error).message || '分支列表读取失败'
  } finally {
    branchesLoading.value = false
  }
}

async function onBranchChange() {
  selected.value = []
  previewPath.value = ''
  await reload()
}

async function onModeChange() {
  selected.value = []
  previewPath.value = ''
  await reload()
}

async function onCommitChange() {
  selected.value = []
  previewPath.value = ''
  await reload()
}

async function reload() {
  if (!projectId.value || !branch.value) return
  const request = ++epoch
  treeLoading.value = true
  error.value = ''
  try {
    if (mode.value === 'FILES') {
      version.value = await repositoryApi.head(projectId.value, branch.value)
      const list = await repositoryApi.paths(projectId.value, version.value)
      if (request !== epoch) return
      entries.value = list.entries
        .filter((entry) => entry.type === 'blob' && entry.reviewable)
        .map((entry) => ({ path: entry.path, reviewable: true }))
    } else {
      const page = await repositoryApi.commits(projectId.value, branch.value, 1, 30)
      if (request !== epoch) return
      commits.value = page.commits
      if (!commitSha.value && commits.value.length) {
        commitSha.value = commits.value[0].sha
      }
      if (!commitSha.value) {
        entries.value = []
        version.value = ''
        tree.value = []
        return
      }
      const detail = await repositoryApi.commit(projectId.value, commitSha.value)
      if (request !== epoch) return
      version.value = commitSha.value
      entries.value = detail.files
        .filter((file) => file.reviewable)
        .map((file) => ({
          path: file.path,
          reviewable: true,
          status: file.status,
          oldPath: file.previousPath
        }))
    }
    tree.value = buildPathTree(entries.value)
    expandAll()
  } catch (failure) {
    if (request === epoch) {
      error.value = (failure as Error).message || '代码列表读取失败'
    }
  } finally {
    if (request === epoch) treeLoading.value = false
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
  if (!node?.isLeaf || !node.path) return
  previewPath.value = node.path
  previewError.value = ''
  previewLoading.value = true
  try {
    if (mode.value === 'DIFF') {
      previewDiff.value = await repositoryApi.diff(projectId.value, commitSha.value, node.path)
    } else {
      const snapshot = await repositoryApi.file(projectId.value, version.value, node.path)
      previewContent.value = snapshot.content
    }
  } catch (failure) {
    previewError.value = (failure as Error).message || '文件读取失败'
  } finally {
    previewLoading.value = false
  }
}

function launchSelected() {
  if (!selected.value.length) {
    message.warning('请先勾选至少一个文件')
    return
  }
  const selectedEntries = entries.value.filter((entry) => selected.value.includes(entry.path))
  sessionStorage.setItem('review-launch-prefill', JSON.stringify({
    projectId: projectId.value,
    branch: branch.value,
    sourceKind: mode.value,
    observedSha: mode.value === 'FILES' ? version.value : '',
    commitSha: mode.value === 'DIFF' ? commitSha.value : '',
    targets: selectedEntries.map((entry) => ({ path: entry.path, oldPath: entry.oldPath || null }))
  }))
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
