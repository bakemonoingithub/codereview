<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="review" tab="代码审查">
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
                <div class="tree-toolbar">
                  <a-space>
                    <a-button size="small" @click="expandAll">展开全部</a-button>
                    <a-button size="small" @click="collapseAll">收起全部</a-button>
                  </a-space>
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
                  <a-empty v-else-if="!treeLoading && !treeData.length" description="该分支无文件" />
                  <a-tree
                    v-else
                    checkable
                    :tree-data="treeData"
                    v-model:checked-keys="structureChecked"
                    v-model:expanded-keys="expandedKeys"
                  />
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
                />
              </a-tab-pane>
            </a-tabs>
          </template>

          <template #right>
            <ReviewResult
              :record="review"
              :project-id="projectId"
              :marks="marks"
              :retrying="retrying"
              @retry="onRetry"
              @mark="onMark"
            />
          </template>
        </SplitPane>
      </a-tab-pane>

      <!-- ---------------- 审查记录（含准确率汇总） ---------------- -->
      <a-tab-pane key="records" tab="审查记录">
        <AccuracyBar :stats="accuracy" :loading="accuracyLoading" />
        <a-divider style="margin: 12px 0" />
        <a-table
          :data-source="records"
          row-key="id"
          :loading="recordsLoading"
          :pagination="false"
          size="small"
        >
          <a-table-column title="时间" data-index="createdAt" width="170" />
          <a-table-column title="分支" data-index="branch" width="110" />
          <a-table-column title="提交" data-index="commitSha" width="90">
            <template #default="{ text }">{{ text ? text.slice(0, 7) : '—' }}</template>
          </a-table-column>
          <a-table-column title="策略" data-index="strategyId" width="110" />
          <a-table-column title="状态" data-index="status" width="90">
            <template #default="{ text }">
              <a-tag :color="recordStatusColor(text)">{{ recordStatusText(text) }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="进度" data-index="progress" width="80">
            <template #default="{ text }">{{ text }}%</template>
          </a-table-column>
          <a-table-column title="操作" width="150">
            <template #default="{ record }">
              <a-space>
                <a-button size="small" @click="viewRecord(record)">查看</a-button>
                <a-button
                  size="small"
                  :disabled="record.status !== 3 && record.status !== 4"
                  @click="onRetryRecord(record)"
                >
                  重审
                </a-button>
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
          <span v-if="confirmMode === 'commit' && binaryCount">（另有 {{ binaryCount }} 个不可审查文件未选中）</span>
        </a-descriptions-item>
        <a-descriptions-item label="策略">{{ confirmStrategyName }}</a-descriptions-item>
        <a-descriptions-item label="预估单元数">{{ confirmScope.length }}</a-descriptions-item>
      </a-descriptions>
      <a-alert
        v-if="confirmScope.length > 50"
        type="warning"
        show-icon
        class="mt12"
        message="单元数超过上限 50，后端会直接拒绝；请减少勾选文件或分批审查"
      />
    </a-modal>

    <!-- 查看历史记录：全屏只读快照，不影响页签里正在进行的审查 -->
    <ReviewRecordViewer
      v-model:open="recordViewerOpen"
      :record="recordSnapshot"
      :project-id="projectId"
      :marks="snapshotMarks"
      :marks-loading="snapshotMarksLoading"
      :strategies="strategies"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  getBranches,
  getTree,
  listCommitPage,
  getCommitDetail,
  getAccuracy,
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
  type IssueMark
} from '@/api/review'
import { listStrategies } from '@/api/strategy'
import { summarize, type ChangedFile } from '@/utils/changedFiles'
import { recordStatusColor, recordStatusText } from '@/utils/reviewResult'
import type { AccuracyStat } from '@/utils/accuracy'
import ReportPanel from '@/components/ReportPanel.vue'
import SplitPane from '@/components/SplitPane.vue'
import CommitTable from '@/components/CommitTable.vue'
import ChangedFileTree from '@/components/ChangedFileTree.vue'
import ReviewResult from '@/components/ReviewResult.vue'
import ReviewRecordViewer from '@/components/ReviewRecordViewer.vue'
import AccuracyBar from '@/components/AccuracyBar.vue'

const route = useRoute()
const projectId = route.params.id as string

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
let fileSet = new Set<string>()

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
let pollTimer: number | undefined

