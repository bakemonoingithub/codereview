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
              <h1 :title="task.id">{{ taskLabel(task.name, task.id) }}</h1>
              <StatusBadge :tone="stateTone(task.state)">{{ stateText(task.state) }}</StatusBadge>
            </div>
            <TaskDetailMetadata :task-id="task.id" :show-id="false">
              {{ projectName }} · {{ typeText(task.specialistType) }}
            </TaskDetailMetadata>
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
                <StatusBadge :tone="stateTone(target.state)">{{ stateText(target.state) }}</StatusBadge>
                <span v-if="target.errorCode" class="error">{{ target.errorCode }}</span>
                <span v-else-if="target.issueCount != null" class="muted small">
                  {{ target.issueCount }} 个问题
                </span>
              </span>
            </button>
          </aside>

          <section class="card content">
            <div class="tabbar">
              <button
                v-for="tab in tabs"
                :key="tab.key"
                type="button"
                :class="{ active: activeTab === tab.key }"
                @click="activeTab = tab.key"
              >
                {{ tab.label }}
              </button>
            </div>

            <a-spin :spinning="detailLoading">
              <template v-if="activeTab === 'result'">
                <a-alert v-if="resultError" type="warning" show-icon :message="resultError" />
                <SpecialistResultView
                  v-if="result"
                  :task-id="task.id"
                  :result="result"
                  @open-code="openCodeReference"
                />
                <div v-else-if="!resultError" class="empty">该文件还没有已发布结果。</div>
              </template>

              <template v-else-if="activeTab === 'materials'">
                <div class="review-input">
<!--                  <h2>输入与配置</h2>-->
<!--                  <p class="muted input-intro">查看本任务的配置，以及当前文件的要求和关联代码材料。</p>-->

                  <section class="input-section" aria-label="任务配置">
                    <h3>任务配置</h3>
                    <dl class="input-pairs">
                      <dt>审查项目</dt>
                      <dd>{{ projectName && projectName !== task.projectId ? projectName : '项目名称不可用' }}</dd>
                      <dt>审查类型</dt>
                      <dd>{{ typeText(task.specialistType) || '未知类型' }}</dd>
                      <dt>分支与源码版本</dt>
                      <dd><code class="input-version" :title="inputVersion.full">{{ inputVersion.label }}</code></dd>
                      <dt>当前审查文件</dt>
                      <dd>
                        <code>{{ inputTarget?.path || '文件路径未记录' }}</code>
                        <small v-if="inputTarget?.oldPath && inputTarget.oldPath !== inputTarget.path" class="muted input-old-path">
                          原路径：<code>{{ inputTarget.oldPath }}</code>
                        </small>
                      </dd>
                      <dt>文件处理设置</dt>
                      <dd>{{ task.smartSplit ? '允许按需拆分' : '整文件提交' }}</dd>
                      <dt>使用的模型</dt>
                      <dd>{{ task.config?.modelName || '模型名称未记录' }}</dd>
                    </dl>
                  </section>

                  <section class="input-section" aria-label="审查规则">
                    <h3>审查规则</h3>
                    <dl class="input-pairs">
                      <dt>本次要求</dt>
                      <dd class="input-text">{{ inputTarget?.note?.trim() ? inputTarget.note : '未填写' }}</dd>
                      <dt>通用规则</dt>
                      <dd>{{ inputRuleLabel }}</dd>
                      <template v-if="hasInputRule">
                        <dt>规则正文</dt>
                        <dd>
                          <details>
                            <summary>展开查看</summary>
                            <pre class="input-rule-content">{{ task.config?.promptContent?.trim() ? task.config.promptContent : '保存的规则正文不可用。' }}</pre>
                          </details>
                        </dd>
                      </template>
                    </dl>
                  </section>

                  <section class="input-section" aria-label="代码材料">
                    <h3>代码材料</h3>
                    <p class="muted small input-material-intro">以下是与当前文件关联的材料记录。可打开查看保存的内容、完整程度及是否已提供给模型。</p>
                    <a-alert v-if="materialError" type="error" show-icon :message="materialError" />
                    <a-empty v-if="!materials.length && !materialError" description="暂无关联材料记录" />
                    <div v-for="group in inputMaterialGroups" :key="group.label" class="input-material-group">
                      <h4>{{ group.label }}</h4>
                      <div class="material-list">
                        <div v-for="material in group.materials" :key="material.id" class="material-row">
                          <div class="material-main">
                            <code>{{ material.path }}</code>
                            <span class="muted small">
                              <template v-if="material.side !== 'FILE'">{{ sideText(material.side) }}</template>
                            </span>
                          </div>
                          <a-button size="small" type="link" @click="openMaterialById(material.id)">查看代码</a-button>
                        </div>
                      </div>
                    </div>
                  </section>

                  <section class="input-section" aria-label="任务信息">
                    <h3>任务信息</h3>
                    <dl class="input-pairs">
                      <dt>任务 ID</dt>
                      <dd><code>{{ task.id }}</code></dd>
                      <dt>创建与结束</dt>
                      <dd>
                        创建：{{ task.createdAt ? formatTime(task.createdAt) : '创建时间未记录' }}<br>
                        结束：{{ task.finishedAt ? formatTime(task.finishedAt) : (['QUEUED', 'PREPARING', 'RUNNING'].includes(task.state) ? '尚未结束' : '结束时间未记录') }}
                      </dd>
                      <dt>模型配置修订</dt>
                      <dd>{{ task.config?.modelRevision ?? '未记录' }}</dd>
                    </dl>
                  </section>
                </div>
              </template>

              <template v-else>
                <ActualCallList
                  :calls="calls"
                  :format-time="formatTime"
                  show-units
                  :error="callError"
                  :has-more="callsHasMore"
                  :loading-more="callsLoadingMore"
                  @open="openCall"
                  @load-more="loadMoreCalls"
                />
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
    <CodeReferenceModal
      v-if="task && codePayload"
      v-model:open="codeOpen"
      :task-id="task.id"
      :project-id="task.projectId"
      :scope="codePayload.scope"
      :path="codePayload.path"
      :sha="codePayload.sha || ''"
      :side="codePayload.side"
      :references="codePayload.references"
      :active-start="codePayload.startLine"
      :active-end="codePayload.endLine"
      :evidence-count="codePayload.evidenceCount || 0"
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
import { runtimeConfig } from '@/runtime/config'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import TaskDetailMetadata from '@/components/TaskDetailMetadata.vue'
import SpecialistResultView from '@/components/SpecialistResultView.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import TaskAccuracySummary from '@/components/TaskAccuracySummary.vue'
import MaterialReaderModal from '@/components/MaterialReaderModal.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import CallReaderModal from '@/components/CallReaderModal.vue'
import ActualCallList from '@/components/ActualCallList.vue'
import { discardFeedbackDrafts, hasDirtyFeedbackDrafts, feedbackSaveVersion } from '@/components/feedbackDrafts'
import { taskLabel } from '@/utils/taskLabel'
import type { CodeReferencePayload } from '@/utils/codeReferences'
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
const codeOpen = ref(false)
const codePayload = ref<CodeReferencePayload | null>(null)
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
  { key: 'materials' as const, label: '输入与配置' },
  { key: 'calls' as const, label: '实际调用' }
]

