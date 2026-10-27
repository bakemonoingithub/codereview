<template>
  <div class="agent-page">
    <div class="back-row">
      <button type="button" class="back-link" @click="goBack">← 返回审查中心</button>
    </div>

    <a-alert v-if="loadError" type="error" show-icon :message="loadError">
      <template #action><a-button size="small" @click="loadTask">重试</a-button></template>
    </a-alert>

    <a-spin :spinning="taskLoading">
      <template v-if="task">
        <div class="heading">
          <div>
            <div class="row wrap">
              <h1 :title="task.id">{{ task.name || 'Agent 任务' }}</h1>
              <StatusBadge :tone="stateTone">{{ stateLabel(task.state) }}</StatusBadge>
<!--              <span class="agent-label">只读</span>-->
            </div>
            <TaskDetailMetadata :task-id="task.id" :show-id="false">
              {{ projectName || task.projectId }} · Agent 分析
            </TaskDetailMetadata>
          </div>
          <div class="actions">
            <a-button :loading="refreshing" @click="refreshAll">刷新</a-button>
            <a-button
              v-if="task.state === 'QUEUED' || task.state === 'RUNNING'"
              danger
              :loading="cancelling"
              @click="cancel"
            >
              取消任务
            </a-button>
            <a-button @click="router.push({ path: '/agents/new', query: { copyFrom: task.id } })">复制参数新建</a-button>
          </div>
        </div>

        <a-alert
          v-if="task.state === 'QUEUED' || task.state === 'RUNNING'"
          class="mb16"
          type="info"
          show-icon
          :message="`正在执行：${actionText(progress?.currentAction) || stateLabel(task.state)}`"
          description="已查范围与调用逐步保存。此处只显示工具动作，不显示模型内部思考。"
        />
<!--        <a-alert-->
<!--          v-else-if="task.state === 'SUCCEEDED'"-->
<!--          class="mb16"-->
<!--          type="info"-->
<!--          show-icon-->
<!--          message="审查材料、审查结果已固定保存。"-->
<!--        />-->
        <a-alert
          v-else-if="task.state !== 'SUCCEEDED'"
          class="mb16"
          type="warning"
          show-icon
          :message="`${stateLabel(task.state)}${task.stopReason ? '：' + task.stopReason : ''}`"
          description="已有记录仅供回看，未形成完整成功结果。"
        />

        <div class="agent-meters">
          <div class="agent-meter">
            <span class="muted small">模型轮次</span>
            <strong>{{ progress?.turnNo ?? 0 }} <small>/ {{ limitText('modelTurns') }} 次</small></strong>
            <progress :value="progress?.turnNo || 0" :max="limitMax('modelTurns')" />
          </div>
          <div class="agent-meter">
            <span class="muted small">工具尝试</span>
            <strong>{{ progress?.toolAttempts ?? 0 }} <small>/ {{ limitText('toolAttempts') }} 次</small></strong>
            <progress :value="progress?.toolAttempts || 0" :max="limitMax('toolAttempts')" />
          </div>
          <div class="agent-meter">
            <span class="muted small">网络尝试</span>
            <strong>{{ progress?.networkAttempts ?? 0 }} <small>/ {{ limitText('networkAttempts') }} 次</small></strong>
            <progress :value="progress?.networkAttempts || 0" :max="limitMax('networkAttempts')" />
          </div>
          <div class="agent-meter">
            <span class="muted small">总耗时</span>
            <strong>{{ elapsedText }} <small>/ {{ limitText('deadlineMinutes') }} 分钟</small></strong>
            <span class="muted small meter-note">含排队与准备</span>
            <progress aria-label="总耗时预算" :value="elapsedSeconds ?? undefined" :max="limitMax('deadlineMinutes') * 60" />
          </div>
          <div class="agent-meter">
            <span class="muted small" title="单次模型回复的已记录输出峰值与单轮输出上限">单轮输出 Token</span>
            <strong>{{ tokenText(progress?.outputUsage?.peakOutputTokens) }} <small>/ {{ limitText('outputTokens') }} Token</small></strong>
            <span class="muted small meter-note">{{ outputUsageNote }}</span>
            <progress aria-label="单轮输出 Token 预算" :value="progress?.outputUsage?.peakOutputTokens ?? undefined" :max="limitMax('outputTokens')" />
          </div>
        </div>
        <p v-if="progressError" class="error-line">{{ progressError }}</p>

        <div class="tabbar">
          <button type="button" :class="{ active: activeTab === 'result' }" @click="activeTab = 'result'">审查结果</button>
          <button type="button" :class="{ active: activeTab === 'input' }" @click="activeTab = 'input'">审查输入</button>
          <button type="button" :class="{ active: activeTab === 'calls' }" @click="activeTab = 'calls'">实际调用</button>
        </div>

        <div v-show="activeTab === 'result'">
          <a-spin :spinning="resultLoading">
            <AgentResultPanel
              v-if="result"
              :body="result.body"
              :downloading="downloadingMarkdown"
              @download="downloadMarkdown"
              @open-code="openCodeReference"
            />
            <div v-else-if="resultError" class="card pad">
              <a-alert type="warning" show-icon :message="resultError" description="任务已终止，但只保留调查过程与调用记录；这类结果不进入报告候选。" />
            </div>
            <div v-else class="card empty">调查尚未结束，结果发布后显示</div>
          </a-spin>
        </div>

        <section v-show="activeTab === 'calls'" class="card">
          <div class="section-top">
            <h2>逐次调用记录</h2>
            <span class="spacer" />
