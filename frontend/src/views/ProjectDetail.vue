<template>
  <div>
    <!--
      详情页原先整页不出现项目名，只靠侧边栏猜自己在哪；这里补面包屑 + 项目名 + 返回。
    -->
    <div class="detail-head">
      <a-breadcrumb>
        <a-breadcrumb-item><router-link to="/projects">项目</router-link></a-breadcrumb-item>
        <a-breadcrumb-item>{{ projectName }}</a-breadcrumb-item>
      </a-breadcrumb>
      <div class="detail-title-row">
        <h2 class="detail-title">{{ projectName }}</h2>
        <span v-if="project?.giteaUrl" class="detail-repo">{{ project.giteaUrl }}</span>
        <a-button size="small" @click="router.push('/projects')">返回项目列表</a-button>
      </div>
    </div>

    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="review" tab="代码审查">
        <LoadErrorAlert :message="pageError" @retry="loadPage" />
        <SplitPane left-title="审查配置" right-title="审查结果">
          <template #left>
            <div class="select-row">
              <span class="select-label">分支</span>
              <a-select
                v-model:value="branch"
                style="width: 200px"
                placeholder="选择分支"
                :loading="branchLoading"
                @change="onBranchChange"
              >
                <a-select-option v-for="b in branches" :key="b" :value="b">{{ b }}</a-select-option>
              </a-select>
            </div>

            <a-tabs v-model:active-key="viewTab" type="card" size="small">
              <!-- ---------------- 结构视图 ---------------- -->
              <a-tab-pane key="structure" tab="结构视图">
                <div class="select-row">
                  <span class="select-label">策略</span>
                  <a-select
                    v-model:value="structureStrategyId"
                    style="width: 220px"
                    placeholder="选择审查策略"
                    :options="structureStrategyOptions"
                  >
                    <template #notFoundContent>
                      <div>暂无策略，<router-link to="/strategies">去创建</router-link></div>
                    </template>
                  </a-select>
                </div>
                <div v-if="structureAnalyzerType === 1" class="select-row">
                  <a-checkbox v-model:checked="mergeFiles">多文件合并审查</a-checkbox>
                </div>
                <div class="tree-search">
                  <a-input
                    v-model:value="structureKeyword"
                    size="small"
                    placeholder="按路径搜索"
                    allow-clear
                  />
                </div>
                <div class="tree-toolbar">
                  <a-space :size="4">
                    <a-button size="small" @click="expandAll">展开全部</a-button>
                    <a-button size="small" @click="collapseAll">收起全部</a-button>
                    <a-button size="small" @click="selectAllStructure">
                      {{ structureKeyword ? '全选筛选结果' : '全选' }}
                    </a-button>
                    <a-button size="small" @click="clearStructureChecked">清空</a-button>
                  </a-space>
                  <span class="count">{{ structureSelectionText }}</span>
                  <a-button
                    type="primary"
                    size="small"
                    :loading="triggering"
                    :disabled="!structureStrategyId"
                    @click="openConfirm('structure')"
                  >
                    开始审查
                  </a-button>
                </div>
                <a-spin :spinning="treeLoading">
                  <a-alert v-if="treeError" type="error" show-icon :message="treeError" class="mb8">
                    <template #action>
                      <a-button size="small" @click="loadTree">重试</a-button>
                    </template>
                  </a-alert>
                  <a-empty
                    v-else-if="!treeLoading && !structureTree.length"
                    :description="structureKeyword ? '没有匹配的文件' : '该分支无文件'"
                  />
                  <a-tree
                    v-else
                    checkable
                    :tree-data="structureTree"
                    :checked-keys="structureChecked"
                    v-model:expanded-keys="expandedKeys"
                    @check="onStructureCheck"
                    @select="onStructureSelect"
                  >
                    <template #title="{ dataRef }">
                      <!--
                        可查看的文本文件做成可点击（点击=看内容）；二进制/无扩展名文件保持普通文本。
                        判定用**后端下发的 reviewable**，前端不自己维护扩展名表。
                        点击处理统一走 @select（antd 的节点点击），这里只负责样式与提示。
                      -->
                      <span v-if="dataRef.isFile && dataRef.reviewable" class="file-link">
                        {{ dataRef.title }}
                      </span>
                      <span v-else-if="dataRef.isFile" class="not-viewable" title="非文本文件，无法查看内容">
                        {{ dataRef.title }}
                      </span>
                      <span v-else>{{ dataRef.title }}</span>
                    </template>
                  </a-tree>
                </a-spin>
              </a-tab-pane>

              <!-- ---------------- 提交视图（独立入口） ---------------- -->
              <a-tab-pane key="commit" tab="提交视图">
                <div class="select-row">
                  <span class="select-label">策略</span>
                  <a-select
                    v-model:value="commitStrategyId"
                    style="width: 220px"
                    placeholder="选择 diff 审查策略"
                    :options="commitStrategyOptions"
                  >
                    <template #notFoundContent>
                      <div>暂无 diff-review 策略，<router-link to="/strategies">去创建</router-link></div>
                    </template>
                  </a-select>
                </div>
                <div class="tree-toolbar">
                  <span class="hint">先选提交，再勾选要审查的变更文件</span>
                  <a-button
                    type="primary"
                    size="small"
                    :loading="triggering"
                    :disabled="!commitStrategyId || !selectedCommit"
                    @click="openConfirm('commit')"
                  >
                    开始审查
                  </a-button>
                </div>
                <CommitTable
                  :commits="commits"
                  :selected="selectedCommit"
                  :has-more="hasMoreCommits"
                  :loading="commitsLoading"
                  :loading-more="commitsLoadingMore"
                  :error="commitsError"
                  @select="onSelectCommit"
                  @load-more="loadMoreCommits"
                  @retry="loadCommits(true)"
                />
                <a-divider style="margin: 8px 0" />
                <ChangedFileTree
                  :files="commitDetail?.files || []"
                  v-model:checked="commitChecked"
                  :loading="detailLoading"
                  :error="detailError"
                  :truncated="!!commitDetail?.truncated"
                  @retry="loadCommitDetail(selectedCommit)"
                  @view="openCommitDiff"
                />
              </a-tab-pane>
            </a-tabs>
          </template>

          <template #right>
            <LoadErrorAlert :message="marksError" @retry="retryMarks" />
            <ReviewResult
              :record="review"
              :project-id="projectId"
              :marks="marks"
              :retrying="retrying"
              :poll-error="pollError"
              @retry="askRetryUnits"
              @resume-poll="resumePoll"
              @mark="onMark"
            />
          </template>
        </SplitPane>
      </a-tab-pane>

      <!-- ---------------- 审查记录（含准确率汇总） ---------------- -->
      <a-tab-pane key="records" tab="审查记录">
        <LoadErrorAlert :message="accuracyError" @retry="loadAccuracy" />
        <AccuracyBar :stats="accuracy" :loading="accuracyLoading" />
        <a-divider style="margin: 12px 0" />
        <LoadErrorAlert :message="recordsError" @retry="retryRecords" />
        <a-table
          :data-source="records"
          row-key="id"
          :loading="recordsLoading"
          :pagination="pagination"
          size="small"
          :scroll="{ x: 'max-content' }"
        >
          <a-table-column title="时间" data-index="createdAt" width="150" />
          <a-table-column title="耗时" width="90">
            <template #default="{ record }">{{ formatDuration(record.startedAt, record.finishedAt) }}</template>
          </a-table-column>
          <a-table-column title="分支" data-index="branch" width="100" ellipsis />
          <a-table-column title="提交" data-index="commitSha" width="80">
            <template #default="{ text }">{{ text ? text.slice(0, 7) : '—' }}</template>
          </a-table-column>
          <a-table-column title="策略" data-index="strategyName" width="130" ellipsis />
          <a-table-column title="状态" data-index="status" width="85">
            <template #default="{ text }">
              <a-tag :color="recordStatusColor(text)">{{ recordStatusText(text) }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="进度" data-index="progress" width="70">
            <template #default="{ text }">{{ text }}%</template>
          </a-table-column>
          <a-table-column title="操作" width="140">
            <template #default="{ record }">
              <a-space>
                <a-button size="small" @click="viewRecord(record)">查看</a-button>
                <!-- 置灰要有解释：成功/执行中的记录为什么不能重审。tooltip 放在 span 上，
                     因为禁用的 button 不触发鼠标事件（antd 的推荐写法）。 -->
                <a-tooltip :title="canRetry(record.status) ? '' : '仅失败或部分成功的审查可重审'">
                  <span>
                    <a-button
                      size="small"
                      :disabled="!canRetry(record.status)"
                      @click="askRetryRecord(record)"
                    >
                      重审
                    </a-button>
                  </span>
                </a-tooltip>
              </a-space>
            </template>
          </a-table-column>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="report" tab="报告生成">
        <ReportPanel :project-id="projectId" />
      </a-tab-pane>
    </a-tabs>

    <!-- 触发前确认：汇总分支/提交/范围/策略/预估单元数 -->
    <a-modal v-model:open="confirmOpen" title="确认触发审查" :confirm-loading="triggering" @ok="doTrigger">
      <a-descriptions :column="1" size="small" bordered>
        <a-descriptions-item label="分支">{{ branch }}</a-descriptions-item>
        <a-descriptions-item v-if="confirmMode === 'commit'" label="提交">
          {{ selectedCommit.slice(0, 7) }} · {{ (commitDetail?.message || '').split('\n')[0] }}
        </a-descriptions-item>
        <a-descriptions-item label="审查范围">
          {{ confirmScope.length }} 个文件
          <span v-if="confirmSkippedCount">（其中 {{ confirmSkippedCount }} 个不可审查）</span>
        </a-descriptions-item>
        <a-descriptions-item label="策略">{{ confirmStrategyName }}</a-descriptions-item>
        <a-descriptions-item label="预估单元数">{{ confirmReviewableCount }}</a-descriptions-item>
      </a-descriptions>
      <a-alert
        v-if="confirmSkippedCount"
        type="warning"
        show-icon
        class="mt12"
        message="所选范围里有部分文件不在可审查名单内，本次会跳过它们、只审查其余文件"
      />
      <a-alert
        v-if="confirmReviewableCount > 50"
        type="warning"
        show-icon
        class="mt12"
        message="单元数超过上限 50，后端会直接拒绝；请减少勾选文件或分批审查"
      />
    </a-modal>

    <!-- 重审二次确认：重审会真花额度并覆盖上一次的结果，误点没有撤销入口 -->
    <a-modal
      v-model:open="retryConfirmOpen"
      :title="retryConfirmTitle"
      :confirm-loading="retryConfirmLoading"
      ok-text="确认重审"
      cancel-text="取消"
      @ok="confirmRetry"
    >
      <p class="retry-confirm-text">{{ retryConfirmMessage }}</p>
    </a-modal>

    <!-- 文件查看：结构视图看内容、提交视图看该提交的差异（同一个壳，两种模式） -->
    <FileViewerModal
      v-model:open="fileViewerOpen"
      :project-id="projectId"
      :path="fileViewerPath"
      :git-ref="fileViewerRef"
      :mode="fileViewerMode"
    />

    <!-- 查看历史记录：全屏只读快照，不影响页签里正在进行的审查 -->
    <ReviewRecordViewer
      v-model:open="recordViewerOpen"
      :record-id="recordViewerId"
      :project-id="projectId"
      :strategies="strategies"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  getBranches,
  getTree,
  listCommitPage,
  getCommitDetail,
  getAccuracy,
  getProject,
  type ProjectItem,
  type TreeNode,
  type CommitInfo,
  type CommitDetail
} from '@/api/project'
import {
  triggerReview,
  getReview,
  retryReview,
  listReviews,
  listMarks,
  markIssue,
  type ReviewRecord,
  type ReviewRecordRow,
  type IssueMark
} from '@/api/review'
import { listStrategies } from '@/api/strategy'
import type { ChangedFile } from '@/utils/changedFiles'
import { canRetry, recordStatusColor, recordStatusText } from '@/utils/reviewResult'
import { formatDuration } from '@/utils/duration'
import { useRecordPagination } from '@/utils/useRecordPagination'
import type { AccuracyStat } from '@/utils/accuracy'
import ReportPanel from '@/components/ReportPanel.vue'
import SplitPane from '@/components/SplitPane.vue'
import CommitTable from '@/components/CommitTable.vue'
import ChangedFileTree from '@/components/ChangedFileTree.vue'
import ReviewResult from '@/components/ReviewResult.vue'
import ReviewRecordViewer from '@/components/ReviewRecordViewer.vue'
import FileViewerModal from '@/components/FileViewerModal.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import AccuracyBar from '@/components/AccuracyBar.vue'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id as string

