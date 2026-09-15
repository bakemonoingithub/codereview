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
      <span class="count">已选 {{ checked.length }} / 共 {{ selectablePaths.length }} 个文件</span>
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
            <!--
              可查看的文本文件标题可点（=看这个提交对它的差异）。
              这里不用 antd 的 select：树开着 checkable，用复选框勾选审查范围，
              再让整行变成"可选中"会与勾选语义混在一起；只把标题做成可点最不容易误伤。

              `.stop` 不能省：这棵树是 `:selectable="false"` 的 checkable 树，
              点标题会被 antd 当成**切换勾选** —— 少了它，"点文件看差异"会顺手把该文件
              加进/移出审查范围（实测踩到，见 ChangedFileTree.spec 的断言）。
            -->
            <span
              v-if="dataRef.file && isReviewable(dataRef.file)"
              class="name clickable"
              :title="`查看 ${dataRef.title} 的提交差异`"
              @click.stop="emit('view', dataRef.file.path)"
            >{{ dataRef.title }}</span>
            <span v-else class="name" :class="{ muted: dataRef.file }">
              {{ dataRef.title }}
            </span>
            <span v-if="dataRef.file" class="stat">
              <span v-if="dataRef.file.additions" class="add">+{{ dataRef.file.additions }}</span>
              <span v-if="dataRef.file.deletions" class="del">-{{ dataRef.file.deletions }}</span>
            </span>
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
  collectAllLeafPaths,
  filterChangedFiles,
  isReviewable,
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
  /** 点了可查看的文件标题：调用方据此打开差异弹窗 */
  (e: 'view', path: string): void
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
/**
 * 可勾选的叶子路径 —— **全部**叶子。
 *
 * 这里原先是 `collectReviewablePaths`（只收可审查的），配合置灰把二进制挡在勾选之外。
 * 现在改成"都能勾、提交时统一警告"：静默过滤会让用户"勾了却不生效"又不知道原因
 * （正是前两次刚修过的那类问题），而且判定权已收归后端 `ReviewableFiles`，前端不再自己筛。
 */
const selectablePaths = computed(() => collectAllLeafPaths(buildChangedFileTree(props.files)))

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

/**
 * 把一次勾选的结果并回选中集：**只改"当前筛选下可见"的那部分，被筛掉的保持原样**。
 *
 * 直接 emit 回写结果是不行的：a-tree 只认识当前 treeData 里的节点，筛选后回写的 keys
 * **不含被筛掉的已选**。直接覆盖会变成"换一个关键词再勾，上一次勾的全没了" ——
 * 与"搜索只影响显示与全选范围"的语义正好相反。
 *
 * 顺带完成原先 onCheck 的过滤：`visible` 已与可审查集合取过交集，所以目录键与
 * 不可审查的叶子都被挡在外面（目录级勾选会把它们一起带上）。
 */
function mergeVisibleSelection(selectedVisible: string[]) {
  const allowed = new Set(selectablePaths.value)
  const visible = new Set(filteredFiles.value.map((f) => f.path).filter((p) => allowed.has(p)))
  const kept = props.checked.filter((k) => !visible.has(k))
  const now = selectedVisible.filter((k) => visible.has(k))
  emit('update:checked', [...new Set([...kept, ...now])])
}

function onCheck(keys: any) {
  mergeVisibleSelection(Array.isArray(keys) ? keys : (keys?.checked || []))
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
  // 走 mergeVisibleSelection：筛选外的已选必须保留，否则"换个关键词再全选"会丢掉上一次的选择
  mergeVisibleSelection(filteredFiles.value.map((f) => f.path))
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
  /* 可查看的文件：看起来就该能点（下划线 + 手型），否则用户不会去试 */
  .name.clickable {
    color: #1677ff;
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
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
