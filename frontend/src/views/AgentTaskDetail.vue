<template>
  <div class="agent-detail">
    <div class="back-row">
      <a-button type="link" @click="goBack">← 返回审查中心</a-button>
      <a-button v-if="task" type="link" @click="router.push('/agents/new')">发起新的 Agent 调查</a-button>
    </div>

    <a-alert v-if="loadError" type="error" show-icon :message="loadError">
      <template #action><a-button size="small" @click="loadTask">重试</a-button></template>
    </a-alert>

    <a-spin :spinning="taskLoading">
      <template v-if="task">
        <div class="detail-head">
          <div>
            <div class="row wrap">
              <h2 :title="task.id">{{ task.name || 'Agent 任务' }}</h2>
              <a-tag color="blue">Agent 调查</a-tag>
              <StatusBadge :tone="stateTone">{{ task.state }}</StatusBadge>
              <a-tag v-if="task.stopReason" color="orange">{{ task.stopReason }}</a-tag>
            </div>
            <div class="muted mono">{{ task.id }}</div>
          </div>
          <a-space wrap>
            <a-button :loading="refreshing" @click="refreshAll">刷新</a-button>
            <a-button
              v-if="task.state === 'QUEUED' || task.state === 'RUNNING'"
              danger
              :loading="cancelling"
              @click="cancel"
            >
              取消任务
            </a-button>
          </a-space>
        </div>

        <a-row :gutter="16">
          <a-col :xs="24" :lg="16">
            <a-tabs v-model:activeKey="activeTab">
              <a-tab-pane key="result" tab="结果">
                <a-spin :spinning="resultLoading">
                  <template v-if="result">
                    <div class="result-head">
                      <h3>{{ result.body.title }}</h3>
                      <a-space>
                        <a-tag :color="result.body.answerStatus === 'ANSWERED' ? 'green' : 'orange'">
                          {{ result.body.answerStatus === 'ANSWERED' ? '已回答' : '材料不足' }}
                        </a-tag>
                        <a-button size="small" @click="downloadMarkdown">下载 Markdown</a-button>
                      </a-space>
                    </div>
                    <p class="muted">{{ result.body.summary }}</p>
                    <a-alert
                      v-if="result.body.answerStatus !== 'ANSWERED'"
                      type="warning"
                      show-icon
                      message="本次调查材料不足；报告选材时会保留该限制，不视为全部问题已回答。"
                      class="mb12"
                    />
                    <div v-if="result.body.unresolved.length" class="unresolved">
                      <strong>未解决事项</strong>
                      <ul>
                        <li v-for="(item, index) in result.body.unresolved" :key="index">{{ item }}</li>
                      </ul>
                    </div>
                    <MarkdownView :text="result.body.markdown" />
                    <div class="citations">
                      <div class="section-head">
                        <strong>来源</strong>
                        <span class="muted">点击查看固定版本材料；行号为原始行号</span>
                      </div>
                      <a-list size="small" :data-source="result.body.citations">
                        <template #renderItem="{ item }">
                          <a-list-item>
                            <a-space wrap>
                              <a-tag color="blue">{{ item.id }}</a-tag>
                              <a-button type="link" size="small" @click="openMaterial(item.materialId)">
                                {{ item.path }}
                              </a-button>
                              <span class="muted">第 {{ item.startLine }}–{{ item.endLine }} 行</span>
                              <span v-if="item.side && item.side !== 'FILE'" class="muted">
                                {{ item.side === 'BEFORE' ? '变更前' : '变更后' }}
                              </span>
                            </a-space>
                          </a-list-item>
                        </template>
                      </a-list>
                    </div>
                  </template>
                  <a-alert
                    v-else-if="resultError"
                    type="info"
                    show-icon
                    :message="resultError"
                    description="任务已终止，但只保留调查过程与调用记录；这类结果不进入报告候选。"
                  />
                  <a-empty v-else description="调查尚未结束，结果发布后显示" />
                </a-spin>
              </a-tab-pane>

              <a-tab-pane key="calls" tab="调用记录">
                <a-alert v-if="callError" type="error" show-icon :message="callError" />
                <a-table
                  :data-source="calls"
                  :columns="callColumns"
                  :pagination="false"
                  size="small"
                  row-key="id"
                  :custom-row="callRow"
                />
                <div class="table-footer">
                  <a-space>
                    <a-button size="small" :disabled="callPage <= 1" @click="changeCallPage(-1)">上一页</a-button>
                    <span class="muted">第 {{ callPage }} 页 · 共 {{ callTotal }} 次</span>
                    <a-button size="small" :disabled="calls.length < callPageSize" @click="changeCallPage(1)">下一页</a-button>
                  </a-space>
                </div>
              </a-tab-pane>

              <a-tab-pane key="progress" tab="进度">
                <a-descriptions bordered size="small" :column="2">
                  <a-descriptions-item label="状态">{{ progress?.state || task.state }}</a-descriptions-item>
                  <a-descriptions-item label="停止原因">{{ progress?.stopReason || '—' }}</a-descriptions-item>
                  <a-descriptions-item label="模型轮次">{{ progress?.turnNo ?? 0 }}</a-descriptions-item>
                  <a-descriptions-item label="工具尝试">{{ progress?.toolAttempts ?? 0 }}</a-descriptions-item>
                  <a-descriptions-item label="网络尝试">{{ progress?.networkAttempts ?? 0 }}</a-descriptions-item>
                  <a-descriptions-item label="当前动作">{{ progress?.currentAction || '—' }}</a-descriptions-item>
                </a-descriptions>
                <div v-if="progress?.limits" class="limits">
                  <div class="section-head"><strong>本次冻结限额</strong></div>
                  <a-space wrap>
                    <a-tag>轮次 {{ progress.limits.modelTurns }}</a-tag>
                    <a-tag>网络 {{ progress.limits.networkAttempts }}</a-tag>
                    <a-tag>工具 {{ progress.limits.toolAttempts }}</a-tag>
                    <a-tag>期限 {{ progress.limits.deadlineMinutes }} 分钟</a-tag>
                    <a-tag>输出 {{ progress.limits.outputTokens }}</a-tag>
                    <a-tag v-if="progress.limits.toolSchemaVersion">{{ progress.limits.toolSchemaVersion }}</a-tag>
                  </a-space>
                </div>
                <div class="limits">
                  <div class="section-head"><strong>已查范围</strong></div>
                  <pre class="json">{{ JSON.stringify(progress?.covered || {}, null, 2) }}</pre>
                </div>
              </a-tab-pane>
            </a-tabs>
          </a-col>
          <a-col :xs="24" :lg="8">
            <a-card size="small" title="范围与模型">
              <a-descriptions :column="1" size="small">
                <a-descriptions-item label="范围">{{ task.sourceKind }}</a-descriptions-item>
                <a-descriptions-item label="分支">{{ task.branch || '—' }}</a-descriptions-item>
                <a-descriptions-item label="固定版本">
                  <span class="mono">{{ task.headSha || '—' }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="模型">{{ task.config?.modelName || '—' }}</a-descriptions-item>
                <a-descriptions-item label="指导提示词">
                  {{ task.config?.promptId ? `v${task.config.promptVersionNo}` : '未选择' }}
                </a-descriptions-item>
                <a-descriptions-item label="创建时间">{{ formatTime(task.createdAt) }}</a-descriptions-item>
                <a-descriptions-item label="期限">{{ formatTime(task.deadlineAt) }}</a-descriptions-item>
              </a-descriptions>
            </a-card>
          </a-col>
        </a-row>
      </template>
    </a-spin>

    <MaterialReaderModal
      v-model:open="materialOpen"
      :task-id="taskId"
      :project-id="task?.projectId || ''"
      :head-sha="task?.headSha || ''"
      :material-id="materialId"
    />

    <a-drawer v-model:open="callOpen" title="工具调用详情" width="720" destroy-on-close>
      <a-spin :spinning="callLoading">
        <template v-if="callDetail">
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="轮次">{{ callDetail.turnNo }}</a-descriptions-item>
            <a-descriptions-item label="序号">{{ callDetail.ordinal }}</a-descriptions-item>
            <a-descriptions-item label="工具">{{ callDetail.toolName }}</a-descriptions-item>
            <a-descriptions-item label="状态">{{ callDetail.status }}</a-descriptions-item>
            <a-descriptions-item label="提供方 ID" :span="2">
              <span class="mono">{{ callDetail.providerToolCallId }}</span>
            </a-descriptions-item>
          </a-descriptions>
          <div class="section-head"><strong>参数</strong></div>
          <pre class="json">{{ pretty(callDetail.arguments) }}</pre>
          <div class="section-head"><strong>返回</strong></div>
          <pre class="json">{{ JSON.stringify(callDetail.envelope, null, 2) }}</pre>
          <div v-if="callDetail.materialIds.length" class="section-head"><strong>材料</strong></div>
          <a-space wrap>
            <a-button
              v-for="material in callDetail.materialIds"
              :key="material"
              size="small"
              @click="openMaterial(material)"
            >
              {{ material }}
            </a-button>
          </a-space>
        </template>
        <a-alert v-else-if="callError" type="error" show-icon :message="callError" />
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import MarkdownView from '@/components/MarkdownView.vue'
import MaterialReaderModal from '@/components/MaterialReaderModal.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import {
  agentTasksApi,
  tasksApi,
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

const calls = ref<AgentToolCallSummary[]>([])
const callPage = ref(1)
const callPageSize = 20
const callTotal = ref(0)
const callError = ref('')

const callOpen = ref(false)
const callLoading = ref(false)
const callDetail = ref<AgentToolCallDetail | null>(null)

const materialOpen = ref(false)
const materialId = ref('')

let timer: number | undefined
let progressEpoch = 0
let callEpoch = 0

const terminalStates = ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']
const stateTone = computed(() => {
  const state = task.value?.state
  if (state === 'SUCCEEDED') return 'green'
  if (state === 'PARTIAL') return 'amber'
  if (state === 'FAILED' || state === 'CANCELLED') return 'red'
  return 'blue'
})

const callColumns = [
  { title: '轮次', dataIndex: 'turnNo', width: 80 },
  { title: '序号', dataIndex: 'ordinal', width: 70 },
  { title: '工具', dataIndex: 'toolName', width: 160 },
  { title: '状态', dataIndex: 'status', width: 140 },
  { title: '时间', dataIndex: 'startedAt', width: 180 },
  { title: '操作', key: 'action', width: 80 }
]

const callRow = (record: AgentToolCallSummary) => ({
  onClick: () => openCall(record.id),
  style: { cursor: 'pointer' }
})

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
    await Promise.all([loadProgress(), loadCalls()])
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
    const page = await agentTasksApi.toolCalls(taskId.value, {
      pageNum: callPage.value,
      pageSize: callPageSize
    })
    if (epoch !== callEpoch) return
    calls.value = page.items
    callTotal.value = page.total || page.items.length
    callError.value = ''
  } catch (e) {
    if (epoch === callEpoch) {
      callError.value = `调用记录读取失败：${(e as Error).message}`
    }
  }
}