/** 项目名/仓库地址：详情页头部与面包屑用；拉取失败也不阻塞审查主流程（只显示兜底文案） */
const project = ref<ProjectItem | null>(null)
const projectName = computed(() => project.value?.name || `项目 #${projectId}`)

const activeTab = ref('review')
const viewTab = ref('structure')

// ---------------- 分支（两个视图共用，切换时整体重置） ----------------
const branches = ref<string[]>([])
const branch = ref('')
const branchLoading = ref(false)

// ---------------- 结构视图 ----------------
const treeData = ref<any[]>([])
const treeLoading = ref(false)
const treeError = ref('')
const structureChecked = ref<string[]>([])
const expandedKeys = ref<string[]>([])
const allExpandableKeys = ref<string[]>([])
const structureStrategyId = ref('')
const mergeFiles = ref(false)
const structureKeyword = ref('')
const fileCount = ref(0)
let fileSet = new Set<string>()

/**
 * 结构视图的搜索与「全选」。
 *
 * 与提交视图的 ChangedFileTree 保持同一套语义：搜索只影响**显示与全选范围**，
 * 已勾选但被搜掉的路径仍留在选中集里（不会因为敲了几个字就静默丢选择）。
 */
const structureMatchedFiles = computed(() => {
  const keyword = structureKeyword.value.trim().toLowerCase()
  const paths = [...fileSet]
  if (!keyword) return paths
  return paths.filter((path) => path.toLowerCase().includes(keyword))
})

