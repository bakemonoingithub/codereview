<template>
  <a-modal
    :open="open"
    title="选择审查文件"
    width="min(1160px, calc(100vw - 48px))"
    :footer="null"
    destroy-on-close
    @cancel="close"
  >
    <div class="scope-head">
      <strong>{{ mode === 'DIFF' ? '提交差异' : '文件内容' }}</strong>
      <code>{{ mode === 'DIFF' ? commitSha : sha }}</code>
      <span v-if="mode === 'DIFF'" class="muted">第一父提交为比较基线</span>
      <span v-else class="muted">分支 {{ branch || '—' }} · 固定预览版本</span>
      <span class="spacer" />
      <a-button size="small" :loading="loading" @click="load">刷新列表</a-button>
    </div>

    <a-alert v-if="error" type="error" show-icon :message="error" class="mb12">
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>
    <a-alert
      v-if="truncated"
      type="warning"
      show-icon
      message="文件列表可能不完整，仍可选择当前显示的文件；不会把未显示文件自动纳入范围。"
      class="mb12"
    />

    <div class="scope-toolbar">
      <a-input
        v-model:value="query"
        allow-clear
        placeholder="按路径搜索"
        style="width: 260px"
      />
      <a-button size="small" @click="expandAll">展开</a-button>
      <a-button size="small" @click="collapseAll">收起</a-button>
      <a-button size="small" @click="selectFiltered">全选筛选结果</a-button>
      <a-button size="small" @click="clearChecked">清空</a-button>
      <span class="spacer" />
      <span class="muted">已选 {{ checked.length }} / 当前可选 {{ entries.length }} 个文件</span>
    </div>

    <div class="scope-body">
      <a-spin :spinning="loading">
        <a-empty v-if="!loading && !treeData.length" description="没有匹配的文件" />
        <a-tree
          v-else
          checkable
          :tree-data="treeData"
          :checked-keys="checked"
          :expanded-keys="expandedKeys"
          @check="onCheck"
          @expand="onExpand"
        >
          <template #title="{ dataRef }">
            <span
              v-if="dataRef.isLeaf"
              class="file-name"
              :class="{ disabled: dataRef.reviewable === false }"
              @click.stop="openPreview(dataRef)"
            >
              {{ dataRef.title }}
            </span>
            <span v-else>{{ dataRef.title }}</span>
            <a-tag v-if="dataRef.isLeaf && dataRef.status" class="status-tag" color="blue">
              {{ statusText(dataRef.status) }}
            </a-tag>
          </template>
        </a-tree>
      </a-spin>
    </div>

    <div class="scope-foot">
      <span class="muted">点击文件名只预览，复选框只调整审查目标；确认后回填主表单，取消不改变原范围。</span>
      <span class="spacer" />
      <a-button @click="close">取消</a-button>
      <a-button type="primary" :disabled="loading || !!error" @click="confirm">确认选择</a-button>
    </div>

    <a-modal
      v-model:open="previewOpen"
      :title="previewTitle"
      width="min(1000px, calc(100vw - 48px))"
      :footer="null"
      destroy-on-close
    >
      <a-spin :spinning="previewLoading">
        <a-alert v-if="previewError" type="error" show-icon :message="previewError" />
        <template v-else-if="previewOpen">
          <div v-if="mode === 'DIFF'" class="preview-tabs">
            <a-button
              v-for="tab in diffTabs"
              :key="tab.key"
              size="small"
              :type="previewMode === tab.key ? 'primary' : 'default'"
              @click="previewMode = tab.key"
            >
              {{ tab.label }}
            </a-button>
          </div>
          <div class="preview-meta">
            <span>{{ previewPath }}</span>
            <span v-if="previewSides" class="muted">
              {{ previewSides.baseSha?.slice(0, 7) || '无父提交' }} → {{ previewSides.headSha.slice(0, 7) }}
            </span>
          </div>
          <DiffViewer
            v-if="mode === 'DIFF' && previewMode === 'changes'"
            :path="previewPath"
            :patch="previewSides?.patch || null"
            highlight
            empty-text="该文件没有可显示的差异"
          />
          <CodeViewer
            v-else-if="previewCode !== null"
            :text="previewCode"
            :path="previewPath"
          />
          <pre v-else class="code-preview">{{ previewNote }}</pre>
        </template>
      </a-spin>
    </a-modal>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CodeViewer from '@/components/CodeViewer.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import { repositoryApi, type DiffSides } from '@/api/v2'
