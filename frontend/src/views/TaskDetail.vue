<template>
  <div>
    <div class="back-row">
      <a-button type="link" @click="goBack">← 返回审查中心</a-button>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="error" class="mb16">
      <template #action><a-button size="small" @click="loadTask">重试</a-button></template>
    </a-alert>
    <a-spin :spinning="taskLoading">
      <template v-if="task">
        <div class="detail-head">
          <div>
            <div class="row wrap">
              <h2>审查任务 {{ shortId(task.id) }}</h2>
              <a-tag :color="stateColor(task.state)">{{ stateText(task.state) }}</a-tag>
            </div>
            <p class="muted">
              {{ projectName }} · {{ typeText(task.specialistType) }} ·
              {{ task.sourceKind === 'DIFF' ? '提交差异' : '文件内容' }} ·
              <code>{{ (task.headSha || '').slice(0, 7) }}</code>
              <span v-if="task.baseSha"> → 父 {{ task.baseSha.slice(0, 7) }}</span>
            </p>
            <p class="muted small">
              文件完成 {{ succeededCount }} / {{ task.targets.length }}
              <span v-if="failedCount">（失败 {{ failedCount }}）</span>
              · {{ task.smartSplit ? '允许智能拆分' : '整文件提交' }}
            </p>
          </div>
          <div class="actions">
            <a-button :loading="refreshing" @click="loadTask">刷新</a-button>
            <a-button
              v-if="task.state === 'QUEUED' || task.state === 'RUNNING'"
              danger
              @click="cancelTask"
            >
              取消任务
            </a-button>
            <a-button @click="copyTask">复制参数新建</a-button>
          </div>
        </div>

        <a-alert
          v-if="task.state === 'PARTIAL'"
          type="warning"
          show-icon
          message="部分完成：成功文件的结果可阅读；失败文件没有完整结论，也不能作为报告材料。"
          class="mb16"
        />
        <a-alert
          v-if="task.state === 'CANCELLED'"
          type="warning"
          show-icon
          message="任务已取消：已完成文件仍可阅读，其他文件已停止，不会自动继续。"
          class="mb16"
        />
        <a-alert
          v-if="task.state === 'FAILED'"
          type="error"
          show-icon
          :message="`任务失败${task.stopReason ? '：' + task.stopReason : ''}`"
          class="mb16"
        />

        <TaskAccuracySummary
          v-if="stats && stats.issues.total + stats.results.total > 0"
          :specialist-type="task.specialistType"
          :stats="stats"
        />
        <p v-if="statsError" class="muted small mb16">{{ statsError }}</p>

        <div class="detail-grid">
          <aside class="card targets">
            <div class="section-head">
              <strong>目标文件</strong>
              <span class="spacer" />
              <span class="muted small">{{ task.targets.length }} 个</span>
            </div>
            <button
              v-for="target in task.targets"
              :key="target.id"
              class="target"
              :class="{ selected: target.id === selectedTargetId }"
              @click="selectTarget(target.id)"
            >
              <span class="target-path">{{ target.path }}</span>
              <span class="target-meta">
                <a-tag :color="targetColor(target.state)">{{ stateText(target.state) }}</a-tag>
                <span v-if="target.errorCode" class="error">{{ target.errorCode }}</span>
                <span v-else-if="result && target.id === selectedTargetId" class="muted small">
                  {{ issueCount }} 个问题
                </span>
              </span>
            </button>
          </aside>

          <section class="card content">
            <div class="tabs">
              <a-button
                v-for="tab in tabs"
                :key="tab.key"
                size="small"
                :type="activeTab === tab.key ? 'primary' : 'default'"
                @click="activeTab = tab.key"
              >
                {{ tab.label }}
              </a-button>
            </div>

            <a-spin :spinning="detailLoading">
              <template v-if="activeTab === 'result'">
                <a-alert v-if="resultError" type="warning" show-icon :message="resultError" />
                <SpecialistResultView
                  v-if="result"
                  :task-id="task.id"
                  :result="result"
                  @open-code="openMaterial"
                />
                <div v-else-if="!resultError" class="empty">该文件还没有已发布结果。</div>
              </template>

              <template v-else-if="activeTab === 'materials'">
                <a-alert
                  v-if="materialError"
                  type="error"
                  show-icon
                  :message="materialError"
                />
                <a-empty v-if="!materials.length && !materialError" description="还没有关联材料" />
                <div v-if="materials.length" class="material-list">
                  <div v-for="material in materials" :key="material.id" class="material-row">
                    <div class="material-main">
                      <code>{{ material.path }}</code>
                      <span class="muted small">
                        {{ material.sha.slice(0, 10) }} · 第 {{ material.startLine }}-{{ material.endLine }} 行
                        · {{ sideText(material.side) }} · {{ material.role }}
                        · {{ material.sent ? '已发送' : '未发送' }}
                      </span>
                      <span v-if="material.diagnostic" class="muted small">{{ material.diagnostic }}</span>
                    </div>
                    <a-button size="small" @click="openMaterialById(material.id)">查看代码</a-button>
                  </div>
                </div>
                <p class="muted small hint">
                  列表显示实际关联到调用的材料；声明摘要与拆片段不是完整源码，扩展全文不改变实际发送内容。
                </p>
              </template>

              <template v-else>
                <a-alert v-if="callError" type="error" show-icon :message="callError" />
                <a-empty v-if="!calls.length && !callError" description="还没有调用记录" />
                <a-table
                  v-if="calls.length"
                  :data-source="calls"
                  row-key="id"
                  :pagination="false"
                  size="small"
                >
                  <a-table-column title="尝试" key="attempt" width="140">
                    <template #default="{ record }">
                      传输尝试 {{ record.attempt }}
                      <span class="muted small">
                        {{ record.generation ? '第 ' + record.generation + ' 代 · ' : '' }}
                        {{ record.unitOrdinal ? '单元 ' + record.unitOrdinal : '单元未记录' }} ·
                        {{ record.purpose }}
                      </span>
                    </template>
                  </a-table-column>
                  <a-table-column title="状态" key="state" width="120">
                    <template #default="{ record }">
                      <a-tag :color="record.state === 'SUCCEEDED' ? 'green' : 'red'">
                        {{ record.state }}
                      </a-tag>
                    </template>
                  </a-table-column>
                  <a-table-column title="时间" key="time" width="170">
                    <template #default="{ record }">{{ formatTime(record.createdAt) }}</template>
                  </a-table-column>
                  <a-table-column title="操作" key="action" width="160">
                    <template #default="{ record }">
                      <a-button size="small" @click="openCall(record.id)">完整输入 / 输出</a-button>
                    </template>
                  </a-table-column>
                </a-table>
                <a-button
                  v-if="callsHasMore"
                  size="small"
                  :loading="callsLoadingMore"
                  class="load-more"
                  @click="loadMoreCalls"
                >
                  加载更多调用
                </a-button>
              </template>
            </a-spin>
          </section>
        </div>
      </template>
    </a-spin>

    <MaterialReaderModal
      v-if="task && selectedTargetId"
      v-model:open="materialOpen"
      :task-id="task.id"
      :project-id="task.projectId"
      :head-sha="task.headSha || ''"
      :material-id="materialId"
    />
    <CallReaderModal
      v-if="task"
      v-model:open="callOpen"
      :task-id="task.id"
      :call-id="callId"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import SpecialistResultView from '@/components/SpecialistResultView.vue'