<!--            <span class="muted small">完整正文按需打开</span>-->
          </div>
          <a-alert v-if="callError" type="error" show-icon :message="callError" class="pad-alert" />
          <div v-if="!calls.length && !callError" class="empty">暂无已保存的模型或工具调用记录。</div>
          <div v-else class="agent-call-layout">
            <div class="agent-call-list" aria-label="逐次调用记录" @wheel.prevent="scrollCallList">
              <button
                v-for="call in calls"
                :key="call.id"
                type="button"
                :class="{ active: selectedCallId === call.id }"
                @click="openCall(call.id)"
              >
                <template v-if="call.kind === 'initial-model'">初始模型请求</template>
                <template v-else>第 {{ call.turnNo }} 轮 · {{ call.ordinal }}</template>
                <small class="call-tags">
                  <a-tag color="blue">{{ call.toolName }}</a-tag>
                  <a-tag :color="callTone(call.status)">{{ callStatusText(call.status) }}</a-tag>
                </small>
              </button>
            </div>
            <div class="agent-call-content">
              <a-spin :spinning="callLoading">
                <Transition name="soft-switch" mode="out-in">
                  <div v-if="callDetail" :key="callDetail.id" class="call-detail-view">
                  <div class="row wrap">
                    <strong>{{ callDetail.toolName }}</strong>
                    <span v-if="callDetail.kind !== 'initial-model'" class="call-position">{{ selectedCallPosition }} / {{ callTotal }}</span>
                    <span class="spacer" />
                    <StatusBadge :tone="callTone(callDetail.status)">{{ callStatusText(callDetail.status) }}</StatusBadge>
                  </div>
                  <p v-if="callDetail.kind === 'initial-model'" class="muted small">第 {{ callDetail.attempt }} 次传输尝试 · {{ formatTime(callDetail.startedAt) }}</p>
                  <p v-else class="muted small">提供方 {{ callDetail.providerToolCallId }} · {{ formatTime(callDetail.startedAt) }}</p>
                  <a-alert v-if="callDetail.kind === 'initial-model' && (callDetail.errorMessage || callDetail.errorCode)" type="error" show-icon :message="callDetail.errorMessage || callDetail.errorCode || ''" />
                  <h3>{{ callDetail.kind === 'initial-model' ? '完整请求' : '参数' }}</h3>
                  <pre v-if="callDetail.kind === 'initial-model'" class="preview-code">{{ callDetail.requestBody?.trim() ? pretty(callDetail.requestBody) : '未保存请求正文。' }}</pre>
                  <pre v-else class="preview-code">{{ pretty(callDetail.arguments) }}</pre>
                  <h3>{{ callDetail.kind === 'initial-model' ? '模型返回' : '返回' }}</h3>
                  <p v-if="callDetail.status === 'RUNNING'" class="muted small">请求正在执行，返回后自动更新。</p>
                  <pre v-else-if="callDetail.kind === 'initial-model'" class="preview-code">{{ callDetail.responseBody?.trim() ? pretty(callDetail.responseBody) : '未保存模型返回。' }}</pre>
                  <pre v-else class="preview-code">{{ JSON.stringify(callDetail.envelope, null, 2) }}</pre>
                  </div>
                  <div v-else key="empty" class="empty">选择左侧一次调用，查看保存的参数与返回。</div>
                </Transition>
              </a-spin>
            </div>
          </div>
        </section>

        <section v-show="activeTab === 'input'" class="card agent-input">
          <div class="review-input">
            <a-alert v-if="inputError" type="error" show-icon :message="inputError" class="input-error">
              <template #action><a-button size="small" :loading="inputLoading" @click="loadInput">重试</a-button></template>
            </a-alert>

            <section class="input-section" aria-label="任务配置">
              <h3>任务配置</h3>
              <dl class="input-pairs">
                <dt>审查项目</dt>
                <dd>{{ projectName || '项目名称不可用' }}</dd>
                <dt>审查类型</dt>
                <dd>Agent 分析</dd>
                <dt>分支与源码版本</dt>
                <dd v-if="inputKind === 'COMMITS'">
                  <code v-if="inputScope?.scope.branch" class="input-version">{{ inputScope.scope.branch }}</code>
                  <div v-if="inputScope?.scope.commits?.length" class="input-targets">
                    <div v-for="(commit, index) in inputScope.scope.commits" :key="commit">
                      <code class="input-version" :title="commit">C{{ index + 1 }} · {{ commit.slice(0, 12) }}</code>
                    </div>
                  </div>
                  <span v-else class="muted">{{ inputLoading ? '正在读取提交版本…' : (inputError ? '提交版本暂不可用' : '提交版本未记录') }}</span>
                  <small class="muted input-note">按所选顺序逐项分析提交差异。</small>
                </dd>
                <dd v-else><code class="input-version" :title="inputVersion.full">{{ inputVersion.label }}</code></dd>
                <template v-if="inputKind === 'TEXT'">
                  <dt>范围描述</dt>
                  <dd class="snapshot-text">{{ inputScope?.scope.description?.trim() ? inputScope.scope.description : (inputLoading ? '正在读取范围描述…' : (inputError ? '范围描述暂不可用' : '范围描述未记录')) }}</dd>
                </template>
                <template v-else-if="inputKind === 'FILES'">
                  <dt>审查文件/目录</dt>
                  <dd>
                    <div v-if="inputScope?.scope.paths?.length" class="input-targets">
                      <button v-for="path in inputScope.scope.paths" :key="path" type="button" class="source-link target-source-link" @click="openTargetFile(path)">
                        <code>{{ path }}</code>
                      </button>
                    </div>
                    <span v-else class="muted">{{ inputLoading ? '正在读取文件范围…' : (inputError ? '文件范围暂不可用' : '文件范围未记录') }}</span>
                  </dd>
                </template>
                <dt>使用的模型</dt>
                <dd>{{ task.config?.modelName || '模型名称未记录' }}</dd>
                <template v-if="inputScope?.referenceResultId">
                  <dt>参考结果</dt>
                  <dd><code>{{ inputScope.referenceResultId }}</code></dd>
                </template>
              </dl>
            </section>

            <section class="input-section" aria-label="审查规则">
              <h3>审查规则</h3>
              <dl class="input-pairs">
                <dt>本次要求</dt>
                <dd class="snapshot-text">{{ inputScope?.requirements?.trim() ? inputScope.requirements : (inputLoading ? '正在读取…' : (inputError ? '本次要求暂不可用' : '本次要求未记录')) }}</dd>
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
        </section>
      </template>
    </a-spin>

    <a-modal
      v-model:open="targetPreviewOpen"
      :title="targetPreviewPath || '固定版本代码'"
      width="min(1000px, calc(100vw - 48px))"
      :footer="null"
      destroy-on-close
    >
      <div class="preview-version">固定版本 <code>{{ targetPreviewSha || '—' }}</code></div>
      <a-spin :spinning="targetPreviewLoading">
        <a-alert v-if="targetPreviewError" type="error" show-icon :message="targetPreviewError" />
        <div v-else-if="targetPreviewContent !== null" class="target-code-preview" tabindex="0" role="region" aria-label="固定版本代码，可横向滚动">
          <CodeViewer :text="targetPreviewContent" :path="targetPreviewPath" />
        </div>
      </a-spin>
    </a-modal>

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
      :initial-mode="codePayload.sha ? 'full' : 'saved'"
    />
  </div>