const structureTree = computed(() => filterTree(treeData.value, structureKeyword.value.trim().toLowerCase()))

/**
 * 「已选 N / …」文案。
 *
 * 搜索时 `已选` 只数**筛选结果内**的选中项，另外把"被搜索藏起来的已选"显式说出来：
 * 搜索只隐藏不改选中集，若不提示，用户会以为审查范围就是眼前这几个文件。
 */
const structureSelectionText = computed(() => {
  if (!structureKeyword.value) {
    // 标签用"共 N 个文件"而不是"可审查 N"：fileCount 统计的是全部 blob，
    // 而不可审查的文件现在同样可勾选（提交时统一告知哪些会被跳过），叫"可审查"会误导。
    return `已选 ${structureChecked.value.length} / 共 ${fileCount.value} 个文件`
  }
  const matched = new Set(structureMatchedFiles.value)
  const inFilter = structureChecked.value.filter((key) => matched.has(key))
  const hidden = structureChecked.value.length - inFilter.length
  const hiddenText = hidden ? `（另有 ${hidden} 个已选不在筛选中）` : ''
  return `已选 ${inFilter.length} / 匹配 ${structureMatchedFiles.value.length}${hiddenText}`
})

/**
 * 按关键词裁剪文件树：命中子节点的目录保留，目录自身命中则保留整棵子树。
 * 只按 path（完整路径）判断，与 `structureMatchedFiles` 口径一致 ——
 * 否则会出现"计数说匹配 3 个、树上却看得到 5 个"的错位。
 */