function materialRoleOrder(role: string): number {
  if (role === 'TARGET') return 0
  if (role === 'AUXILIARY') return 2
  return 1
}
// 同类材料保留接口默认的路径、代码版本、起始行顺序。
const orderedMaterials = computed(() => [...materials.value].sort(
  (left, right) => materialRoleOrder(left.role) - materialRoleOrder(right.role)
))
const inputTarget = computed(() => task.value?.targets.find(target => target.id === selectedTargetId.value))
const inputVersion = computed(() => {
  const branch = task.value?.branch || '分支未记录'
  const head = inputTarget.value?.headSha || task.value?.headSha
  const base = inputTarget.value?.baseSha || task.value?.baseSha
  const after = head || '源码版本未记录'
  if (task.value?.sourceKind === 'DIFF') {
    const before = base || '比较基线未记录'
    return {
      label: `${branch} · ${base ? base.slice(0, 12) : before}（修改前）→ ${head ? head.slice(0, 12) : after}（修改后）`,
      full: `${branch} · ${before}（修改前）→ ${after}（修改后）`
    }
  }
  return { label: `${branch} · ${head ? head.slice(0, 12) : after}`, full: `${branch} · ${after}` }
})
const hasInputRule = computed(() => {
  const config = task.value?.config
  return Boolean(config && (config.promptId || config.promptName || config.promptVersionId ||
    config.promptVersionNo != null || config.promptContent))
})
const inputRuleLabel = computed(() => {
  if (!hasInputRule.value) return '未使用通用规则'
  const config = task.value?.config
  const version = config?.promptVersionNo != null ? `v${config.promptVersionNo}` : '版本未记录'
  return `${config?.promptName || '规则名称未记录'} · ${version}`
})
const inputMaterialGroups = computed(() => [
  { label: '审查对象', materials: orderedMaterials.value.filter(material => material.role === 'TARGET') },
  { label: '辅助参考', materials: orderedMaterials.value.filter(material => material.role === 'AUXILIARY') },
  { label: '其他材料', materials: orderedMaterials.value.filter(material => !['TARGET', 'AUXILIARY'].includes(material.role)) }
].filter(group => group.materials.length))
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
  codeOpen.value = false
  codePayload.value = null
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
      codeOpen.value = false
      codePayload.value = null
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
  codeOpen.value = false
  codePayload.value = null
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
  pollTimer = setTimeout(() => { void loadTask() }, runtimeConfig().specialistPollMillis)
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