</template>

<script setup lang="ts">
import { runtimeConfig } from '@/runtime/config'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import TaskDetailMetadata from '@/components/TaskDetailMetadata.vue'
import AgentResultPanel from '@/components/AgentResultPanel.vue'
import CodeViewer from '@/components/CodeViewer.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatSeconds } from '@/utils/duration'
import type { CodeReferencePayload } from '@/utils/codeReferences'
import {
  agentTasksApi,
  projectsApi,
  repositoryApi,
  tasksApi,
  type AgentTaskCopyInput,
  type AgentProgress,
  type AgentResultView,
  type AgentToolCallDetail,
  type AgentToolCallSummary,
  type CallDetailFull,
  type CallSummary,
  type TaskView
} from '@/api/v2'

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.id))

const task = ref<TaskView | null>(null)
const projectName = ref('')
const inputScope = ref<AgentTaskCopyInput | null>(null)
const inputLoading = ref(false)
const inputError = ref('')
const inputKind = computed(() => {
  const kind = inputScope.value?.scope.kind || String(task.value?.sourceKind || '')
  return kind === 'DIFF' ? 'COMMITS' : kind
})
const inputVersion = computed(() => {
  const branch = inputScope.value?.scope.branch || task.value?.branch || '分支未记录'
  const sha = inputScope.value?.scope.observedSha || task.value?.headSha
  return {
    label: `${branch} · ${sha ? sha.slice(0, 12) : '源码版本未记录'}`,
    full: `${branch} · ${sha || '源码版本未记录'}`
  }
})
const hasInputRule = computed(() => {
  const config = task.value?.config
  return Boolean(config?.promptId || config?.promptName || config?.promptVersionId ||
    config?.promptVersionNo != null || config?.promptContent ||
    inputScope.value?.guidancePromptId || inputScope.value?.guidancePromptVersionId)
})
const inputRuleLabel = computed(() => {
  if (!hasInputRule.value) return '未使用通用规则'
  const config = task.value?.config
  const version = config?.promptVersionNo != null ? `v${config.promptVersionNo}` : '版本未记录'
  return `${config?.promptName || '规则名称未记录'} · ${version}`
})
const taskLoading = ref(false)
const refreshing = ref(false)
const cancelling = ref(false)
const loadError = ref('')
const activeTab = ref('result')

