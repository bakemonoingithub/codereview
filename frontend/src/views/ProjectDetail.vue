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
            <div v-if="!review" class="placeholder">尚未触发审查</div>
            <template v-else>
              <div v-if="review.status < 2" class="mb8">
                <a-progress :percent="review.progress" status="active" />
                <p class="summary">{{ review.status === 0 ? '排队中…' : '审查执行中…' }}</p>
              </div>
              <div class="result-head">
                <a-alert
                  v-if="review.status >= 2"
                  :type="statusAlert.type"
                  :message="statusAlert.text"
                  show-icon
                  class="flex1"
                />
                <a-button v-if="review.status === 3 || review.status === 4" :loading="retrying" @click="onRetry">
                  重审失败单元
                </a-button>
              </div>
              <p v-if="parsed.summary" class="summary">{{ parsed.summary }}</p>

              <DiffReviewResult
                v-if="resultType === 'diff-review'"
                :project-id="projectId"
                :commit-sha="review.commitSha || ''"
                :record-id="review.id"
                :result="parsed"
                :marks="marks"
                @mark="onMark"
              />

              <a-collapse v-else-if="resultType === 'llm-review' && parsed.units?.length">
                <a-collapse-panel v-for="(u, i) in parsed.units" :key="i" :header="unitTitle(u)">
                  <a-space style="margin-bottom: 8px">
                    <a-tag :color="u.status === 'success' ? 'green' : 'red'">
                      {{ u.status === 'success' ? '成功' : '失败' }}
                    </a-tag>
                    <span class="unit-meta">{{ u.unit.kind }} · 行 {{ u.unit.lines }}</span>
                  </a-space>
                  <div v-if="u.status === 'failed'" class="error-text">{{ u.error }}</div>
                  <template v-else>
                    <p v-if="u.summary" class="summary">{{ u.summary }}</p>
                    <a-table
                      v-if="u.issues?.length"
                      :data-source="u.issues"
                      row-key="title"
                      size="small"
                      :pagination="false"
                    >
                      <a-table-column title="级别" data-index="severity" width="80" />
                      <a-table-column title="行" data-index="line" width="60" />
                      <a-table-column title="文件" data-index="file" width="140">
                        <template #default="{ text }">{{ text || u.path }}</template>
                      </a-table-column>
                      <a-table-column title="问题" data-index="title" />
                      <a-table-column title="建议" data-index="suggestion" />
                    </a-table>
                    <RawResult v-else-if="u.raw" :text="u.raw" />
                  </template>
                </a-collapse-panel>
              </a-collapse>

              <CouplingResult v-else-if="resultType === 'coupling'" :result="parsed" />
              <PatternResult v-else-if="resultType === 'design-pattern'" :result="parsed" />
              <RawResult v-else-if="resultType === 'raw'" :text="parsed.raw" />

              <div v-else-if="resultType === 'api-review'">
                <a-space v-if="parsed.resultUrl" style="margin-bottom: 8px">
                  <a-tag :color="parsed.triggered ? 'green' : 'red'">
                    {{ parsed.triggered ? '已触发' : '触发失败' }}
                  </a-tag>
                  <a :href="parsed.resultUrl" target="_blank" rel="noopener">查看 SonarQube 结果</a>
                </a-space>
                <p v-if="parsed.triggerError" class="error-text">触发失败：{{ parsed.triggerError }}</p>
                <a-table
                  v-if="parsed.issues?.length"
                  :data-source="parsed.issues"
                  row-key="key"
                  size="small"
                  :pagination="false"
                  style="margin-top: 8px"
                >
                  <a-table-column title="级别" data-index="severity" width="80" />
                  <a-table-column title="行" data-index="line" width="60" />
                  <a-table-column title="问题" data-index="message" />
                </a-table>
              </div>
            </template>
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
import type { AccuracyStat } from '@/utils/accuracy'
import CouplingResult from '@/components/CouplingResult.vue'
import PatternResult from '@/components/PatternResult.vue'
import RawResult from '@/components/RawResult.vue'
import ReportPanel from '@/components/ReportPanel.vue'
import SplitPane from '@/components/SplitPane.vue'
import CommitTable from '@/components/CommitTable.vue'
import ChangedFileTree from '@/components/ChangedFileTree.vue'
import DiffReviewResult from '@/components/DiffReviewResult.vue'
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

async function viewRecord(record: any) {
  review.value = record as ReviewRecord
  await loadMarks(record.id)
  activeTab.value = 'review'
}

// =====================================================================
// 结果渲染
// =====================================================================

const parsed = computed(() => {
  if (!review.value?.resultJson) return {}
  try {
    return JSON.parse(review.value.resultJson)
  } catch {
    return {}
  }
})

const resultType = computed(() => {
  const r = parsed.value
  if (Array.isArray(r.units)) {
    // 带 commit 信息的即 diff-review（两者都复用 units 结构）
    return r.commit ? 'diff-review' : 'llm-review'
  }
  if (Array.isArray(r.nodes) && Array.isArray(r.edges)) return 'coupling'
  if (Array.isArray(r.patterns)) return 'design-pattern'
  if (r.type === 'api-review') return 'api-review'
  if (typeof r.raw === 'string' && r.raw) return 'raw'
  if (r.error) return 'raw'
  return 'none'
})

const statusAlert = computed(() => {
  const s = review.value?.status
  if (s === 2) return { type: 'success', text: '审查成功' }
  if (s === 3) return { type: 'error', text: '审查失败' }
  if (s === 4) return { type: 'warning', text: '部分成功（部分单元失败，可重审）' }
  return { type: 'info', text: '' }
})

function unitTitle(u: any) {
  return `${u.path} · ${u.unit.name}`
}

function recordStatusText(status: number) {
  return ['排队', '执行中', '成功', '失败', '部分成功'][status] || '未知'
}

function recordStatusColor(status: number) {
  return ['default', 'processing', 'green', 'red', 'orange'][status] || 'default'
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
.summary {
  color: #666;
}
.unit-meta {
  color: #999;
}
.error-text {
  color: #cf1322;
  white-space: pre-wrap;
}
.placeholder {
  color: #999;
}
.result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.flex1 {
  flex: 1;
}
.mb8 {
  margin-bottom: 8px;
}
.mt12 {
  margin-top: 12px;
}
:deep(.ant-tree-indent-unit) {
  width: 12px;
}
</style>