import {
  buildPathTree,
  checkedFileKeys,
  filterPathTree,
  leafKeys,
  mergeCheckedSelection,
  type FileTreeEntry,
  type FileTreeNode
} from '@/utils/pathTree'

type ScopeMode = 'FILES' | 'DIFF'
type PreviewMode = 'changes' | 'before' | 'after' | 'content'

interface ScopeSelection {
  path: string
  oldPath: string | null
}

const props = defineProps<{
  open: boolean
  projectId: string
  mode: ScopeMode
  sha: string
  commitSha?: string
  branch?: string
  selected: string[]
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'confirm', entries: ScopeSelection[]): void
}>()

let loadEpoch = 0
let previewEpoch = 0
const loading = ref(false)
const error = ref('')
const truncated = ref(false)
const entries = ref<FileTreeEntry[]>([])
const tree = ref<FileTreeNode[]>([])
const checked = ref<string[]>([])
const query = ref('')
const expandedKeys = ref<string[]>([])

const previewOpen = ref(false)
const previewLoading = ref(false)
const previewError = ref('')
const previewPath = ref('')
const previewMode = ref<PreviewMode>('content')
const previewSides = ref<DiffSides | null>(null)
const previewContent = ref('')

const diffTabs = [
  { key: 'changes' as const, label: '改动' },
  { key: 'before' as const, label: '修改前原文' },
  { key: 'after' as const, label: '修改后原文' }
]

const treeData = computed(() => filterPathTree(tree.value, query.value))
const previewTitle = computed(() =>
  props.mode === 'DIFF' ? `${previewPath.value} · 提交差异` : `${previewPath.value} · 文件原文`
)
const previewCode = computed<string | null>(() => {
  if (props.mode === 'FILES' || previewMode.value === 'content') return previewContent.value
  if (previewMode.value === 'changes') return null
  const side = previewMode.value === 'before' ? previewSides.value?.before : previewSides.value?.after
  if (!side || side.status !== 'PRESENT') return null
  return side.content || ''
})
const previewNote = computed(() => {
  if (props.mode === 'FILES' || previewMode.value === 'content' || previewMode.value === 'changes') return ''
  const side = previewMode.value === 'before' ? previewSides.value?.before : previewSides.value?.after
  if (side?.status === 'PRESENT') return ''
  return side?.reason === 'ADDED'
    ? '该文件为新增文件，修改前不存在。'
    : side?.reason === 'REMOVED'
      ? '该文件已删除，修改后不存在。'
      : '该侧原文不可读取。'
})

watch(
  () => [props.open, props.projectId, props.mode, props.sha, props.commitSha] as const,
  async ([open]) => {
    loadEpoch++
    previewEpoch++
    previewOpen.value = false
    entries.value = []
    tree.value = []
    if (!open) {
      return
    }
    checked.value = [...props.selected]
    await load()
  },
  { immediate: true }
)

async function load() {
  if (!props.projectId) {
    return
  }
  const epoch = ++loadEpoch
  loading.value = true
  error.value = ''
  entries.value = []
  tree.value = []
  try {
    if (props.mode === 'DIFF') {
      if (!props.commitSha) {
        throw new Error('请选择提交')
      }
      const detail = await repositoryApi.commit(props.projectId, props.commitSha)
      if (epoch !== loadEpoch) return
      entries.value = detail.files
        .filter((file) => file.reviewable)
        .map((file) => ({
          path: file.path,
          reviewable: file.reviewable,
          status: file.status,
          oldPath: file.previousPath
        }))
      truncated.value = detail.truncated
    } else {
      const list = await repositoryApi.paths(props.projectId, props.sha)
      if (epoch !== loadEpoch) return
      entries.value = list.entries
        .filter((entry) => entry.type === 'blob' && entry.reviewable)
        .map((entry) => ({ path: entry.path, reviewable: true }))
      truncated.value = list.truncated
    }
    tree.value = buildPathTree(entries.value)
    expandAll()
    checked.value = checked.value.filter((path) => entries.value.some((entry) => entry.path === path))
  } catch (failure) {
    if (epoch !== loadEpoch) return
    error.value = (failure as Error).message || '文件列表加载失败'
  } finally {
    if (epoch === loadEpoch) loading.value = false
  }
}