const progress = ref<AgentProgress | null>(null)
const progressError = ref('')
const elapsedSeconds = computed(() => {
  const value = progress.value?.elapsedSeconds
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
})
const elapsedText = computed(() => elapsedSeconds.value === null ? '—' : formatSeconds(elapsedSeconds.value))
const outputUsageNote = computed(() => {
  const usage = progress.value?.outputUsage
  if (!usage) return '输出用量暂不可用'
  if (usage.totalOutputTokens === null) return `${usage.unreportedCalls} 次调用未返回输出用量`
  const total = `总计 ${tokenText(usage.totalOutputTokens)} Token`
  return usage.unreportedCalls > 0 ? `${total} · ${usage.unreportedCalls} 次用量未返回` : total
})

function tokenText(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value.toLocaleString() : '—'
}

const result = ref<AgentResultView | null>(null)
const resultLoading = ref(false)
const resultError = ref('')
const downloadingMarkdown = ref(false)

type InitialModelCall = {
  kind: 'initial-model'
  id: string
  modelCallId: string
  toolName: string
  status: string
  startedAt: string | null
  finishedAt: string | null
}
type AgentCallItem = (AgentToolCallSummary & { kind?: 'tool' }) | InitialModelCall
type AgentCallDetail = (AgentToolCallDetail & { kind?: 'tool' }) |
  (CallDetailFull & { kind: 'initial-model'; toolName: string; status: string })

const calls = ref<AgentCallItem[]>([])
const callTotal = ref(0)
const callError = ref('')

const callOpen = ref(false)
const callLoading = ref(false)
const callDetail = ref<AgentCallDetail | null>(null)
const selectedCallId = ref('')
const selectedCallPosition = computed(() => {
  const index = calls.value.filter(call => call.kind !== 'initial-model').findIndex((call) => call.id === selectedCallId.value)
  return index >= 0 ? index + 1 : 0
})

const codeOpen = ref(false)
const codePayload = ref<CodeReferencePayload | null>(null)
const targetPreviewOpen = ref(false)
const targetPreviewPath = ref('')
const targetPreviewSha = ref('')
const targetPreviewContent = ref<string | null>(null)
const targetPreviewLoading = ref(false)
const targetPreviewError = ref('')
let targetPreviewEpoch = 0

let timer: number | undefined
let progressEpoch = 0
let callEpoch = 0
let callDetailEpoch = 0
let pollEpoch = 0
let disposed = false

const terminalStates = ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']
const stateTone = computed(() => {
  const state = task.value?.state
  if (state === 'SUCCEEDED') return 'green'
  if (state === 'PARTIAL') return 'amber'
  if (state === 'FAILED' || state === 'CANCELLED') return 'red'
  return 'blue'
})

onMounted(() => {
  void loadTask()
})

onBeforeUnmount(() => {
  disposed = true
  stopPolling()
  progressEpoch++
  callEpoch++
  callDetailEpoch++
})

function goBack() {
  router.push('/reviews')
}

function stopPolling() {
  pollEpoch++
  if (timer) {
    window.clearTimeout(timer)
    timer = undefined
  }
}

async function loadTask() {
  stopPolling()
  taskLoading.value = true
  loadError.value = ''
  try {
    task.value = await tasksApi.detail(taskId.value)
    await Promise.all([loadProjectName(), loadProgress(), loadCalls(), loadInput()])
    if (isTerminal(task.value.state)) {
      await loadResult()
    } else {
      startPolling()
    }
  } catch (e) {
    loadError.value = `任务加载失败：${(e as Error).message}`
  } finally {
    taskLoading.value = false
  }
}

async function loadProjectName() {
  const projectId = task.value?.projectId
  projectName.value = ''
  if (!projectId) return
  try {
    const project = await projectsApi.detail(projectId)
    if (task.value?.projectId === projectId) projectName.value = project.name
  } catch {
    // 项目名称读取失败时保留项目 ID，不影响任务详情。
  }
}