function filterTree(nodes: any[], keyword: string): any[] {
  if (!keyword) return nodes
  const walk = (list: any[]): any[] => {
    const result: any[] = []
    for (const node of list) {
      const children = Array.isArray(node.children) ? walk(node.children) : []
      if (children.length) {
        result.push({ ...node, children })
      } else if (String(node.key ?? '').toLowerCase().includes(keyword)) {
        result.push({ ...node, children: node.children })
      }
    }
    return result
  }
  return walk(nodes)
}

function keysOfTree(nodes: any[]): string[] {
  const keys: string[] = []
  const walk = (list: any[]) => {
    for (const node of list) {
      if (node.children?.length) {
        keys.push(node.key)
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return keys
}

// 搜索后自动展开，省去用户逐层点开
watch(structureKeyword, () => {
  if (structureKeyword.value) {
    expandedKeys.value = keysOfTree(structureTree.value)
  }
})

/**
 * 把一次勾选的结果并回选中集：**只改"当前筛选下可见"的那部分，被搜掉的保持原样**。
 *
 * <p>不能直接 `structureChecked.value = 回写结果`：`a-tree` 只认识当前 `treeData` 里的节点，
 * 过滤后回写的 keys **不含被搜掉的已选**。直接覆盖就变成"换一个关键词再勾，
 * 上一次勾的全没了" —— 与本视图声明的语义（"搜索只影响显示与全选范围，
 * 已勾选但被搜掉的路径仍留在选中集里"）正好相反。
 */
function mergeVisibleSelection(selectedVisible: string[]) {
  const visible = new Set(structureMatchedFiles.value)
  const kept = structureChecked.value.filter((k) => !visible.has(k))
  const now = selectedVisible.filter((k) => visible.has(k))
  structureChecked.value = [...new Set([...kept, ...now])]
}

/** 全选**当前筛选结果**（与 ChangedFileTree 的 selectAllFiltered 同义）；筛选外的已选保留 */
function selectAllStructure() {
  mergeVisibleSelection([...structureMatchedFiles.value])
}

/**
 * `a-tree` 的勾选回写。
 *
 * <p>**必须过滤掉目录键**：`a-tree` 默认父子联动（`checkStrictly=false`），当过滤后的树里
 * 某个目录的**可见**子节点被全部勾上时，它会把**该目录自身的 key** 也一并回写进
 * `checkedKeys`。原实现用 `v-model:checked-keys` 原样落库，于是清空搜索、整棵树恢复后，
 * 那个目录键仍然在选中集里 —— 一勾就等于勾上它**全部子文件**：
 * 视觉上"所有文件都被选中"，计数也把目录算了进去。
 *
 * <p>改成 `:checked-keys` + `@check` 是为了拿回写入权；再经 {@link mergeVisibleSelection}
 * 并回，避免"换关键词再勾"时把筛选外的已选一起抹掉。展示不受影响：存进去的都是叶子键，
 * antd 自己会把父目录推导成选中/半选。
 *
 * <p>口径与 {@link ChangedFileTree} 的 `onCheck` 一致。
 */
function onStructureCheck(keys: any) {
  // checkStrictly 打开时 antd 回传 { checked, halfChecked }，两种形状都兜住
  const list: string[] = Array.isArray(keys) ? keys : (keys?.checked || [])
  mergeVisibleSelection(list.filter((k) => fileSet.has(k)))
}

function clearStructureChecked() {
  structureChecked.value = []
}

/**
 * 点节点 = 看文件内容（仅限"可查看的文本文件"）。
 *
 * 目录不弹窗（点它只应展开/折叠），不可查看的文件也不弹窗 —— 它们没有可展示的文本；
 * 勾选走的是复选框（`@check`），与这里互不影响。
 */
function onStructureSelect(_selectedKeys: any, info: any) {
  const node = info?.node?.dataRef ?? info?.node
  if (node?.isFile && node?.reviewable) {
    openFileContent(node.path)
  }
}

// ---------------- 提交视图（独立选中集） ----------------
const commits = ref<CommitInfo[]>([])
const commitPage = ref(1)
const hasMoreCommits = ref(false)
const commitsLoading = ref(false)
const commitsLoadingMore = ref(false)
const commitsError = ref('')
const selectedCommit = ref('')
const commitDetail = ref<CommitDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const commitChecked = ref<string[]>([])
const commitStrategyId = ref('')

// ---------------- 策略 ----------------
const strategies = ref<{ value: string; label: string; analyzerType: number }[]>([])
const structureStrategyOptions = computed(() => strategies.value.filter((s) => s.analyzerType !== 5))
// diff-review 只在提交视图可选
const commitStrategyOptions = computed(() => strategies.value.filter((s) => s.analyzerType === 5))
const structureAnalyzerType = computed(
  () => strategies.value.find((s) => s.value === structureStrategyId.value)?.analyzerType
)

// ---------------- 审查执行 ----------------
const triggering = ref(false)
const retrying = ref(false)
const review = ref<ReviewRecord | null>(null)
const marks = ref<IssueMark[]>([])
/** 标记/准确率拉取失败的原因：失败时保留上一次的数据并显式提示，不再静默清空 */
const marksError = ref('')
let pollTimer: number | undefined
/** 当前轮询的记录 id（供「继续等待」恢复用，不依赖 review 是否还在） */
let polledId = ''
/** 连续失败次数（成功一次即清零） */
let pollFailures = 0
/** 非空表示"轮询已因连续失败而停止"，界面给出恢复入口 */
const pollError = ref('')

// ---------------- 确认框 ----------------
const confirmOpen = ref(false)
const confirmMode = ref<'structure' | 'commit'>('structure')
const confirmScope = ref<string[]>([])
const confirmStrategyName = ref('')
/**
 * 可审查路径集合 —— **直接来自后端下发的 `reviewable`**（文件树节点 / 变更文件各一份），
 * 前端不维护扩展名表。判定权只在后端 `ReviewableFiles` 一处。
 */
let structureReviewableSet = new Set<string>()
let commitReviewableSet = new Set<string>()

function reviewableSetFor(mode: 'structure' | 'commit'): Set<string> {
  return mode === 'commit' ? commitReviewableSet : structureReviewableSet
}

/** 本次范围里可审查的文件数 —— 也就是实际会执行的工作量（预估单元数） */
const confirmReviewableCount = computed(
  () => confirmScope.value.filter((p) => reviewableSetFor(confirmMode.value).has(p)).length
)

/** 本次范围里会被后端跳过的文件数 */
const confirmSkippedCount = computed(() => confirmScope.value.length - confirmReviewableCount.value)

// ---------------- 记录与准确率 ----------------
// 分页状态由 useRecordPagination 持有（与"报告-选择审查记录"共用同一套分页语义）
const accuracy = ref<AccuracyStat[]>([])
const accuracyLoading = ref(false)
const accuracyError = ref('')

// ---------------- 记录只读查看（全屏模态框） ----------------
// 只持有"看哪一条"：完整记录与标记由弹窗自己按 id 拉，不污染页签里正在进行的审查
const recordViewerOpen = ref(false)
const recordViewerId = ref('')

// ---------------- 文件查看 ----------------
// 结构视图看的是"这个文件的内容"（ref=当前分支）；提交视图看的是"这个提交对它的差异"（ref=sha）
const fileViewerOpen = ref(false)
const fileViewerPath = ref('')
const fileViewerRef = ref('')
const fileViewerMode = ref<'content' | 'diff'>('content')

function openFileContent(path: string) {
  fileViewerPath.value = path
  fileViewerRef.value = branch.value
  fileViewerMode.value = 'content'
  fileViewerOpen.value = true
}

function openCommitDiff(path: string) {
  fileViewerPath.value = path
  fileViewerRef.value = selectedCommit.value
  fileViewerMode.value = 'diff'
  fileViewerOpen.value = true
}

// =====================================================================
// 分支
// =====================================================================

async function loadBranches() {
  branchLoading.value = true
  try {
    branches.value = await getBranches(projectId)
    branch.value = branches.value[0] || ''
    if (branch.value) {
      await loadTree()
      await loadCommits(true)
    }
  } finally {
    branchLoading.value = false
  }
}

/** 切换分支：两个视图的选中集与提交选择**整体重置**（Q40） */
async function onBranchChange() {
  structureChecked.value = []
  commitChecked.value = []
  selectedCommit.value = ''
  commitDetail.value = null
  detailError.value = ''
  await loadTree()
  await loadCommits(true)
}

// =====================================================================
// 结构视图
// =====================================================================

/**
 * 结构树 → antd 树节点。
 *
 * 刻意把 `isFile` 与 `reviewable` 带下去：前者区分"文件 / 目录"（目录同样有 path，
 * 但点它应该只展开，不该弹窗），后者决定"这个文件能不能看内容"。
 * 判定来源是**后端下发的 reviewable**，前端不自己维护扩展名表。
 */
function toTreeNodes(nodes: TreeNode[]): any[] {
  return nodes.map((n) => ({
    title: n.name,
    key: n.path,
    path: n.path,
    isFile: n.type === 'blob',
    reviewable: n.reviewable === true,
    children: n.children?.length ? toTreeNodes(n.children) : undefined
  }))
}

function flattenFiles(nodes: TreeNode[]): Set<string> {
  const set = new Set<string>()
  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.type === 'blob') set.add(n.path)
      if (n.children) walk(n.children)
    }
  }
  walk(nodes)
  return set
}