import TaskAccuracySummary from '@/components/TaskAccuracySummary.vue'
import MaterialReaderModal from '@/components/MaterialReaderModal.vue'
import CallReaderModal from '@/components/CallReaderModal.vue'
import { discardFeedbackDrafts, hasDirtyFeedbackDrafts, feedbackSaveVersion } from '@/components/feedbackDrafts'
import {
  projectsApi,
  tasksApi,
  type CallSummary,
  type MaterialView,
  type ResultView,
  type TaskStatistics,
  type TaskView
} from '@/api/v2'

const route = useRoute()
const router = useRouter()
const task = ref<TaskView | null>(null)
const projectName = ref('')
const taskLoading = ref(false)
const refreshing = ref(false)
const error = ref('')
const selectedTargetId = ref('')
const activeTab = ref<'result' | 'materials' | 'calls'>('result')
const result = ref<ResultView | null>(null)
const resultError = ref('')
const stats = ref<TaskStatistics | null>(null)
const statsError = ref('')
const materials = ref<MaterialView[]>([])
const materialError = ref('')
const calls = ref<CallSummary[]>([])
const callError = ref('')
const callsCursor = ref<string | null>(null)
const callsHasMore = ref(false)
const callsLoadingMore = ref(false)
const detailLoading = ref(false)
const materialOpen = ref(false)
const materialId = ref('')
const callOpen = ref(false)
const callId = ref('')
let loadEpoch = 0
let taskEpoch = 0
let callsEpoch = 0
let statsEpoch = 0
let callsPages = 1
let disposed = false
let pollTimer: ReturnType<typeof setTimeout> | null = null