async function loadInput() {
  inputLoading.value = true
  inputError.value = ''
  try {
    inputScope.value = await agentTasksApi.copyInput(taskId.value)
  } catch (failure) {
    inputScope.value = null
    inputError.value = `读取本次输入失败：${(failure as Error).message || '请稍后重试'}`
  } finally {
    inputLoading.value = false
  }
}

async function refreshAll() {
  refreshing.value = true
  try {
    await loadTask()
  } finally {
    refreshing.value = false
  }
}

function isTerminal(state: string | undefined) {
  return Boolean(state && terminalStates.includes(state))
}

function startPolling() {
  stopPolling()
  const epoch = pollEpoch
  async function poll() {
    if (disposed || epoch !== pollEpoch) return
    await Promise.all([loadProgress(), loadCalls()])
    if (disposed || epoch !== pollEpoch) return
    if (task.value && isTerminal(task.value.state)) {
      await loadResult()
      await loadCalls()
      return
    }
    timer = window.setTimeout(() => void poll(), runtimeConfig().agentPollMillis)
  }
  if (!disposed) timer = window.setTimeout(() => void poll(), runtimeConfig().agentPollMillis)
}

async function loadProgress() {
  const epoch = ++progressEpoch
  try {
    const value = await agentTasksApi.progress(taskId.value)
    if (epoch !== progressEpoch) return
    progress.value = value
    progressError.value = ''
    if (task.value && !isTerminal(task.value.state) && value.state
        && value.state !== task.value.state) {
      task.value = { ...task.value, state: value.state, stopReason: value.stopReason }
    }
  } catch (e) {
    if (epoch === progressEpoch) {
      progressError.value = `进度读取失败：${(e as Error).message}`
    }
  }
}

async function loadResult() {
  resultLoading.value = true
  resultError.value = ''
  try {
    result.value = await agentTasksApi.result(taskId.value)
  } catch (e) {
    result.value = null
    resultError.value = `未发布完整结果：${(e as Error).message}`
  } finally {
    resultLoading.value = false
  }
}

async function loadCalls() {
  const epoch = ++callEpoch
  const id = taskId.value
  const [toolsOutcome, initialOutcome] = await Promise.allSettled([
    readToolCalls(id, epoch), readInitialModelCall(id, epoch)
  ])
  if (disposed || epoch !== callEpoch || id !== taskId.value) return

  const tools = toolsOutcome.status === 'fulfilled'
    ? toolsOutcome.value.items : calls.value.filter(call => call.kind !== 'initial-model')
  if (toolsOutcome.status === 'fulfilled') callTotal.value = toolsOutcome.value.total
  let initial = calls.value.find(call => call.kind === 'initial-model')
  if (initialOutcome.status === 'fulfilled') {
    const record = initialOutcome.value
    initial = record ? {
      kind: 'initial-model', id: `initial-model:${record.id}`, modelCallId: record.id,
      toolName: '模型请求', status: record.state, startedAt: record.startedAt, finishedAt: record.finishedAt
    } : undefined
  }
  calls.value = initial ? [initial, ...tools] : tools
  callError.value = [
    toolsOutcome.status === 'rejected' ? `工具调用记录读取失败：${(toolsOutcome.reason as Error)?.message || '请稍后重试'}` : '',
    initialOutcome.status === 'rejected' ? `初始模型请求读取失败：${(initialOutcome.reason as Error)?.message || '请稍后重试'}` : ''
  ].filter(Boolean).join('；')
  const selected = calls.value.find(call => call.id === selectedCallId.value)
  if (selected && callDetail.value?.id === selected.id && selected.status !== callDetail.value.status) {
    await openCall(selected.id)
  }
}

async function readToolCalls(id: string, epoch: number) {
  const items: AgentToolCallSummary[] = []
  let pageNum = 1
  let total = 0
  do {
    const page = await agentTasksApi.toolCalls(id, { pageNum, pageSize: 100 })
    if (disposed || epoch !== callEpoch || id !== taskId.value) return { items: [], total: 0 }
    items.push(...page.items)
    total = page.total || items.length
    pageNum += 1
    if (!page.items.length) break
  } while (items.length < total)
  return { items, total }
}

async function readInitialModelCall(id: string, epoch: number): Promise<CallSummary | null> {
  let earliest: CallSummary | null = null
  let cursor: string | undefined
  const seen = new Set<string>()
  do {
    const page = await tasksApi.calls(id, { cursor, limit: 100 })
    if (disposed || epoch !== callEpoch || id !== taskId.value) return null
    // 接口按创建时间及 ID 倒序返回，完整遍历摘要后才能确定最早保存的 Agent 请求。
    const agentCalls = page.items.filter(call => call.purpose === 'AGENT')
    if (agentCalls.length) earliest = agentCalls[agentCalls.length - 1]
    if (!page.hasMore) return earliest
    if (!page.nextCursor || seen.has(page.nextCursor)) {
      throw new Error('分页信息异常，无法确认最早的模型请求')
    }
    seen.add(page.nextCursor)
    cursor = page.nextCursor
  } while (cursor)
  return earliest
}