/**
 * 收集可审查的 blob 路径。
 *
 * `reviewable` 由后端判定（`ReviewableFiles`），这里只做收集 ——
 * 前端不再自己维护扩展名表，也就不会再和后端漂移。
 */
function collectReviewable(nodes: TreeNode[]): Set<string> {
  const set = new Set<string>()
  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.type === 'blob' && n.reviewable) set.add(n.path)
      if (n.children) walk(n.children)
    }
  }
  walk(nodes)
  return set
}

function collectExpandableKeys(nodes: TreeNode[]): string[] {
  const keys: string[] = []
  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.children?.length) {
        keys.push(n.path)
        walk(n.children)
      }
    }
  }
  walk(nodes)
  return keys
}

async function loadTree() {
  if (!branch.value) return
  treeLoading.value = true
  treeError.value = ''
  try {
    const nodes = await getTree(projectId, branch.value)
    treeData.value = toTreeNodes(nodes)
    fileSet = flattenFiles(nodes)
    structureReviewableSet = collectReviewable(nodes)
    // fileSet 是普通 Set（非响应式），计数要单独用 ref 暴露给模板
    fileCount.value = fileSet.size
    allExpandableKeys.value = collectExpandableKeys(nodes)
  } catch (e: any) {
    treeData.value = []
    fileCount.value = 0
    treeError.value = e?.message || '文件树加载失败'
  } finally {
    treeLoading.value = false
  }
}