function protectFeedbackDrafts(event: BeforeUnloadEvent) {
  if (!task.value || !hasDirtyFeedbackDrafts(task.value.id)) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', protectFeedbackDrafts))
onBeforeUnmount(() => window.removeEventListener('beforeunload', protectFeedbackDrafts))
function confirmDiscardFeedback(taskId: string | undefined): boolean | Promise<boolean> {
  if (!taskId || !hasDirtyFeedbackDrafts(taskId)) return true
  return new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: '反馈尚未保存',
      content: '离开此页面将丢弃未保存的评价或评论。',
      okText: '离开并丢弃',
      cancelText: '继续编辑',
      onOk: () => { discardFeedbackDrafts(taskId); resolve(true) },
      onCancel: () => resolve(false)
    })
  })
}
onBeforeRouteLeave(() => confirmDiscardFeedback(task.value?.id))
onBeforeRouteUpdate((to, from) => to.params.id === from.params.id
  ? true
  : confirmDiscardFeedback(task.value?.id))

const tabs = [
  { key: 'result' as const, label: '审查结果' },
  { key: 'materials' as const, label: '材料与边界' },
  { key: 'calls' as const, label: '实际调用' }
]

const succeededCount = computed(() =>
  task.value?.targets.filter((target) => target.state === 'SUCCEEDED').length || 0
)
const failedCount = computed(() =>
  task.value?.targets.filter((target) => target.state === 'FAILED').length || 0
)
const issueCount = computed(() => result.value?.issues.length || 0)
function saveScene() {
  if (!task.value) return
  try {
    sessionStorage.setItem(`review-detail-${task.value.id}`, JSON.stringify({
      selectedTargetId: selectedTargetId.value, activeTab: activeTab.value, callsPages
    }))
  } catch { /* 存储不可用不阻断阅读 */ }
}
watch(() => route.params.id, () => {
  saveScene()
  stopPoll()
  taskEpoch++
  loadEpoch++
  callsEpoch++
  statsEpoch++
  task.value = null
  projectName.value = ''
  selectedTargetId.value = ''
  activeTab.value = 'result'
  result.value = null
  materials.value = []
  calls.value = []
  stats.value = null
  statsError.value = ''
  callsCursor.value = null
  callsHasMore.value = false
  callsPages = 1
  materialOpen.value = false
  callOpen.value = false
  try {
    const saved = JSON.parse(sessionStorage.getItem(`review-detail-${String(route.params.id)}`) || 'null')
    if (saved) {
      selectedTargetId.value = typeof saved.selectedTargetId === 'string' ? saved.selectedTargetId : ''
      activeTab.value = ['result', 'materials', 'calls'].includes(saved.activeTab) ? saved.activeTab : 'result'
      callsPages = Math.min(4, Math.max(1, Number(saved.callsPages) || 1))
    }
  } catch { /* 无现场 */ }
  void loadTask()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => {
  saveScene()
  disposed = true
  taskEpoch++
  loadEpoch++
  callsEpoch++
  statsEpoch++
  stopPoll()
})

async function loadTask() {
  const epoch = ++taskEpoch
  const id = String(route.params.id)
  stopPoll()
  taskLoading.value = !task.value
  refreshing.value = !!task.value
  error.value = ''
  try {
    const loaded = await tasksApi.detail(id)
    if (epoch !== taskEpoch || disposed) return
    task.value = loaded
    if (!loaded.targets.some(target => target.id === selectedTargetId.value)) {
      if (selectedTargetId.value) message.warning('原目标已不可用，已切换到当前任务目标')
      selectedTargetId.value = loaded.targets[0]?.id || ''
      result.value = null
      materials.value = []
      calls.value = []
      callsCursor.value = null
      callsHasMore.value = false
    }
    void loadStatistics(loaded.id)
    await loadTarget()
    if (epoch !== taskEpoch || disposed) return
    if (!projectName.value || projectName.value === loaded.projectId) {
      try {
        const project = await projectsApi.detail(loaded.projectId)
        if (epoch === taskEpoch && !disposed) projectName.value = project.name
      } catch {
        if (epoch === taskEpoch && !disposed) projectName.value = loaded.projectId
      }
    }
  } catch (failure) {
    if (epoch === taskEpoch && !disposed) error.value = (failure as Error).message || '任务详情加载失败'
  } finally {
    if (epoch === taskEpoch && !disposed) {
      taskLoading.value = false
      refreshing.value = false
      schedulePoll()
    }
  }
}

async function loadMoreCalls() {
  if (!task.value || !selectedTargetId.value || !callsHasMore.value || callsLoadingMore.value || detailLoading.value) return
  const epoch = ++callsEpoch
  const targetEpoch = loadEpoch
  callsLoadingMore.value = true
  callError.value = ''
  try {
    const page = await tasksApi.calls(task.value.id, {
      targetId: selectedTargetId.value, cursor: callsCursor.value || undefined, limit: 50
    })
    if (epoch !== callsEpoch || targetEpoch !== loadEpoch || disposed) return
    calls.value = [...new Map([...calls.value, ...page.items].map(call => [call.id, call])).values()]
    callsCursor.value = page.nextCursor
    callsHasMore.value = page.hasMore
    callsPages++
  } catch (failure) {
    if (epoch === callsEpoch && targetEpoch === loadEpoch && !disposed) {
      callError.value = (failure as Error).message || '更多调用读取失败'
    }
  } finally {
    if (epoch === callsEpoch && !disposed) callsLoadingMore.value = false
  }
}

async function selectTarget(targetId: string) {
  if (selectedTargetId.value === targetId) return
  selectedTargetId.value = targetId
  callsPages = 1
  callsEpoch++
  callsCursor.value = null
  callsHasMore.value = false
  callsLoadingMore.value = false
  result.value = null
  materials.value = []
  calls.value = []
  materialOpen.value = false
  callOpen.value = false
  await loadTarget()
}

async function readCallPages(taskId: string, targetId: string) {
  let cursor: string | undefined
  const items: CallSummary[] = []
  let page
  for (let index = 0; index < callsPages; index++) {
    page = await tasksApi.calls(taskId, { targetId, cursor, limit: 50 })
    items.push(...page.items)
    if (!page.hasMore || !page.nextCursor) break
    cursor = page.nextCursor
  }
  return { items, nextCursor: page?.nextCursor || null, hasMore: page?.hasMore || false }
}

/**
 * 任务级统计与目标选择无关，单独读取；失败时保留上一次结果，不把未知显示成 0%。
 */
async function loadStatistics(taskId: string) {
  const epoch = ++statsEpoch
  try {
    const loaded = await tasksApi.statistics(taskId)
    if (epoch !== statsEpoch || disposed) return
    if (task.value?.id !== taskId) return
    stats.value = loaded
    statsError.value = ''
  } catch (failure) {
    if (epoch !== statsEpoch || disposed || task.value?.id !== taskId) return
    statsError.value = `准确率统计读取失败：${(failure as Error).message || '请稍后重试'}`
  }
}

function refreshStatistics() {
  if (task.value) void loadStatistics(task.value.id)
}
watch(() => task.value ? feedbackSaveVersion(task.value.id) : 0, refreshStatistics)

async function loadTarget() {  const epoch = ++loadEpoch
  callsEpoch++
  callsLoadingMore.value = false
  if (!task.value || !selectedTargetId.value) {
    result.value = null; materials.value = []; calls.value = []
    detailLoading.value = false
    return
  }
  const id = task.value.id
  const targetId = selectedTargetId.value
  detailLoading.value = true
  resultError.value = ''
  materialError.value = ''
  callError.value = ''
  try {
    const [resultOutcome, materialOutcome, callOutcome] = await Promise.allSettled([
      tasksApi.result(id, targetId), tasksApi.materials(id, targetId), readCallPages(id, targetId)
    ])
    if (epoch !== loadEpoch || disposed) return
    if (resultOutcome.status === 'fulfilled') result.value = resultOutcome.value
    else resultError.value = (resultOutcome.reason as Error)?.message || '结果读取失败'
    if (materialOutcome.status === 'fulfilled') materials.value = materialOutcome.value
    else materialError.value = (materialOutcome.reason as Error)?.message || '材料读取失败'
    if (callOutcome.status === 'fulfilled') {
      calls.value = callOutcome.value.items
      callsCursor.value = callOutcome.value.nextCursor
      callsHasMore.value = callOutcome.value.hasMore
    } else callError.value = (callOutcome.reason as Error)?.message || '调用读取失败'
  } finally {
    if (epoch === loadEpoch && !disposed) detailLoading.value = false
  }
}

function schedulePoll() {
  stopPoll()
  if (disposed || !task.value || !['QUEUED', 'RUNNING'].includes(task.value.state)) return
  pollTimer = setTimeout(() => { void loadTask() }, 3000)
}

function stopPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function cancelTask() {
  if (!task.value) return
  const id = task.value.id
  const revision = task.value.revision
  Modal.confirm({
    title: '取消任务',
    content: '已完成文件的结果会保留，未完成文件停止；是否继续？',
    okText: '取消任务',
    okType: 'danger',
    cancelText: '继续等待',
    onOk: async () => {
      try {
        await tasksApi.cancel(id, revision)
        if (disposed || id !== String(route.params.id)) return
        message.success('已请求取消')
        await loadTask()
      } catch (failure) {
        message.error((failure as Error).message || '取消失败')
      }
    }
  })
}

function copyTask() {
  if (!task.value) return
  router.push({ path: '/reviews/new', query: { copyFrom: task.value.id } })
}

function openMaterial(value: { materialId?: string; path: string; sha?: string; side: string; startLine: number; endLine: number }) {
  if (value.materialId) {
    openMaterialById(value.materialId)
  }
}

function openMaterialById(id: string) {
  materialId.value = id
  materialOpen.value = true
}

function openCall(id: string) {
  callId.value = id
  callOpen.value = true
}

function goBack() {
  const from = String(route.query.from || '')
  if (from) {
    router.push(from)
  } else {
    router.push('/reviews')
  }
}

function shortId(id: string): string {
  return id.length > 10 ? `${id.slice(0, 10)}…` : id
}

function typeText(type: string): string {
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度' } as Record<string, string>)[type] || type
}

function stateText(state: string): string {
  return (
    ({
      QUEUED: '排队中',
      PREPARING: '准备中',
      RUNNING: '执行中',
      SUCCEEDED: '已完成',
      PARTIAL: '部分完成',
      FAILED: '失败',
      CANCELLED: '已取消'
    } as Record<string, string>)[state] || state
  )
}

function stateColor(state: string): string {
  return (
    ({
      SUCCEEDED: 'green',
      RUNNING: 'blue',
      PARTIAL: 'orange',
      FAILED: 'red',
      CANCELLED: 'default'
    } as Record<string, string>)[state] || 'default'
  )
}

function targetColor(state: string): string {
  return stateColor(state)
}

function sideText(side: string): string {
  return ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || side
}

function formatTime(value: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped lang="less">
.back-row {
  margin-bottom: 4px;
}
.detail-head {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-start;
  margin-bottom: 16px;
}
.detail-head h2 {
  margin: 0;
  font-size: 22px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wrap {
  flex-wrap: wrap;
}
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.muted {
  color: #69798d;
}
.small {
  font-size: 12px;
}
.mb16 {
  margin-bottom: 16px;
}
.detail-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
}
.card {
  background: #fff;
  border: 1px solid #e3e9f0;
  border-radius: 8px;
  overflow: hidden;
}
.targets {
  max-height: 680px;
  overflow: auto;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid #e3e9f0;
}
.target {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid #eef1f6;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.target:hover {
  background: #f7f9fc;
}
.target.selected {
  background: #edf4ff;
  box-shadow: inset 3px 0 0 #2463d5;
}
.target-path {
  overflow-wrap: anywhere;
  font-size: 13px;
}
.target-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}
.error {
  color: #bd3c37;
  font-size: 11px;
}
.content {
  min-height: 500px;
  padding: 14px 18px;
}
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.material-list {
  border: 1px solid #e3e9f0;
  border-radius: 7px;
}
.material-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid #eef1f6;
}
.material-row:last-child {
  border-bottom: 0;
}
.material-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.hint {
  margin-top: 10px;
}
.load-more {
  margin-top: 10px;
}
.empty {
  padding: 40px;
  text-align: center;
  color: #69798d;
}
.spacer {
  flex: 1;
}
</style>