function stateLabel(state: string) {
  return ({
    QUEUED: '排队中',
    RUNNING: '执行中',
    SUCCEEDED: '已完成',
    PARTIAL: '部分完成',
    FAILED: '失败',
    CANCELLED: '已取消'
  } as Record<string, string>)[state] || state
}

function limitNumber(key: string) {
  const value = progress.value?.limits?.[key]
  return typeof value === 'number' ? value : null
}

function limitText(key: string) {
  const value = limitNumber(key)
  return value === null ? '—' : value.toLocaleString()
}

function limitMax(key: string) {
  return Math.max(limitNumber(key) || 1, 1)
}

function callTone(status: string): 'green' | 'amber' | 'blue' | 'red' | 'default' {
  const value = status.toUpperCase()
  if (value === 'RUNNING') return 'blue'
  if (value === 'UNREADABLE' || value === 'INTERRUPTED' || value === 'CANCELLED') return 'red'
  if (value === 'OK' || value === 'SUCCEEDED') return 'blue'
  if (value.includes('TRUNC') || value.includes('LIMIT') || value.startsWith('REJECTED')) return 'amber'
  if (value.includes('FAIL') || value.includes('ERROR') || value.includes('INVALID') || value.includes('TIMEOUT')) return 'red'
  return 'amber'
}

function callStatusText(status: string) {
  return ({ RUNNING: '执行中', OK: '成功', SUCCEEDED: '成功', FAILED: '失败', UNREADABLE: '读取失败', NOT_FOUND: '未找到',
    INVALID_ARGUMENT: '参数无效', TIMEOUT: '超时', ERROR: '失败', INTERRUPTED: '已中断',
    CANCELLED: '已取消', REJECTED_BATCH: '整批拒绝（未执行）' } as Record<string, string>)[status] || status
}

function actionText(action: string | null | undefined) {
  if (!action) return ''
  if (action.startsWith('TOOL_CALL:')) return `调用 ${action.slice('TOOL_CALL:'.length)}`
  return ({ MODEL_CALL: '等待模型响应', TOOL_CALL: '工具调用已完成',
    PROTOCOL_RETRY: '纠正模型输出', RUNNING: '执行中' } as Record<string, string>)[action] || action
}

function scrollCallList(event: WheelEvent) {
  const list = event.currentTarget as HTMLElement
  list.scrollLeft += Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY
}

async function openCall(callId: string) {
  selectedCallId.value = callId
  activeTab.value = 'calls'
  callOpen.value = true
  callLoading.value = true
  const epoch = ++callDetailEpoch
  const id = taskId.value
  const entry = calls.value.find(call => call.id === callId)
  callDetail.value = null
  try {
    if (entry?.kind === 'initial-model') {
      const detail = await tasksApi.call(id, entry.modelCallId)
      if (!disposed && epoch === callDetailEpoch && id === taskId.value) {
        callDetail.value = { ...detail, kind: 'initial-model', id: callId, toolName: '初始模型请求', status: detail.state }
      }
    } else {
      const detail = await agentTasksApi.toolCall(id, callId)
      if (!disposed && epoch === callDetailEpoch && id === taskId.value) callDetail.value = detail
    }
  } catch (e) {
    if (!disposed && epoch === callDetailEpoch && id === taskId.value) callError.value = `调用详情读取失败：${(e as Error).message}`
  } finally {
    if (epoch === callDetailEpoch) callLoading.value = false
  }
}

function openCodeReference(value: CodeReferencePayload) {
  codePayload.value = value
  codeOpen.value = true
}

async function openTargetFile(path: string) {
  if (!task.value || !inputScope.value) return
  const sha = inputScope.value.scope.observedSha || task.value.headSha || ''
  targetPreviewOpen.value = true
  targetPreviewPath.value = path
  targetPreviewSha.value = sha
  targetPreviewContent.value = null
  targetPreviewError.value = ''
  const epoch = ++targetPreviewEpoch
  targetPreviewLoading.value = true
  try {
    if (!sha) throw new Error('任务没有保存固定版本，无法读取代码')
    const snapshot = await repositoryApi.file(task.value.projectId, sha, path)
    if (epoch === targetPreviewEpoch) targetPreviewContent.value = snapshot.content
  } catch (e) {
    if (epoch === targetPreviewEpoch) targetPreviewError.value = (e as Error).message || '固定版本代码读取失败'
  } finally {
    if (epoch === targetPreviewEpoch) targetPreviewLoading.value = false
  }
}