function expandAll() {
  expandedKeys.value = [...allExpandableKeys.value]
}

function collapseAll() {
  expandedKeys.value = []
}

// =====================================================================
// 提交视图
// =====================================================================

async function loadCommits(reset: boolean) {
  if (!branch.value) return
  if (reset) {
    commitPage.value = 1
    commits.value = []
    commitsError.value = ''
    commitsLoading.value = true
  } else {
    commitsLoadingMore.value = true
  }
  try {
    const page = await listCommitPage(projectId, branch.value, commitPage.value)
    commits.value = reset ? page.commits : [...commits.value, ...page.commits]
    hasMoreCommits.value = page.hasMore
    if (reset && page.commits.length) {
      // 默认选中最新提交（Q40）
      await onSelectCommit(page.commits[0].sha)
    }
  } catch (e: any) {
    commitsError.value = e?.message || '提交列表加载失败'
    hasMoreCommits.value = false
  } finally {
    commitsLoading.value = false
    commitsLoadingMore.value = false
  }
}

async function loadMoreCommits() {
  if (!hasMoreCommits.value || commitsLoadingMore.value) return
  commitPage.value += 1
  await loadCommits(false)
}

async function onSelectCommit(sha: string) {
  selectedCommit.value = sha
  commitChecked.value = []
  await loadCommitDetail(sha)
}

/**
 * 拉取单提交详情（列表视图，不含 patch）；**默认全选全部变更文件**。
 *
 * 原先是"默认只选可审查的"（把二进制排除在外）—— 那是一种静默预筛：用户看到的勾选状态
 * 与实际范围已经不一致，却没有任何提示。现在统一为"全都能勾，提交时告知哪些会被跳过"，
 * 默认选中就不再偷偷剔除任何文件。
 */
async function loadCommitDetail(sha: string) {
  if (!sha) return
  detailLoading.value = true
  detailError.value = ''
  try {
    const detail = await getCommitDetail(projectId, sha)
    commitDetail.value = detail
    const files: ChangedFile[] = detail.files || []
    commitChecked.value = files.map((f) => f.path)
    // reviewable 来自后端下发的字段
    commitReviewableSet = new Set(files.filter((f) => f.reviewable).map((f) => f.path))
  } catch (e: any) {
    commitDetail.value = null
    commitReviewableSet = new Set()
    detailError.value = e?.message || '变更文件加载失败'
  } finally {
    detailLoading.value = false
  }
}

// =====================================================================
// 策略
// =====================================================================

async function loadStrategies() {
  const page = (await listStrategies({ pageNum: 1, pageSize: 100 })) as any
  strategies.value = (page?.records || []).map((s: any) => ({
    value: s.id,
    label: s.name,
    analyzerType: s.analyzerType
  }))
  const firstDiff = strategies.value.find((s) => s.analyzerType === 5)
  if (firstDiff) commitStrategyId.value = firstDiff.value
  const firstOther = strategies.value.find((s) => s.analyzerType !== 5)
  if (firstOther) structureStrategyId.value = firstOther.value
}

/**
 * 页面级加载：分支/文件树 与 策略表。
 *
 * 这两处原先失败是**静默**的 —— `loadBranches` 完全没有 catch（未处理的 rejection），
 * `loadStrategies` 的 catch 里只写了句"策略加载失败不阻塞"。后果是：分支下拉空、
 * 文件树与提交列表永不加载、策略下拉空导致「开始审查」永远点不动，
 * 而界面上没有任何提示能解释为什么。
 */
const pageError = ref('')

async function loadProject() {
  project.value = await getProject(projectId)
}

async function loadPage() {
  pageError.value = ''
  const failures: string[] = []
  await Promise.all([
    loadProject().catch(() => failures.push('项目信息')),
    loadBranches().catch(() => failures.push('分支与文件树')),
    loadStrategies().catch(() => failures.push('策略'))
  ])
  if (failures.length) {
    pageError.value = `以下数据加载失败：${failures.join('、')}，请检查后端服务后重试`
  }
}

// =====================================================================
// 触发
// =====================================================================