function openCodeReference(value: CodeReferencePayload) {
  if (value.references?.length && value.path) {
    codePayload.value = value
    codeOpen.value = true
    return
  }
  if (value.materialId) openMaterialById(value.materialId)
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

function stateTone(state: string): 'green' | 'amber' | 'blue' | 'red' | 'default' {
  if (state === 'SUCCEEDED') return 'green'
  if (state === 'PARTIAL' || state === 'CANCELLED') return 'amber'
  if (state === 'RUNNING' || state === 'QUEUED' || state === 'PREPARING') return 'blue'
  if (state === 'FAILED') return 'red'
  return 'default'
}

function sideText(side: string): string {
  return ({ FILE: '', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || '代码版本未说明'
}

function formatTime(value: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped lang="less">
.back-row {
  margin-bottom: 16px;
}
.detail-head {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  margin-bottom: 32px;
}
.detail-head h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
.detail-head p {
  margin: 8px 0 0;
  font-size: 15px;
}
.task-id {
  overflow-wrap: anywhere;
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
  color: var(--muted);
}
.small {
  font-size: 12px;
}
.mb16 {
  margin-bottom: 16px;
}
.detail-grid {
  display: grid;
  grid-template-columns: 270px minmax(0, 1fr);
  gap: 20px;
}
.card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  overflow: hidden;
}
.targets {
  max-height: 680px;
  overflow: auto;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--line);
}
.target {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  padding: 18px 20px;
  border: 0;
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  box-shadow: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.target:hover {
  background: var(--bg);
}
.target.selected {
  background: var(--soft);
  border-left: 3px solid var(--blue);
  padding-left: 17px;
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
  color: var(--red);
  font-size: 11px;
}
.content {
  min-height: 500px;
}
.tabbar {
  display: flex;
  gap: 28px;
  padding: 0 24px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}
.tabbar button {
  border: 0;
  border-radius: 0;
  background: none;
  box-shadow: none;
  padding: 15px 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
}
.tabbar button.active {
  color: var(--blue);
  border-bottom: 2px solid var(--blue);
  font-weight: 600;
}
.content :deep(.ant-spin-container) {
  padding: 20px 24px 24px;
}
.review-input {
  h2 {
    margin: 0 0 8px;
    font-size: 20px;
    line-height: 1.4;
  }
  .input-intro {
    margin: 0 0 12px;
    font-size: 13px;
    line-height: 1.8;
  }
  .input-section {
    padding: 20px 0;
    border-top: 1px solid var(--line);
    h3 {
      margin: 0 0 15px;
      font-size: 16px;
    }
    &:last-child { padding-bottom: 0; }
  }
  .input-pairs {
    display: grid;
    grid-template-columns: 130px minmax(0, 1fr);
    gap: 13px 18px;
    margin: 0;
    font-size: 14px;
    line-height: 1.8;
    dt { color: var(--muted); }
    dd {
      margin: 0;
      min-width: 0;
      overflow-wrap: anywhere;
    }
  }
  .input-version {
    padding: 3px 7px;
    border-radius: 5px;
    background: var(--bg);
    box-decoration-break: clone;
    -webkit-box-decoration-break: clone;
  }
  .input-old-path { display: block; margin-top: 4px; }
  .input-text { white-space: pre-wrap; }
  summary { color: var(--blue); cursor: pointer; }
  .input-rule-content {
    max-height: 260px;
    overflow: auto;
    margin: 12px 0 0;
    padding: 13px 15px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--bg);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font: inherit;
  }
  .input-material-intro { margin: 0 0 14px; line-height: 1.8; }
  .input-material-group h4 {
    margin: 16px 0 8px;
    color: var(--muted);
    font-size: 13px;
    font-weight: 500;
  }
  .material-list { border-radius: 8px; }
  .material-row { padding: 12px 14px; }
  .material-row :deep(.ant-btn) { flex-shrink: 0; }
  @media (max-width: 760px) {
    .input-pairs { grid-template-columns: 96px minmax(0, 1fr); gap: 12px; }
  }
}
.material-list {
  border: 1px solid var(--line);
  border-radius: 14px;
  overflow: hidden;
}
.material-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 17px 20px;
  border-bottom: 1px solid #edf1f6;
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
  padding: 48px 25px;
  text-align: center;
  color: var(--muted);
}
@media (max-width: 1100px) {
  .detail-grid {
    grid-template-columns: 225px minmax(0, 1fr);
    gap: 14px;
  }
  .detail-head {
    align-items: flex-start;
  }
  .detail-head h1 {
    font-size: 26px;
  }
}
@media (max-width: 760px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
  .detail-head {
    flex-wrap: wrap;
  }
}
.spacer {
  flex: 1;
}
</style>