async function cancel() {
  if (!task.value) return
  cancelling.value = true
  try {
    task.value = await tasksApi.cancel(taskId.value, task.value.revision)
    message.success('已请求取消')
    await loadProgress()
  } catch (e) {
    message.error((e as Error).message)
  } finally {
    cancelling.value = false
  }
}

async function downloadMarkdown() {
  if (!result.value) return
  downloadingMarkdown.value = true
  try {
    const evidence = await buildMarkdownEvidence(result.value.body.citations)
    const body = separateAdjacentCitationMarkers(
      result.value.body.markdown,
      result.value.body.citations.map((citation) => citation.id)
    )
    const markdown = [body.trimEnd(), evidence].filter(Boolean).join('\n\n')
    const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${result.value.body.title || 'agent-result'}.md`
    link.click()
    URL.revokeObjectURL(url)
  } finally {
    downloadingMarkdown.value = false
  }
}

function separateAdjacentCitationMarkers(markdown: string, citationIds: string[]): string {
  const ids = [...new Set(citationIds.filter((id) => /^E\d{1,3}$/.test(id)))]
  if (!ids.length) return markdown
  const alternatives = ids.sort((a, b) => b.length - a.length)
    .map((id) => id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('|')
  const marker = new RegExp(`\\[(${alternatives})\\](?=\\[(?:${alternatives})\\])`, 'g')
  return markdown.replace(marker, '[$1]、')
}

async function buildMarkdownEvidence(citations: AgentResultView['body']['citations']): Promise<string> {
  if (!citations.length) return ''
  const materialLoads = new Map<string, ReturnType<typeof tasksApi.material>>()
  const entries = await Promise.all(citations.map(async (citation) => {
    let materialLoad = materialLoads.get(citation.materialId)
    if (!materialLoad) {
      materialLoad = tasksApi.material(taskId.value, citation.materialId)
      materialLoads.set(citation.materialId, materialLoad)
    }

    let excerpt = ''
    try {
      const material = await materialLoad
      const lines = (material.content || '').split(/\r?\n/)
      const materialStart = material.metadata.startLine || 1
      const from = Math.max(0, citation.startLine - materialStart)
      const to = Math.min(lines.length, citation.endLine - materialStart + 1)
      excerpt = lines.slice(from, to)
        .map((line, index) => `${citation.startLine + index} | ${line}`)
        .join('\n')
    } catch {
      // 保留离线引用位置；即使材料正文暂时不可读，也不丢失 E 编号和路径信息。
    }

    const details = [
      `文件：${citation.path}`,
      `引用行：${citation.startLine}–${citation.endLine}`,
      citation.sha ? `固定版本：${citation.sha}` : ''
    ].filter(Boolean)
    let block = `### [${citation.id}]\n\n${details.join('；')}`
    if (excerpt) {
      const longestFence = Math.max(0, ...Array.from(excerpt.matchAll(/`+/g), (match) => match[0].length))
      const fence = '`'.repeat(Math.max(3, longestFence + 1))
      block += `\n\n${fence}text\n${excerpt}\n${fence}`
    } else {
      block += '\n\n证据正文暂时无法读取。'
    }
    return block
  }))
  return `## 引用证据\n\n${entries.join('\n\n')}`
}

function pretty(value: string | null) {
  if (!value) return '—'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function formatTime(value: string | null | undefined) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.agent-page { padding-bottom: 24px; }
.back-row { margin-bottom: 16px; }
.back-link, .quiet, .source-link {
  border: 0;
  background: transparent;
  box-shadow: none;
  color: var(--blue);
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}
.heading {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  margin-bottom: 24px;
}
.heading h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.4;
}
.sub { margin: 8px 0 0; color: var(--muted); font-size: 15px; }
.row { display: flex; align-items: center; gap: 10px; }
.wrap { flex-wrap: wrap; }
.actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.spacer { flex: 1; }
.muted { color: var(--muted); }
.snapshot-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.target-code-preview {
  min-width: 0;
  max-width: 100%;
  max-height: 55vh;
  overflow: auto;
  overscroll-behavior: contain;
}
.small { font-size: 12px; }
.mono { font-family: var(--font-mono); }
.mb16 { margin: 0 0 16px; }
.agent-label {
  background: #eef2ff;
  color: #4f46e5;
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 11px;
  font-weight: 600;
}
.agent-meters {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
  margin: 0 0 24px;
}
.agent-meter {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--shadow);
  padding: 16px 20px;
}
.agent-meter strong { display: block; margin: 7px 0; font-size: 18px; font-weight: 700; }
.agent-meter small { font-size: 11px; font-weight: 400; color: var(--muted); }
.agent-meter progress { height: 5px; width: 100%; margin-top: auto; accent-color: var(--blue); }
.agent-meter .meter-note { margin-bottom: 8px; font-size: 11px; }
.error-line { color: var(--red); font-size: 12px; margin: -12px 0 16px; }
.tabbar {
  display: flex;
  gap: 28px;
  margin-bottom: 24px;
  padding: 0 24px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--shadow);
  overflow: auto;
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
  white-space: nowrap;
}
.tabbar button.active { color: var(--blue); border-bottom: 2px solid var(--blue); font-weight: 600; }
.card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  overflow: hidden;
}
.agent-input .review-input { padding: 20px 24px 24px; }
.agent-input .input-section { padding: 20px 0; border-top: 1px solid var(--line); }
.agent-input .input-section:last-child { padding-bottom: 0; }
.agent-input .input-section h3 { margin: 0 0 15px; font-size: 16px; }
.agent-input .input-pairs {
  display: grid;
  grid-template-columns: 130px minmax(0, 1fr);
  gap: 13px 18px;
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
}
.agent-input .input-pairs dt { color: var(--muted); }
.agent-input .input-pairs dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
.agent-input .input-version {
  padding: 3px 7px;
  border-radius: 5px;
  background: var(--bg);
  box-decoration-break: clone;
  -webkit-box-decoration-break: clone;
}
.agent-input .input-targets { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; max-height: 240px; overflow: auto; }
.agent-input .target-source-link { margin-top: 0; font-weight: 400; }
.agent-input .input-note { display: block; margin-top: 6px; }
.agent-input .input-error { margin-bottom: 16px; }
.agent-input summary { color: var(--blue); cursor: pointer; }
.agent-input .input-rule-content {
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
.section-top {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--line);
}
.section-top h2, .section-top h3 { margin: 0; }
.section-top.flat { border-bottom: 0; padding-bottom: 8px; }
.pad { padding: 18px 22px; }
.source-link {
  display: inline-block;
  max-width: 100%;
  min-width: 0;
  margin-top: 6px;
  padding: 3px 7px;
  background: var(--soft);
  border-radius: 6px;
  font-family: var(--font-mono);
  text-align: left;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.target-source-link { display: inline-block; text-align: left; }
.target-source-link code { color: inherit; }
.preview-version { margin-bottom: 12px; color: var(--muted); font-size: 12px; }
.agent-timeline { padding: 4px 0 0 13px; }
.agent-event {
  position: relative;
  margin-left: 6px;
  border-left: 1px solid var(--line);
  padding: 0 0 24px 26px;
}
.agent-event:last-child { border-left-color: transparent; padding-bottom: 2px; }
.agent-event::before {
  content: '';
  position: absolute;
  left: -5px;
  top: 7px;
  width: 9px;
  height: 9px;
  background: #93b4f5;
  border: 2px solid #fff;
  border-radius: 50%;
}
.agent-event p { margin: 5px 0 0; font-size: 12px; color: var(--muted); }
.agent-event strong { font-size: 13px; }
.agent-call-layout { display: flex; flex-direction: column; min-width: 0; }
.agent-call-list { display: flex; gap: 10px; padding: 14px 20px; border-bottom: 1px solid var(--line); overflow-x: auto; overflow-y: hidden; }
.agent-call-list > button {
  display: flex;
  flex: 0 0 150px;
  flex-direction: column;
  align-items: flex-start;
  gap: 7px;
  padding: 10px 12px;
  color: var(--ink);
  text-align: left;
  white-space: nowrap;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: none;
  font-weight: 500;
}
.agent-call-list > button.active { background: var(--soft); border-color: #b9d0ff; color: var(--blue); }
.agent-call-list small.call-tags { display: flex; gap: 5px; margin: 0; }
.agent-call-list .call-tags :deep(.ant-tag) { margin: 0; }
.call-position { color: var(--muted); font-size: 11px; white-space: nowrap; }
.agent-call-content { padding: 20px; min-width: 0; }
.agent-call-content h3 { margin: 18px 0 8px; font-size: 14px; }
.agent-call-content .preview-code { max-height: 470px; }
.preview-code {
  max-height: 280px;
  overflow: auto;
  margin: 0;
  background: var(--preview);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.materials { margin-top: 14px; }
.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
}
.empty { text-align: center; padding: 48px 25px; color: var(--muted); }
.pad-alert { margin: 16px 24px 0; }
@media (max-width: 1250px) {
  .agent-meters { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
  .agent-meter { padding: 14px; }
}
@media (max-width: 1000px) {
  .agent-meters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .heading { flex-wrap: wrap; align-items: flex-start; }
  .heading h1 { font-size: 26px; }
  .actions { flex-wrap: wrap; }
  .agent-meters { grid-template-columns: 1fr; }
  .agent-meter strong { font-size: 16px; }
  .tabbar { gap: 18px; }
  .agent-input .input-pairs { grid-template-columns: 96px minmax(0, 1fr); gap: 12px; }
}
</style>
