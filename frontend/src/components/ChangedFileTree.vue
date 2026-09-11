<template>
  <div class="changed-file-tree">
    <div class="toolbar">
      <a-input v-model:value="keyword" size="small" placeholder="按路径搜索" allow-clear />
      <a-select v-model:value="statusFilter" size="small" style="width: 108px" :options="statusOptions" />
    </div>
    <div class="toolbar">
      <a-space :size="4">
        <a-button size="small" @click="expandAll">展开</a-button>
        <a-button size="small" @click="collapseAll">收起</a-button>
        <a-button size="small" @click="selectAllFiltered">
          {{ keyword || statusFilter ? '全选筛选结果' : '全选' }}
        </a-button>
        <a-button size="small" @click="clearAll">清空</a-button>
      </a-space>
      <span class="count">已选 {{ checked.length }} / 可审查 {{ reviewablePaths.length }}</span>
    </div>
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" class="mb8">
        <template #action>
          <a-button size="small" @click="$emit('retry')">重试</a-button>
        </template>
      </a-alert>
      <!--
        截断只作**警告**，不能再把树吃掉：原先 truncated 与 a-tree 是互斥分支，
        结果是"看到超过 300 个的警告、却没有树可勾"，而 commitChecked 已被默认全选 ——
        清空后再也选不回来，整条分支变成死路。
      -->
      <a-alert
        v-if="truncated && !error"
        type="warning"
        show-icon
        class="mb8"
        message="该提交变更文件较多，宿主只返回了第一页，列表可能不完整"
      />
      <a-empty v-if="!error && !loading && !filteredFiles.length" :description="emptyText" />
      <a-tree
        v-else-if="!error"
        checkable
        :selectable="false"
        :tree-data="treeData"
        :checked-keys="checked"
        v-model:expanded-keys="expandedKeys"
        @check="onCheck"
      >
        <template #title="{ dataRef }">
          <span class="node-title">
            <a-tag v-if="dataRef.file" :color="statusMeta(dataRef.file).color" class="badge">
              {{ statusMeta(dataRef.file).letter }}
            </a-tag>
            <span class="name" :class="{ muted: dataRef.file && !isReviewable(dataRef.file) }">
              {{ dataRef.title }}
            </span>
            <span v-if="dataRef.file" class="stat">
              <span v-if="dataRef.file.additions" class="add">+{{ dataRef.file.additions }}</span>
              <span v-if="dataRef.file.deletions" class="del">-{{ dataRef.file.deletions }}</span>
            </span>
            <a-tooltip v-if="dataRef.file && !isReviewable(dataRef.file)" :title="notReviewableReason(dataRef.file)">
              <span class="mark">不可审查</span>
            </a-tooltip>
          </span>
        </template>
      </a-tree>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  buildChangedFileTree,
  collectReviewablePaths,
  filterChangedFiles,
  isReviewable,
  notReviewableReason,
  statusMeta,
  type ChangedFile
} from '@/utils/changedFiles'

const props = withDefaults(
  defineProps<{
    files: ChangedFile[]
    checked: string[]
    loading?: boolean
    error?: string
    truncated?: boolean
    emptyText?: string
  }>(),
  {
    loading: false,
    error: '',
    truncated: false,
    emptyText: '该提交无变更文件'
  }
)

const emit = defineEmits<{
  (e: 'update:checked', value: string[]): void
  (e: 'retry'): void
}>()

const keyword = ref('')
const statusFilter = ref('')
const expandedKeys = ref<string[]>([])

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'added', label: '新增' },
  { value: 'modified', label: '修改' },
  { value: 'removed', label: '删除' },
  { value: 'renamed', label: '重命名' }
]

const filteredFiles = computed(() => filterChangedFiles(props.files, keyword.value, statusFilter.value))
const treeData = computed(() => buildChangedFileTree(filteredFiles.value))
const reviewablePaths = computed(() => collectReviewablePaths(buildChangedFileTree(props.files)))

// 过滤变化后自动展开，省去用户逐层点开。
// `immediate` 不能省：挂载时 files 若已经就位（或数据是同步给的），treeData 不会再"变化"，
// 这个 watch 就永远不会触发，树会一直停在折叠状态只显示一层目录。
watch(
  treeData,
  () => {
    const keys: string[] = []
    const walk = (nodes: any[]) => {
      for (const node of nodes) {
        if (node.children?.length) {
          keys.push(node.key)
          walk(node.children)
        }
      }
    }
    walk(treeData.value)
    expandedKeys.value = keys
  },
  { immediate: true }
)

function onCheck(keys: any) {
  const list: string[] = Array.isArray(keys) ? keys : (keys?.checked || [])
  // 目录级勾选会带上不可审查的叶子，这里统一过滤掉
  const allowed = new Set(reviewablePaths.value)
  emit('update:checked', list.filter((k) => allowed.has(k)))
}

function expandAll() {
  const keys: string[] = []
  const walk = (nodes: any[]) => {
    for (const node of nodes) {
      if (node.children?.length) {
        keys.push(node.key)
        walk(node.children)
      }
    }
  }
  walk(treeData.value)
  expandedKeys.value = keys
}

function collapseAll() {
  expandedKeys.value = []
}

/**
 * 全选**当前筛选结果**。
 *
 * 原先是 `[...reviewablePaths]`（未过滤的全集）：搜索出 3 个文件后点"全选"，
 * 实际勾上的是全部可审文件 —— 用户以为只选了筛出来的那几个，随后要么被单元数上限拦住，
 * 要么跑出远超预期的审查。
 */
function selectAllFiltered() {
  const allowed = new Set(reviewablePaths.value)
  emit(
    'update:checked',
    filteredFiles.value.map((f) => f.path).filter((path) => allowed.has(path))
  )
}

function clearAll() {
  emit('update:checked', [])
}
</script>

<style scoped lang="less">
.changed-file-tree {
  .toolbar {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 6px;
  }
  .count {
    margin-left: auto;
    color: #999;
    font-size: 12px;
  }
  .node-title {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .badge {
    margin: 0;
    padding: 0 4px;
    line-height: 16px;
    font-size: 11px;
  }
  .name.muted {
    color: #bfbfbf;
  }
  .stat {
    font-size: 11px;
  }
  .add {
    color: #389e0d;
  }
  .del {
    color: #cf1322;
  }
  .mark {
    color: #d46b08;
    font-size: 11px;
  }
  .mb8 {
    margin-bottom: 8px;
  }
}
</style>
