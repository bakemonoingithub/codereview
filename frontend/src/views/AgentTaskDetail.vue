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
              <span class="agent-label">只读</span>
            </div>
            <TaskDetailMetadata :task-id="task.id">
              {{ projectName || task.projectId }} · Agent 分析 · {{ sourceLabel(task.sourceKind) }}
              <template v-if="task.headSha"> · <code>{{ task.headSha.slice(0, 7) }}</code></template>
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
          :message="`正在执行：${progress?.currentAction || stateLabel(task.state)}`"
          description="已查范围与调用逐步保存。此处只显示工具动作，不显示模型内部思考。"
        />
        <a-alert
          v-else-if="task.state === 'SUCCEEDED'"
          class="mb16"
          type="info"
          show-icon
          message="审查结果已固定保存。进一步分析需另建任务；生成报告需另行选材。"
        />
        <a-alert
          v-else
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
            <span class="muted small">期限</span>
            <strong>{{ limitText('deadlineMinutes') }} <small>分钟</small></strong>
            <span class="muted small">输出上限 {{ limitText('outputTokens') }} tokens</span>
          </div>
        </div>
        <p v-if="progressError" class="error-line">{{ progressError }}</p>

        <div class="tabbar">
          <button type="button" :class="{ active: activeTab === 'result' }" @click="activeTab = 'result'">审查结果</button>
          <button type="button" :class="{ active: activeTab === 'calls' }" @click="activeTab = 'calls'">实际调用 · {{ callTotal }}</button>
          <button type="button" :class="{ active: activeTab === 'input' }" @click="activeTab = 'input'">任务输入</button>
        </div>

        <div v-show="activeTab === 'result'">
          <a-spin :spinning="resultLoading">
            <div v-if="result" class="agent-layout">
              <article class="card agent-article">
                <div class="row wrap result-head">
                  <StatusBadge :tone="result.body.answerStatus === 'ANSWERED' ? 'green' : 'amber'">
                    {{ result.body.answerStatus === 'ANSWERED' ? '已回答' : '材料不足' }}
                  </StatusBadge>
                  <span class="spacer" />
                  <a-button size="small" :loading="downloadingMarkdown" @click="downloadMarkdown">下载 Markdown</a-button>
                </div>
                <h2>{{ result.body.title }}</h2>
                <p class="muted">{{ result.body.summary }}</p>
                <a-alert
                  v-if="result.body.answerStatus !== 'ANSWERED'"
                  class="mb16"
                  type="warning"
                  show-icon
                  message="本次调查材料不足；报告选材时会保留该限制，不视为全部问题已回答。"
                />
                <div v-if="result.body.unresolved.length" class="unresolved">
                  <strong>未解决事项</strong>
                  <ul>
                    <li v-for="(item, index) in result.body.unresolved" :key="index">{{ item }}</li>
                  </ul>
                </div>
                <MarkdownView
                  :text="result.body.markdown"
                  :citation-ids="citationIds"
                  @citation-click="openCitation"
                />
              </article>
              <aside class="agent-side">
                <section class="card">
                  <div class="section-top"><h3>代码证据</h3><span class="spacer" /><span class="muted small">固定版本</span></div>
                  <div class="pad">
                    <p v-if="!result.body.citations.length" class="muted small">没有引用。</p>
                    <div v-for="item in result.body.citations" :key="item.id" class="agent-evidence">
                      <span class="muted small">{{ item.id }}</span>
                      <button
                        type="button"
                        class="source-link"
                        @click="openMaterial(item.materialId, item.startLine, item.endLine, item.id)"
                      >
                        {{ item.path }} : {{ item.startLine }}–{{ item.endLine }} ↗
                      </button>
                      <p class="muted small">
                        <code>{{ item.sha?.slice(0, 7) || '—' }}</code>
                        <template v-if="item.side && item.side !== 'FILE'"> · {{ item.side === 'BEFORE' ? '变更前' : '变更后' }}</template>
                      </p>
                    </div>
                  </div>
                </section>
              </aside>
            </div>
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
            <span class="muted small">完整正文按需打开</span>
          </div>
          <a-alert v-if="callError" type="error" show-icon :message="callError" class="pad-alert" />
          <div v-if="!calls.length" class="empty">尚未发送模型调用，当前没有请求快照。</div>
          <div v-else class="agent-call-layout">
            <div class="agent-call-list" aria-label="逐次调用记录" @wheel.prevent="scrollCallList">
              <button
                v-for="call in calls"
                :key="call.id"
                type="button"
                :class="{ active: selectedCallId === call.id }"
                @click="openCall(call.id)"
              >
                第 {{ call.turnNo }} 轮 · {{ call.ordinal }}
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
                    <span class="call-position">{{ selectedCallPosition }} / {{ callTotal }}</span>
                    <span class="spacer" />
                    <StatusBadge :tone="callTone(callDetail.status)">{{ callStatusText(callDetail.status) }}</StatusBadge>
                  </div>
                  <p class="muted small">提供方 {{ callDetail.providerToolCallId }} · {{ formatTime(callDetail.startedAt) }}</p>
                  <h3>参数</h3>
                  <pre class="preview-code">{{ pretty(callDetail.arguments) }}</pre>
                  <h3>返回</h3>
                  <pre class="preview-code">{{ JSON.stringify(callDetail.envelope, null, 2) }}</pre>
                  </div>
                  <div v-else key="empty" class="empty">选择左侧一次调用，查看保存的参数与返回。</div>
                </Transition>
              </a-spin>
            </div>
          </div>
        </section>

        <section v-show="activeTab === 'input'" class="card input-readonly">
          <div class="section-top"><h2>提交时保存的输入</h2><span class="spacer" /><span class="muted small">只读历史快照</span></div>
          <div class="pad">
            <dl class="input-list">
              <dt>范围</dt>
              <dd>{{ inputScope ? scopeLabel(inputScope.scope.kind) : sourceLabel(task.sourceKind) }}<template v-if="inputScope?.scope.branch"> · {{ inputScope.scope.branch }}</template></dd>
              <dt>固定版本</dt>
              <dd><code>{{ inputScope?.scope.observedSha || task.headSha || '—' }}</code></dd>
              <template v-if="inputScope?.scope.description">
                <dt>范围描述</dt><dd class="snapshot-text">{{ inputScope.scope.description }}</dd>
              </template>
              <dt>目标</dt>
              <dd>
                <template v-if="inputScope?.scope.kind === 'COMMITS'">
                  <p v-for="(commit, index) in inputScope.scope.commits || []" :key="commit"><code>C{{ index + 1 }} · {{ commit }}</code></p>
                </template>
                <template v-else-if="inputScope?.scope.kind === 'FILES'">
                  <p v-for="path in inputScope.scope.paths || []" :key="path">
                    <button type="button" class="source-link target-source-link" @click="openTargetFile(path)">
                      <code>{{ path }}</code>
                    </button>
                  </p>
                </template>
                <p v-else-if="!inputScope" class="muted small">正在读取提交时保存的范围…</p>
                <p v-else class="muted small">按文字范围调查</p>
              </dd>
              <dt>本次要求</dt>
              <dd class="snapshot-text">{{ inputScope?.requirements || (inputLoading ? '正在读取…' : '—') }}</dd>
              <dt>模型与指导提示词</dt>
              <dd>
                {{ task.config?.modelName || '—' }}
                <br />
                {{ task.config?.promptId ? '指导提示词 ' + task.config.promptId + ' · v' + task.config.promptVersionNo : '未选指导提示词' }}
                <template v-if="inputScope?.referenceResultId"><br />参考结果：{{ inputScope.referenceResultId }}</template>
              </dd>
              <dt>创建 / 期限</dt>
              <dd>{{ formatTime(task.createdAt) }} · {{ formatTime(task.deadlineAt) }}</dd>
            </dl>
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

    <MaterialReaderModal
      v-if="task"
      v-model:open="materialOpen"
      :task-id="task.id"
      :project-id="task.projectId"
      :head-sha="task.headSha || ''"
      :material-id="materialId"
      :focus-start="materialFocusStart"
      :focus-end="materialFocusEnd"
      :focus-label="materialFocusLabel"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import TaskDetailMetadata from '@/components/TaskDetailMetadata.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import CodeViewer from '@/components/CodeViewer.vue'