function openConfirm(mode: 'structure' | 'commit') {
  confirmMode.value = mode
  if (mode === 'structure') {
    confirmScope.value = structureChecked.value.filter((k) => fileSet.has(k))
    confirmStrategyName.value = strategies.value.find((s) => s.value === structureStrategyId.value)?.label || ''
    if (!confirmScope.value.length) {
      message.warning('请先勾选要审查的文件（可勾选目录批量选择）')
      return
    }
  } else {
    confirmScope.value = [...commitChecked.value]
    confirmStrategyName.value = strategies.value.find((s) => s.value === commitStrategyId.value)?.label || ''
    if (!confirmScope.value.length) {
      message.warning('请先勾选要审查的变更文件')
      return
    }
  }
  // 一个可审查文件都没有：在点下去之前就拦住，不产生一条注定失败的记录
  // （后端 ReviewService/ReviewExecutor 有同样的兜底，用于绕过前端的调用）
  if (!confirmReviewableCount.value) {
    message.warning('所选文件中没有可审查的文件，请改选后再试')
    return
  }
  confirmOpen.value = true
}

async function doTrigger() {
  const isCommit = confirmMode.value === 'commit'
  triggering.value = true
  try {
    const record = await triggerReview(projectId, {
      branch: branch.value,
      strategyId: isCommit ? commitStrategyId.value : structureStrategyId.value,
      scope: confirmScope.value,
      mergeFiles: isCommit ? undefined : mergeFiles.value,
      // diff 审查必须锚定到具体提交
      commitSha: isCommit ? selectedCommit.value : undefined
    })
    review.value = record
    marks.value = []
    marksError.value = ''
    confirmOpen.value = false
    if (record.status < 2) {
      startPoll(record.id)
    } else {
      await loadMarks(record.id)
    }
  } catch (e: any) {
    message.error(e?.message || '触发失败')
  } finally {
    triggering.value = false
  }
}

// ---------------- 重审二次确认 ----------------
/**
 * 重审会真的再调一次大模型：既消耗额度，又会覆盖上一次的结果，误点没有撤销入口。
 * 两个入口（结果区的"重审失败单元"、记录列表的"重审"）合并到同一个确认框，
 * 确认后才真正发请求。
 */
const retryConfirmOpen = ref(false)
const retryConfirmLoading = ref(false)
const retryTarget = ref<{ kind: 'units'; id: string } | { kind: 'record'; record: ReviewRecordRow } | null>(null)

const retryConfirmTitle = computed(() =>
  retryTarget.value?.kind === 'units' ? '确认重审失败单元' : '确认重审该次审查'
)

const retryConfirmMessage = computed(() => {
  const target = retryTarget.value
  if (!target) return ''
  if (target.kind === 'units') {
    return '将重新调用大模型审查这次失败的单元。会消耗额度，并覆盖这次的结果。'
  }
  const sha = target.record.commitSha ? `（提交 ${target.record.commitSha.slice(0, 7)}）` : ''
  return `将重新审查这条记录${sha}。会消耗额度，并覆盖原有结果。`
})

function askRetryUnits() {
  if (!review.value) return
  retryTarget.value = { kind: 'units', id: review.value.id }
  retryConfirmOpen.value = true
}

function askRetryRecord(record: ReviewRecordRow) {
  retryTarget.value = { kind: 'record', record }
  retryConfirmOpen.value = true
}

async function confirmRetry() {
  const target = retryTarget.value
  if (!target) return
  retryConfirmLoading.value = true
  try {
    if (target.kind === 'units') {
      await doRetryUnits()
    } else {
      await doRetryRecord(target.record)
    }
    retryConfirmOpen.value = false
    retryTarget.value = null
  } finally {
    retryConfirmLoading.value = false
  }
}

async function doRetryUnits() {
  if (!review.value) return
  retrying.value = true
  try {
    await retryReview(review.value.id)
    review.value.status = 1
    review.value.progress = 0
    message.success('已提交重审')
    startPoll(review.value.id)
  } catch (e: any) {
    message.error(e?.message || '重审失败')
  } finally {
    retrying.value = false
  }
}

async function doRetryRecord(record: ReviewRecordRow) {
  try {
    await retryReview(record.id)
    message.success('已提交重审')
    // 停在第 1 页是因为重审后这条记录会回到列表最前
    await reloadRecordsFromFirstPage()
  } catch (e: any) {
    message.error(e?.message || '重审失败')
  }
}

/** 轮询失败连续达到这个次数才停：单次抖动（网关重启、偶发 5xx）不该让进度永久停住 */
const MAX_POLL_FAILURES = 3

function stopPoll() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

/**
 * 启动进度轮询。
 *
 * 原先的实现里 **任何一次** 请求失败就 `clearInterval` 静默退出 —— 界面永远停在最后一次
 * 进度上，既不报错也没有恢复入口，用户只能刷新页面。现在改成：
 * 连续失败到 {@link MAX_POLL_FAILURES} 次才停，并给出「继续等待」让用户手动恢复。
 */
function startPoll(id: string) {
  stopPoll()
  polledId = id
  pollFailures = 0
  pollError.value = ''
  pollTimer = window.setInterval(() => void pollOnce(id), 2000)
}

