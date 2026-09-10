<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="review" tab="代码审查">
        <a-row :gutter="16">
          <a-col :span="10">
            <a-card title="审查配置" size="small">
              <div class="select-row">
                <span class="select-label">请选择分支</span>
              <a-select
                v-model:value="branch"
                style="width: 200px"
                placeholder="选择分支"
                :loading="branchLoading"
                @change="loadTree"
              >
                <a-select-option v-for="b in branches" :key="b" :value="b">{{ b }}</a-select-option>
              </a-select>
            </div>
            <div class="select-row">
              <span class="select-label">请选择审查策略</span>
              <a-select
                v-model:value="strategyId"
                style="width: 220px"
                placeholder="选择审查策略"
                :options="strategyOptions"
              >
                <template #notFoundContent>
                  <div>暂无策略，<router-link to="/strategies">去创建</router-link></div>
                </template>
              </a-select>
              <a-button type="primary" :loading="triggering" :disabled="!strategyId" @click="onTrigger">
                开始审查
              </a-button>
            </div>
            <a-tabs v-model:active-key="viewTab" type="card">
              <a-tab-pane key="structure" tab="结构视图">
                <div class="tree-toolbar">
                  <a-space>
                    <a-button size="small" @click="expandAll">展开全部</a-button>
                    <a-button size="small" @click="collapseAll">收起全部</a-button>
                  </a-space>
                </div>
                <a-tree
                  v-if="treeData.length"
                  checkable
                  :tree-data="treeData"
                  v-model:checked-keys="checkedKeys"
                  v-model:expanded-keys="expandedKeys"
                />
                <a-empty v-else description="加载中…" />
              </a-tab-pane>
              <a-tab-pane key="commit" tab="提交视图">
                <a-select v-model:value="selectedCommit" style="width: 100%" placeholder="选择提交" :options="commitOptions" @change="loadChangedFiles" />
                <a-checkbox-group v-if="changedFiles.length" v-model:value="changedChecked" style="width: 100%; margin-top: 8px">
                  <a-checkbox v-for="f in changedFiles" :key="f" :value="f">{{ f }}</a-checkbox>
                </a-checkbox-group>
                <a-button v-if="changedFiles.length" size="small" type="primary" style="margin-top: 8px" @click="useChangedFiles">
                  审查选中变更文件
                </a-button>
              </a-tab-pane>
            </a-tabs>
            </a-card>
          </a-col>
      <a-col :span="14">
        <a-card title="审查结果" size="small">
          <div v-if="!review">尚未触发审查</div>
          <template v-else>
            <div v-if="review.status < 2" style="margin-bottom: 12px">
              <a-progress :percent="review.progress" status="active" />
              <p class="summary">{{ review.status === 0 ? '排队中…' : '审查执行中…' }}</p>
            </div>
            <a-alert
              v-if="review.status >= 2"
              :type="statusAlert.type"
              :message="statusAlert.text"
              show-icon
              style="margin-bottom: 12px"
            />
            <a-space v-if="review.status === 3 || review.status === 4" style="margin-bottom: 12px">
              <a-button :loading="retrying" @click="onRetry">重审失败单元</a-button>
            </a-space>
            <p v-if="parsed.summary" class="summary">{{ parsed.summary }}</p>

            <a-collapse v-if="resultType === 'llm-review' && parsed.units?.length">
              <a-collapse-panel v-for="(u, i) in parsed.units" :key="i" :header="unitTitle(u)">
                <template #extra>
                  <span v-if="isRetrying(u)" class="retrying-badge">
                    <span class="spinner"></span> 重审中
                  </span>
                </template>
                <a-space style="margin-bottom: 8px">
                  <a-tag :color="unitTagColor(u)">{{ unitTagText(u) }}</a-tag>
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
                    <a-table-column title="问题" data-index="title" />
                    <a-table-column title="建议" data-index="suggestion" />
                  </a-table>
                </template>
              </a-collapse-panel>
            </a-collapse>

            <CouplingResult v-else-if="resultType === 'coupling'" :result="parsed" />
            <PatternResult v-else-if="resultType === 'design-pattern'" :result="parsed" />

            <div v-else-if="resultType === 'api-review'">
              <a-space v-if="parsed.resultUrl" style="margin-bottom: 8px">
                <a-tag :color="parsed.triggered ? 'green' : 'red'">{{ parsed.triggered ? '已触发' : '触发失败' }}</a-tag>
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
        </a-card>
      </a-col>
    </a-row>
      </a-tab-pane>
      <a-tab-pane key="report" tab="报告生成">
        <ReportPanel :project-id="projectId" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { getBranches, getTree, listCommits, listChangedFiles, type TreeNode, type CommitInfo } from '@/api/project'
import { triggerReview, getReview, retryReview, parseResult, type ReviewRecord } from '@/api/review'
import { listStrategies } from '@/api/strategy'
import CouplingResult from '@/components/CouplingResult.vue'
import PatternResult from '@/components/PatternResult.vue'
import ReportPanel from '@/components/ReportPanel.vue'

const route = useRoute()
const projectId = route.params.id as string
const activeTab = ref('review')
const viewTab = ref('structure')