import MaterialReaderModal from '@/components/MaterialReaderModal.vue'
import StatusBadge from '@/components/StatusBadge.vue'
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
  type TaskView
} from '@/api/v2'

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.id))

const task = ref<TaskView | null>(null)
const projectName = ref('')
const inputScope = ref<AgentTaskCopyInput | null>(null)
const inputLoading = ref(false)
const taskLoading = ref(false)
const refreshing = ref(false)
const cancelling = ref(false)
const loadError = ref('')
const activeTab = ref('result')

const progress = ref<AgentProgress | null>(null)
const progressError = ref('')

const result = ref<AgentResultView | null>(null)
const resultLoading = ref(false)
const resultError = ref('')
const downloadingMarkdown = ref(false)

const calls = ref<AgentToolCallSummary[]>([])
const callTotal = ref(0)
const callError = ref('')

const callOpen = ref(false)
const callLoading = ref(false)
const callDetail = ref<AgentToolCallDetail | null>(null)
const selectedCallId = ref('')
const selectedCallPosition = computed(() => {
  const index = calls.value.findIndex((call) => call.id === selectedCallId.value)
  return index >= 0 ? index + 1 : 0
})

const materialOpen = ref(false)
const materialId = ref('')
const materialFocusStart = ref<number | null>(null)
const materialFocusEnd = ref<number | null>(null)
const materialFocusLabel = ref('')
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