async function pollOnce(id: string) {
  try {
    const r = await getReview(id)
    pollFailures = 0
    pollError.value = ''
    review.value = r
    if (r.status >= 2) {
      stopPoll()
      await loadMarks(id)
      // 新完成的审查一定排在最前，回到第 1 页才看得到
      await Promise.all([reloadRecordsFromFirstPage(), loadAccuracy()])
    }
  } catch (e: any) {
    pollFailures += 1
    if (pollFailures >= MAX_POLL_FAILURES) {
      stopPoll()
      pollError.value = e?.message || '进度获取失败'
    }
    // 未达阈值就静静等下一次 tick
  }
}

/**
 * 「继续等待」：立即恢复轮询（常用于后端刚重启完的情况）。
 *
 * 用记录的 `polledId` 而不是 `review.value?.id` —— 后者在 `review` 为空时会静默什么都不做。
 */
function resumePoll() {
  if (!polledId) {
    return
  }
  startPoll(polledId)
  void pollOnce(polledId)
}

// =====================================================================
// 标记与准确率
// =====================================================================

async function loadMarks(recordId: string) {
  try {
    marks.value = await listMarks(recordId)
    marksError.value = ''
  } catch (e: any) {
    // 失败**不清空**：已点的"误报/已采纳"会整批消失，看起来像从没标过（真缺陷）
    marksError.value = e?.message || '标记加载失败'
  }
}

function retryMarks() {
  if (review.value) {
    void loadMarks(review.value.id)
  }
}

async function onMark(unitPath: string, issueIndex: number, markValue: number) {
  if (!review.value) return
  try {
    await markIssue(review.value.id, { unitPath, issueIndex, markValue })
    await loadMarks(review.value.id)
    await loadAccuracy()
  } catch (e: any) {
    message.error(e?.message || '标记失败')
  }
}

async function loadAccuracy() {
  accuracyLoading.value = true
  try {
    accuracy.value = await getAccuracy(projectId)
    accuracyError.value = ''
  } catch (e: any) {
    // 失败不清空：AccuracyBar 会把空数组渲染成"暂无审查结果，无法统计准确率"，
    // 于是**接口失败被误报成"本来就没有结果"**（指标 5 的口径会因此失真）
    accuracyError.value = e?.message || '准确率加载失败'
  } finally {
    accuracyLoading.value = false
  }
}

/**
 * 拉取当前页（服务端分页）。
 *
 * 准确率汇总刻意**不在这里**重拉：它是项目维度的聚合，翻页不会改变它，
 * 每次翻页重拉纯属浪费，还会让汇总条闪一下 loading。
 */
async function loadRecordsPage(params: { pageNum: number; pageSize: number }) {
  // 失败就抛：错误信息由 useRecordPagination 兜住并暴露到界面（不再"失败即清空列表"）
  return await listReviews(projectId, params)
}

const {
  records,
  loading: recordsLoading,
  error: recordsError,
  pagination,
  fetchPage: retryRecords,
  reloadFromFirstPage: reloadRecordsFromFirstPage
} = useRecordPagination<ReviewRecordRow>({ loader: loadRecordsPage })

/**
 * 查看历史记录：开全屏只读弹窗，**不切页签**。
 *
 * 只把 id 交给弹窗，由弹窗自己拉完整记录与标记 —— 列表接口刻意不返回 `resultJson`
 * （单条可达 MB 级），取数逻辑放一处，免得每个调用方各写一遍。
 */
function viewRecord(record: ReviewRecordRow) {
  recordViewerId.value = record.id
  recordViewerOpen.value = true
}

onMounted(async () => {
  await Promise.all([loadPage(), loadAccuracy()])
})

onUnmounted(() => {
  stopPoll()
})

// 供测试驱动轮询：轮询是"失败不再永久停止"这条契约的唯一实现处，
// 而它由 setInterval 驱动，只能通过实例入口配合假定时器验证。
// `loadMarks/marks/marksError` 同理：标记失败"不清空"这条契约藏在 async 分支里，
// 没有别的可观察入口（右边栏只在触发审查后才渲染）。
defineExpose({ startPoll, resumePoll, pollError, stopPoll, loadMarks, marks, marksError })
</script>

<style scoped lang="less">
.detail-head {
  margin-bottom: 12px;
}
.detail-title-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-top: 6px;
  /* 窄屏（笔记本 1366 + 125% 缩放）下项目名+仓库地址+返回按钮会顶出一层横向滚动 */
  flex-wrap: wrap;
}
.detail-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  color: rgba(0, 0, 0, 0.88);
}
.detail-repo {
  flex: 1;
  min-width: 0;
  color: #8c8c8c;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.select-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.select-label {
  flex: none;
  width: 60px;
  color: rgba(0, 0, 0, 0.88);
}
.tree-search {
  margin-bottom: 8px;
}
.tree-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.count {
  margin-left: auto;
  color: #999;
  font-size: 12px;
  white-space: nowrap;
}
.hint {
  color: #999;
  font-size: 12px;
}
/* 可查看的文件：看起来就该能点；不可查看的保持灰色但可辨认 */
.file-link {
  color: #1677ff;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}
.not-viewable {
  color: #bfbfbf;
}
.mt12 {
  margin-top: 12px;
}
.retry-confirm-text {
  margin: 0;
  line-height: 22px;
}
:deep(.ant-tree-indent-unit) {
  width: 12px;
}
</style>