function onCheck(keys: (string | number)[]) {
  // 搜索筛选后树里只剩部分文件；a-tree 的回写只覆盖当前可见叶子，
  // 筛选外的已选必须保留，否则“搜索一下再勾一个”会静默丢范围。
  const visible = leafKeys(treeData.value)
  checked.value = mergeCheckedSelection(checked.value, visible,
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

function collapseAll() {
  expandedKeys.value = []
}

function selectFiltered() {
  const keys = leafKeys(treeData.value)
  checked.value = Array.from(new Set([...checked.value, ...keys]))
}

function clearChecked() {
  checked.value = []
}

function confirm() {
  if (loading.value || error.value) return
  const byPath = new Map(entries.value.map((entry) => [entry.path, entry]))
  const selected = checked.value
    .map((path) => byPath.get(path))
    .filter((entry): entry is FileTreeEntry => !!entry)
    .map((entry) => ({ path: entry.path, oldPath: entry.oldPath ?? null }))
  emit('confirm', selected)
  close()
}

function close() {
  loadEpoch++
  previewEpoch++
  previewOpen.value = false
  emit('update:open', false)
}

async function openPreview(node: FileTreeNode) {
  if (!node.path) {
    return
  }
  const epoch = ++previewEpoch
  previewPath.value = node.path
  previewOpen.value = true
  previewLoading.value = true
  previewError.value = ''
  previewSides.value = null
  previewContent.value = ''
  previewMode.value = props.mode === 'DIFF' ? 'changes' : 'content'
  try {
    if (props.mode === 'DIFF') {
      if (!props.commitSha) {
        throw new Error('请选择提交')
      }
      const sides = await repositoryApi.diff(props.projectId, props.commitSha, node.path)
      if (epoch !== previewEpoch) return
      previewSides.value = sides
    } else {
      const snapshot = await repositoryApi.file(props.projectId, props.sha, node.path)
      if (epoch !== previewEpoch) return
      previewContent.value = snapshot.content
    }
  } catch (failure) {
    if (epoch !== previewEpoch) return
    previewError.value = (failure as Error).message || '文件读取失败'
  } finally {
    if (epoch === previewEpoch) previewLoading.value = false
  }
}

function statusText(status: string): string {
  return ({ added: '新增', modified: '修改', removed: '删除', renamed: '重命名' } as Record<string, string>)[status] || status
}
</script>

<style scoped lang="less">
.scope-head,
.scope-toolbar,
.scope-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.scope-head {
  margin-bottom: 12px;
}
.scope-toolbar {
  margin-bottom: 10px;
}
.scope-body {
  max-height: 46vh;
  min-height: 220px;
  overflow: auto;
  border: 1px solid #e3e9f0;
  border-radius: 7px;
  padding: 12px;
  background: #fbfcfe;
}
.scope-foot {
  margin-top: 14px;
}
.spacer {
  flex: 1;
}
.muted {
  color: #69798d;
  font-size: 12px;
}
.mb12 {
  margin-bottom: 12px;
}
.file-name {
  color: #2463d5;
  cursor: pointer;
}
.file-name.disabled {
  color: #9ca9b9;
  cursor: not-allowed;
}
.status-tag {
  margin-left: 6px;
}
.preview-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.preview-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-family: Consolas, monospace;
  font-size: 12px;
}
.code-preview {
  max-height: 52vh;
  overflow: auto;
  background: #f6f8fc;
  border: 1px solid #e3e9f0;
  border-radius: 6px;
  padding: 12px;
  font: 12px/1.8 Consolas, 'Cascadia Code', monospace;
  white-space: pre;
}
</style>