const branches = ref<string[]>([])
const branch = ref('')
const branchLoading = ref(false)
const treeData = ref<any[]>([])
const checkedKeys = ref<string[]>([])
const expandedKeys = ref<string[]>([])
const allExpandableKeys = ref<string[]>([])
const commits = ref<CommitInfo[]>([])
const selectedCommit = ref('')
const changedFiles = ref<string[]>([])
const changedChecked = ref<string[]>([])
const commitOptions = computed(() => commits.value.map((c) => ({
  value: c.sha,
  label: `${c.sha.slice(0, 7)} · ${(c.message || '').split('\n')[0]}`
})))
const strategyId = ref('')
const strategyOptions = ref<{ value: string; label: string }[]>([])
const triggering = ref(false)
const retrying = ref(false)
const retryInProgress = ref(false)
const review = ref<ReviewRecord | null>(null)
let pollTimer: number | undefined

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

let fileSet = new Set<string>()

function expandAll() {
  expandedKeys.value = [...allExpandableKeys.value]
}

function collapseAll() {
  expandedKeys.value = []
}

async function loadCommits() {
  if (!branch.value) return
  try {
    commits.value = await listCommits(projectId, branch.value)
  } catch {
    commits.value = []
  }
}

async function loadChangedFiles() {
  if (!selectedCommit.value || !branch.value) return
  try {
    const idx = commits.value.findIndex((c) => c.sha === selectedCommit.value)
    const base = idx >= 0 && idx + 1 < commits.value.length ? commits.value[idx + 1].sha : branch.value
    changedFiles.value = await listChangedFiles(projectId, base, selectedCommit.value)
    changedChecked.value = []
  } catch {
    changedFiles.value = []
  }
}

function useChangedFiles() {
  if (!changedChecked.value.length) return
  checkedKeys.value = [...new Set([...checkedKeys.value, ...changedChecked.value])]
  message.success('已勾选变更文件，可在文件树中确认后点击「开始审查」')
}

async function loadBranches() {
  branchLoading.value = true
  try {
    branches.value = await getBranches(projectId)
    branch.value = branches.value[0] || ''
    if (branch.value) await loadTree()
  } finally {
    branchLoading.value = false
  }
}

async function loadTree() {
  const nodes = await getTree(projectId, branch.value)
  treeData.value = toTreeNodes(nodes)
  fileSet = flattenFiles(nodes)
  allExpandableKeys.value = collectExpandableKeys(nodes)
  loadCommits()
}

async function loadStrategies() {
  try {
    const page = (await listStrategies({ pageNum: 1, pageSize: 100 })) as any
    strategyOptions.value = (page?.records || []).map((s: any) => ({ value: s.id, label: s.name }))
    if (strategyOptions.value.length === 1) strategyId.value = strategyOptions.value[0].value
  } catch {
    // 策略加载失败不阻塞
  }
}

async function onTrigger() {
  const scope = checkedKeys.value.filter((k) => fileSet.has(k))
  if (!scope.length) {
    message.warning('请先勾选要审查的文件（可勾选目录批量选择）')
    return
  }
  if (!strategyId.value) {
    message.warning('请选择审查策略（可到「策略」菜单新建）')
    return
  }
  triggering.value = true
  try {
    retryInProgress.value = false
    const record = await triggerReview(projectId, { branch: branch.value, strategyId: strategyId.value, scope })
    review.value = record
    startPoll(record.id)
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
    retryInProgress.value = true
    message.success('已提交重审')
    startPoll(review.value.id)
  } catch (e: any) {
    message.error(e?.message || '重审失败')
  } finally {
    retrying.value = false
  }
}

function startPoll(id: string) {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = window.setInterval(async () => {
    try {
      const r = await getReview(id)
      review.value = r
      if (r.status >= 2) {
        retryInProgress.value = false
        window.clearInterval(pollTimer)
      }
    } catch {
      window.clearInterval(pollTimer)
    }
  }, 2000)
}

const parsed = computed(() => parseResult(review.value?.resultJson))

const resultType = computed(() => {
  const r = parsed.value
  if (Array.isArray(r.units)) return 'llm-review'
  if (Array.isArray(r.nodes) && Array.isArray(r.edges)) return 'coupling'
  if (Array.isArray(r.patterns)) return 'design-pattern'
  if (r.type === 'api-review') return 'api-review'
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

function isRetrying(u: any) {
  return retryInProgress.value && u.status === 'failed'
}

function unitTagColor(u: any) {
  if (isRetrying(u)) return 'processing'
  return u.status === 'success' ? 'green' : 'red'
}

function unitTagText(u: any) {
  if (isRetrying(u)) return '重审中'
  return u.status === 'success' ? '成功' : '失败'
}

onMounted(() => {
  loadBranches()
  loadStrategies()
})
</script>

<style scoped lang="less">
.select-row {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}
.select-label {
  flex: none;
  width: 110px;
  color: rgba(0, 0, 0, 0.88);
}
.tree-toolbar {
  margin-bottom: 8px;
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
:deep(.ant-tree-indent-unit) {
  width: 12px;
}
.retrying-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #1677ff;
  font-size: 12px;
}
.spinner {
  width: 12px;
  height: 12px;
  border: 2px solid #91caff;
  border-top-color: #1677ff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