// ---------------- 确认框 ----------------
const confirmOpen = ref(false)
const confirmMode = ref<'structure' | 'commit'>('structure')
const confirmScope = ref<string[]>([])
const confirmStrategyName = ref('')
const binaryCount = computed(() => {
  if (confirmMode.value !== 'commit' || !commitDetail.value) {
    return 0
  }
  return summarize(commitDetail.value.files || []).binary
})

// ---------------- 记录与准确率 ----------------
const records = ref<any[]>([])
const recordsLoading = ref(false)
const accuracy = ref<AccuracyStat[]>([])
const accuracyLoading = ref(false)

// ---------------- 记录只读查看（全屏模态框） ----------------
// 快照态与页签里的 review 完全隔离：查看历史记录不得覆盖正在进行的审查
const recordViewerOpen = ref(false)
const recordSnapshot = ref<ReviewRecord | null>(null)
const snapshotMarks = ref<IssueMark[]>([])
const snapshotMarksLoading = ref(false)

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

function toTreeNodes(nodes: TreeNode[]): any[] {
  return nodes.map((n) => ({
    title: n.name,
    key: n.path,
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
    allExpandableKeys.value = collectExpandableKeys(nodes)
  } catch (e: any) {
    treeData.value = []
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

/** 拉取单提交详情（列表视图，不含 patch）；默认全选可审查文件（Q24） */
async function loadCommitDetail(sha: string) {
  if (!sha) return
  detailLoading.value = true
  detailError.value = ''
  try {
    const detail = await getCommitDetail(projectId, sha)
    commitDetail.value = detail
    commitChecked.value = (detail.files || [])
      .filter((f: ChangedFile) => !summarize([f]).binary)
      .map((f: ChangedFile) => f.path)
  } catch (e: any) {
    commitDetail.value = null
    detailError.value = e?.message || '变更文件加载失败'
  } finally {
    detailLoading.value = false
  }
}

// =====================================================================
// 策略
// =====================================================================

async function loadStrategies() {
  try {
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
  } catch {
    // 策略加载失败不阻塞
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

async function onRetry() {
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

async function onRetryRecord(record: any) {
  try {
    await retryReview(record.id)
    message.success('已提交重审')
    await loadRecords()
  } catch (e: any) {
    message.error(e?.message || '重审失败')
  }
}

function startPoll(id: string) {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = window.setInterval(async () => {
    try {
      const r = await getReview(id)
      review.value = r
      if (r.status >= 2) {
        window.clearInterval(pollTimer)
        await loadMarks(id)
        await Promise.all([loadRecords(), loadAccuracy()])
      }
    } catch {
      window.clearInterval(pollTimer)
    }
  }, 2000)
}

// =====================================================================
// 标记与准确率
// =====================================================================

async function loadMarks(recordId: string) {
  try {
    marks.value = await listMarks(recordId)
  } catch {
    marks.value = []
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
  } catch {
    accuracy.value = []
  } finally {
    accuracyLoading.value = false
  }
}

async function loadRecords() {
  recordsLoading.value = true
  try {
    const page = (await listReviews(projectId, { pageNum: 1, pageSize: 50 })) as any
    records.value = page?.records || []
  } catch {
    records.value = []
  } finally {
    recordsLoading.value = false
  }
}

/**
 * 查看历史记录：开全屏只读弹窗，**不切页签**。
 *
 * 原实现是切到「代码审查」页签并把记录塞进 `review`，副作用是覆盖掉当前正在进行的审查结果；
 * 弹窗用独立的 `recordSnapshot`，关掉即恢复原状。重审/打标记仍走「代码审查」页签。
 */
async function viewRecord(record: any) {
  recordSnapshot.value = record as ReviewRecord
  snapshotMarks.value = []
  recordViewerOpen.value = true
  snapshotMarksLoading.value = true
  try {
    snapshotMarks.value = await listMarks(record.id)
  } catch {
    snapshotMarks.value = []
  } finally {
    snapshotMarksLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadBranches(), loadStrategies(), loadRecords(), loadAccuracy()])
})

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<style scoped lang="less">
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
.tree-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.hint {
  color: #999;
  font-size: 12px;
}
.mt12 {
  margin-top: 12px;
}
:deep(.ant-tree-indent-unit) {
  width: 12px;
}
</style>