async function changeCallPage(delta: number) {
  callPage.value = Math.max(1, callPage.value + delta)
  await loadCalls()
}

async function openCall(callId: string) {
  callOpen.value = true
  callLoading.value = true
  callDetail.value = null
  try {
    callDetail.value = await agentTasksApi.toolCall(taskId.value, callId)
  } catch (e) {
    callError.value = `调用详情读取失败：${(e as Error).message}`
  } finally {
    callLoading.value = false
  }
}

function openMaterial(id: string) {
  materialId.value = id
  materialOpen.value = true
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

function downloadMarkdown() {
  if (!result.value) return
  const blob = new Blob([result.value.body.markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${result.value.body.title || 'agent-result'}.md`
  link.click()
  URL.revokeObjectURL(url)
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
.agent-detail {
  padding: 16px 20px 40px;
}
.back-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.detail-head h2 {
  margin: 0;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.wrap {
  flex-wrap: wrap;
}
.muted {
  color: rgba(0, 0, 0, 0.45);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.mb12 {
  margin-bottom: 12px;
}
.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.section-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 16px 0 8px;
}
.unresolved ul {
  margin: 6px 0 0 18px;
  padding: 0;
}
.citations {
  margin-top: 12px;
}
.table-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.limits {
  margin-top: 16px;
}
.json {
  max-height: 360px;
  overflow: auto;
  padding: 10px;
  background: #fafafa;
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