const terminalStates = ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']
const stateTone = computed(() => {
  const state = task.value?.state
  if (state === 'SUCCEEDED') return 'green'
  if (state === 'PARTIAL') return 'amber'
  if (state === 'FAILED' || state === 'CANCELLED') return 'red'
  return 'blue'
})
const citationIds = computed(() => result.value?.body.citations.map((item) => item.id) || [])

onMounted(() => {
  void loadTask()
})

onBeforeUnmount(() => {
  stopPolling()
})

function goBack() {
  router.push('/reviews')
}

function stopPolling() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function loadTask() {
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
  try {
    inputScope.value = await agentTasksApi.copyInput(taskId.value)
  } catch {
    inputScope.value = null
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
  timer = window.setInterval(async () => {
    await loadProgress()
    if (task.value && isTerminal(task.value.state)) {
      stopPolling()
      await loadResult()
      await loadCalls()
    }
  }, 2000)
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
  try {
    const collected: AgentToolCallSummary[] = []
    let pageNum = 1
    let total = 0
    do {
      const page = await agentTasksApi.toolCalls(taskId.value, { pageNum, pageSize: 100 })
      if (epoch !== callEpoch) return
      collected.push(...page.items)
      total = page.total || collected.length
      pageNum += 1
      if (!page.items.length) break
    } while (collected.length < total)
    calls.value = collected
    callTotal.value = total
    callError.value = ''
  } catch (e) {
    if (epoch === callEpoch) {
      callError.value = `调用记录读取失败：${(e as Error).message}`
    }
  }
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

function sourceLabel(kind: string) {
  return ({
    FILES: '文件范围',
    DIFF: '提交差异',
    TEXT: '文字范围',
    COMMITS: '逐提交差异'
  } as Record<string, string>)[kind] || kind
}

function scopeLabel(kind: string) {
  return ({ TEXT: '文字范围', FILES: '文件／目录', COMMITS: '提交比较' } as Record<string, string>)[kind] || kind
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
  if (value === 'OK') return 'blue'
  if (value.includes('TRUNC') || value.includes('LIMIT') || value.startsWith('REJECTED')) return 'amber'
  if (value.includes('FAIL') || value.includes('ERROR') || value.includes('INVALID') || value.includes('TIMEOUT')) return 'red'
  return 'amber'
}

function callStatusText(status: string) {
  return status === 'REJECTED_BATCH' ? '整批拒绝（未执行）' : status
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
  try {
    const detail = await agentTasksApi.toolCall(taskId.value, callId)
    if (epoch === callDetailEpoch) callDetail.value = detail
  } catch (e) {
    if (epoch === callDetailEpoch) callError.value = `调用详情读取失败：${(e as Error).message}`
  } finally {
    if (epoch === callDetailEpoch) callLoading.value = false
  }
}

function openMaterial(id: string, startLine?: number, endLine?: number, label?: string) {
  materialId.value = id
  materialFocusStart.value = startLine ?? null
  materialFocusEnd.value = endLine ?? null
  materialFocusLabel.value = label ? `${label} 引用` : ''
  materialOpen.value = true
}

function openCitation(id: string) {
  const citation = result.value?.body.citations.find((item) => item.id === id)
  if (citation) openMaterial(citation.materialId, citation.startLine, citation.endLine, citation.id)
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
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin: 0 0 24px;
}
.agent-meter {
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--shadow);
  padding: 16px 20px;
}
.agent-meter strong { display: block; margin: 7px 0; font-size: 18px; font-weight: 700; }
.agent-meter small { font-size: 11px; font-weight: 400; color: var(--muted); }
.agent-meter progress { height: 5px; width: 100%; accent-color: var(--blue); }
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
.input-readonly { background: #f8fafc; }
.input-readonly .pad { color: #475569; }
.input-readonly code { color: #64748b; }
.agent-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 380px);
  gap: 24px;
  align-items: start;
}
.agent-article, .agent-side { min-width: 0; }
.agent-side .pad {
  box-sizing: border-box;
  height: min(70vh, 720px);
  overflow-y: auto;
  overscroll-behavior: contain;
}
.agent-article { padding: 28px 32px; line-height: 1.95; }
.agent-article h2 { margin: 4px 0 14px; font-size: 20px; }
.result-head { margin-bottom: 16px; }
.unresolved ul { margin: 6px 0 0 18px; padding: 0; font-size: 13px; }
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
.agent-evidence {
  display: block;
  min-width: 0;
  padding: 13px 0;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.agent-evidence:last-child { border-bottom: 0; }
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
.input-list { margin: 0; font-size: 13px; }
.input-list dt { color: var(--muted); margin-top: 14px; font-size: 12px; }
.input-list dt:first-child { margin-top: 0; }
.input-list dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.empty { text-align: center; padding: 48px 25px; color: var(--muted); }
.pad-alert { margin: 16px 24px 0; }
@media (max-width: 1250px) {
  .agent-layout { grid-template-columns: minmax(0, 1fr) minmax(320px, 340px); }
  .agent-meters { gap: 10px; }
  .agent-meter { padding: 14px; }
  .agent-article { padding: 24px; }
}
@media (max-width: 1000px) {
  .agent-layout { grid-template-columns: 1fr; }
  .agent-meters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .agent-side .pad { height: min(60vh, 560px); }
}
@media (max-width: 760px) {
  .heading { flex-wrap: wrap; align-items: flex-start; }
  .heading h1 { font-size: 26px; }
  .actions { flex-wrap: wrap; }
  .agent-meters, .agent-layout { grid-template-columns: 1fr; }
  .agent-article { padding: 20px; }
  .agent-meter strong { font-size: 16px; }
  .tabbar { gap: 18px; }
}
</style>
