<template>
  <div>
    <div class="back-row">
      <a-button type="link" @click="router.push('/reports')">← 返回报告</a-button>
    </div>

    <a-alert v-if="error" type="error" show-icon :message="error" class="mb16">
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>
    <a-alert
      v-if="startFailed"
      type="warning"
      show-icon
      closable
      class="mb16"
      message="材料已冻结，但生成请求没有成功受理。可在此重新选择模型生成，冻结来源不会改变。"
      @close="startFailed = false"
    />

    <a-spin :spinning="loading">
      <template v-if="report">
        <div class="page-head">
          <div>
            <div class="row wrap">
              <h1>{{ report.title }}</h1>
              <StatusBadge :tone="stateTone(report.state)">{{ stateText(report.state) }}</StatusBadge>
            </div>
            <p class="muted">
              {{ report.sourceCount }} 个审查结果 · {{ report.taskCount }} 个来源任务 ·
              {{ report.versionCount }} 个仓库版本
              <span v-if="report.model"> · {{ report.model.name }}（{{ report.model.modelName }}）</span>
            </p>
            <p class="muted small">
              冻结于 {{ formatTime(report.createdAt) }}
              <span v-if="report.finishedAt"> · 结束于 {{ formatTime(report.finishedAt) }}</span>
              <span v-if="report.model"> · 模型配置修订 {{ report.model.revision }}</span>
            </p>
          </div>
          <div class="actions">
            <a-button :loading="refreshing" @click="load">刷新</a-button>
            <a-button
              v-if="report.state === 'SUCCEEDED'"
              type="primary"
              :loading="downloading"
              @click="download"
            >
              下载 Markdown
            </a-button>
            <a-button v-if="report.state === 'INPUT_FROZEN'" type="primary" @click="openGenerate">
              选择模型生成
            </a-button>
          </div>
        </div>

        <a-alert
          v-if="report.state === 'INPUT_FROZEN'"
          type="info"
          show-icon
          class="mb16"
          message="输入材料已冻结，尚未生成正文。选择模型后开始生成。"
        />
        <a-alert
          v-if="report.state === 'QUEUED' || report.state === 'RUNNING'"
          type="info"
          show-icon
          class="mb16"
          message="报告正在后台生成。页面会自动刷新状态；失败会保留冻结来源和错误，不产生可下载正文。"
        />
        <a-alert
          v-if="report.state === 'FAILED'"
          type="error"
          show-icon
          class="mb16"
          :message="`报告生成失败${report.errorCode ? '：' + report.errorCode : ''}`"
        >
          <template #description>
            <div>{{ report.errorMessage || '没有可显示的失败原因。' }}</div>
            <div v-if="report.errorCode" class="small muted">错误码：{{ report.errorCode }}</div>
            <div class="small muted">
              冻结来源、输入摘要和实际调用保留；没有把事实草稿标为完成报告，也没有可下载的成功正文。
            </div>
          </template>
        </a-alert>

        <div class="overview">
          <div><b>{{ report.sourceCount }}</b><span>个审查结果</span></div>
          <div><b>{{ report.taskCount }}</b><span>个来源任务</span></div>
          <div><b>{{ report.versionCount }}</b><span>个仓库版本</span></div>
          <div><b>{{ kindSummary }}</b><span>类型分布</span></div>
        </div>

        <div class="report-layout">
          <section class="card report-body">
            <template v-if="report.body">
              <ReportMarkdown
                :text="report.body"
                :source-count="report.sources.length"
                @citation="openSource"
              />
              <div class="notice">
                正文与下载使用同一份保存内容。引用 [S1] 可打开对应冻结来源；后续修改反馈、配置或源码不会替换本报告。
              </div>
            </template>
            <div v-else-if="report.state === 'FAILED'" class="empty">
              <a-empty description="没有可阅读的报告正文">
                <p class="muted small">可查看下方实际调用与错误，或返回报告列表重新选材生成。</p>
                <a-button @click="router.push('/reports/new')">重新选材生成</a-button>
              </a-empty>
            </div>
            <div v-else class="empty muted">报告正文生成后显示在这里。</div>

            <section v-if="report.calls.length" class="calls">
              <div class="section-head">
                <strong>实际调用</strong>
                <span class="spacer" />
                <span class="muted small">{{ report.calls.length }} 次尝试</span>
              </div>
              <div v-for="call in report.calls" :key="call.id" class="call-row">
                <div>
                  <StatusBadge :tone="call.state === 'SUCCEEDED' ? 'green' : call.state === 'FAILED' ? 'red' : 'blue'">
                    {{ call.state }}
                  </StatusBadge>
                  <span class="muted small">第 {{ call.attempt }} 次 · {{ call.errorCode || '无错误码' }}</span>
                  <div v-if="call.errorMessage" class="error small">{{ call.errorMessage }}</div>
                </div>
                <a-button size="small" @click="openCall(call.id)">查看完整请求与返回</a-button>
              </div>
            </section>
          </section>

          <aside class="card sources-panel">
            <div class="section-head">
              <strong>报告来源</strong>
              <span class="spacer" />
              <span class="muted small">{{ report.sources.length }} 项</span>
            </div>
            <p v-if="!report.sources.length" class="muted small">没有来源摘要。</p>
            <button
              v-for="source in report.sources"
              :key="source.ordinal"
              class="source-card"
              @click="openSource(source.ordinal)"
            >
              <div class="row wrap">
                <StatusBadge tone="blue">S{{ source.ordinal }}</StatusBadge>
                <StatusBadge :tone="source.kind === 'AGENT' ? 'blue' : 'default'">{{ kindText(source.kind) }}</StatusBadge>
                <span class="spacer" />
                <span class="muted small">{{ shortSha(source.headSha) }}</span>
              </div>
              <strong class="source-title">{{ source.projectName || source.projectId }}</strong>
              <span class="muted small">{{ source.path || 'Agent Markdown' }} · {{ shortId(source.taskId) }}</span>
              <span class="muted small">{{ source.summary || '无摘要' }}</span>
              <span v-if="source.kind === 'ISSUE'" class="muted small">
                {{ source.issueCount }} 个问题 · 准确 {{ source.accurate }} · 不准确 {{ source.inaccurate }} ·
                无法判断 {{ source.unknown }} · 未反馈 {{ source.unassessed }}
              </span>
              <span v-else class="muted small">
                结果级反馈：准确 {{ source.accurate }} · 不准确 {{ source.inaccurate }} ·
                无法判断 {{ source.unknown }} · 未反馈 {{ source.unassessed }}
              </span>
            </button>
            <p class="muted small">
              正文中的 [S1] 等引用可打开对应来源；来源读取使用冻结快照，不重新查询当前配置或最新代码。
            </p>
          </aside>
        </div>
      </template>
    </a-spin>

    <a-modal
      :open="generateOpen"
      title="选择模型生成报告"
      :confirm-loading="generating"
      ok-text="开始生成"
      cancel-text="取消"
      @cancel="generateOpen = false"
      @ok="startGeneration"
    >
      <a-alert v-if="generateError" type="error" show-icon :message="generateError" class="mb12" />
      <div class="field">
        <label>生成模型</label>
        <a-select
          v-model:value="generateForm.modelConfigId"
          style="width: 100%"
          placeholder="选择模型"
          :loading="modelsLoading"
          :options="modelOptions"
        />
      </div>
      <div class="field">
        <label>报告补充要求（可选）</label>
        <a-textarea
          v-model:value="generateForm.instruction"
          :maxlength="4000"
          :auto-size="{ minRows: 2, maxRows: 4 }"
          placeholder="例如：优先说明支付重试风险，保留来源引用。"
        />
      </div>
      <p class="muted small">
        已冻结的标题、来源、版本、问题和反馈不会因本次模型选择而改变。
      </p>
    </a-modal>

    <ReportSourceDrawer
      :open="sourceOpen"
      :report-id="id"
      :ordinal="sourceOrdinal"
      @update:open="sourceOpen = $event"
    />
    <CallReaderModal :open="callOpen" :call-id="callId" @update:open="callOpen = $event" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { modelsApi, reportsApi, type ModelView, type ReportDetail } from '@/api/v2'
import ReportMarkdown from '@/components/ReportMarkdown.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ReportSourceDrawer from '@/components/ReportSourceDrawer.vue'
import CallReaderModal from '@/components/CallReaderModal.vue'

const route = useRoute()
const router = useRouter()
const id = computed(() => String(route.params.id || ''))
const report = ref<ReportDetail | null>(null)
const loading = ref(false)
const refreshing = ref(false)
const error = ref('')
const downloading = ref(false)
const startFailed = ref(!!route.query.startFailed)
const sourceOpen = ref(false)
const sourceOrdinal = ref(0)
const callOpen = ref(false)
const callId = ref('')
const generateOpen = ref(false)
const generating = ref(false)
const generateError = ref('')
const models = ref<ModelView[]>([])
const modelsLoading = ref(false)
const generateForm = reactive({ modelConfigId: undefined as string | undefined, instruction: '' })
let loadEpoch = 0
let pollTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false
let reportEpoch = 0

const modelOptions = computed(() =>
  models.value.map((model) => ({
    value: model.id,
    disabled: !model.contextWindow,
    label: `${model.name} · ${model.modelName}${model.contextWindow ? '' : '（未声明窗口，不可用于报告）'}${model.validationStatus === 'PASSED' ? '' : '（未验证）'}`
  }))
)
const kindSummary = computed(() => {
  const counts = report.value?.kindCounts || {}
  const entries = Object.entries(counts)
  if (!entries.length) return '—'
  return entries.map(([key, count]) => `${kindText(key)} ${count}`).join(' / ')
})

watch(
  () => id.value,
  () => {
    reportEpoch++
    report.value = null
    sourceOpen.value = false
    sourceOrdinal.value = 0
    callOpen.value = false
    callId.value = ''
    generateOpen.value = false
    generating.value = false
    downloading.value = false
    generateError.value = ''
    generateForm.modelConfigId = undefined
    generateForm.instruction = ''
    startFailed.value = !!route.query.startFailed
    void load()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  disposed = true
  loadEpoch++
  if (pollTimer) clearTimeout(pollTimer)
})

async function load() {
  if (!id.value) return
  const epoch = ++loadEpoch
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  if (!report.value) loading.value = true
  else refreshing.value = true
  error.value = ''
  try {
    const loaded = await reportsApi.detail(id.value)
    if (disposed || epoch !== loadEpoch) return
    report.value = loaded
    if (loaded.state === 'QUEUED' || loaded.state === 'RUNNING') {
      pollTimer = setTimeout(() => void load(), 2000)
    }
  } catch (failure) {
    if (disposed || epoch !== loadEpoch) return
    error.value = (failure as Error).message || '报告读取失败'
  } finally {
    if (disposed || epoch !== loadEpoch) return
    loading.value = false
    refreshing.value = false
  }
}

async function download() {
  if (!report.value || downloading.value) return
  const current = report.value
  const epoch = reportEpoch
  downloading.value = true
  error.value = ''
  try {
    const blob = await reportsApi.download(current.id)
    if (disposed || epoch !== reportEpoch) return
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${current.title || '审查报告'}.md`
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (failure) {
    if (disposed || epoch !== reportEpoch) return
    error.value = (failure as Error).message || '报告下载失败'
  } finally {
    if (!disposed && epoch === reportEpoch) downloading.value = false
  }
}

async function openGenerate() {
  const epoch = reportEpoch
  generateOpen.value = true
  generateError.value = ''
  if (!models.value.length) {
    modelsLoading.value = true
    try {
      const page = await modelsApi.list({ limit: 100 })
      if (disposed || epoch !== reportEpoch) return
      models.value = page.items
    } catch (failure) {
      if (disposed || epoch !== reportEpoch) return
      generateError.value = (failure as Error).message || '模型列表读取失败'
    } finally {
      modelsLoading.value = false
    }
  }
  if (disposed || epoch !== reportEpoch) return
  if (!generateForm.modelConfigId) {
    generateForm.modelConfigId = models.value.find((model) =>
      model.contextWindow && model.contextWindow > 0)?.id || models.value[0]?.id
  }
}

async function startGeneration() {
  if (!report.value || report.value.id !== id.value || generating.value) return
  const epoch = reportEpoch
  if (!generateForm.modelConfigId) {
    generateError.value = '请选择生成模型。'
    return
  }
  generating.value = true
  generateError.value = ''
  try {
    await reportsApi.start(report.value.id, {
      modelConfigId: generateForm.modelConfigId,
      instruction: generateForm.instruction.trim() || undefined
    })
    if (disposed || epoch !== reportEpoch) return
    generateOpen.value = false
    await load()
  } catch (failure) {
    if (disposed || epoch !== reportEpoch) return
    generateError.value = (failure as Error).message || '生成请求失败'
  } finally {
    if (!disposed && epoch === reportEpoch) generating.value = false
  }
}

function openSource(ordinal: number) {
  sourceOrdinal.value = ordinal
  sourceOpen.value = true
}

function openCall(call: string) {
  callId.value = call
  callOpen.value = true
}

function stateText(state: string) {
  return ({
    INPUT_FROZEN: '待生成',
    QUEUED: '排队中',
    RUNNING: '生成中',
    SUCCEEDED: '已完成',
    FAILED: '生成失败'
  } as Record<string, string>)[state] || state
}

function stateTone(state: string): 'green' | 'amber' | 'blue' | 'red' | 'default' {
  if (state === 'SUCCEEDED') return 'green'
  if (state === 'FAILED') return 'red'
  if (state === 'QUEUED' || state === 'RUNNING' || state === 'INPUT_FROZEN') return 'blue'
  return 'default'
}

function kindText(kind: string) {
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度', AGENT: 'Agent' } as Record<string, string>)[kind] || kind
}

function shortSha(sha: string | null) {
  return sha ? sha.slice(0, 12) : 'UNKNOWN'
}

function shortId(value: string) {
  return value.length <= 10 ? value : value.slice(-10)
}

function formatTime(value: string) {
  if (!value) return '—'
  const normalized = /[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<style scoped lang="less">
.back-row {
  margin-bottom: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 32px;
}
.page-head h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.4;
}
.page-head p {
  margin: 8px 0 0;
  font-size: 15px;
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
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.muted {
  color: var(--muted);
}
.small {
  font-size: 12px;
}
.error {
  color: var(--red);
}
.overview {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 22px;
  padding: 22px 28px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.overview > div {
  display: flex;
  gap: 10px;
  align-items: baseline;
}
.overview b {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.8px;
  color: var(--ink);
}
.overview span {
  color: var(--muted);
  font-size: 12px;
}
.report-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 20px;
  align-items: start;
}
.card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  overflow: hidden;
}
.report-body {
  padding: 30px 38px;
  max-width: none;
  min-height: 320px;
  line-height: 1.95;
}
.sources-panel {
  position: sticky;
  top: 16px;
  max-height: calc(100vh - 32px);
  overflow: auto;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.sources-panel .section-head {
  padding: 18px 24px;
  border-bottom: 1px solid var(--line);
}
.calls .section-head {
  margin-bottom: 10px;
}
.spacer {
  flex: 1;
}
.source-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 15px 18px;
  text-align: left;
  background: #fff;
  border: 0;
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  box-shadow: none;
  cursor: pointer;
  font-weight: 500;
}
.source-card:hover {
  background: var(--bg);
}
.source-title {
  margin-top: 2px;
}
.notice {
  margin-top: 18px;
  padding: 13px 16px;
  background: var(--notice);
  border: 1px solid var(--notice-border);
  border-radius: 14px;
  color: var(--notice-ink);
  font-size: 12px;
  line-height: 1.7;
}
.sources-panel > .muted,
.sources-panel > p.muted {
  padding: 15px 18px;
}
.calls {
  margin-top: 24px;
  border-top: 1px solid #eef2f7;
  padding-top: 16px;
}
.call-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f2f5f9;
}
.empty {
  padding: 36px 0;
  text-align: center;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}
.field label {
  font-weight: 500;
  color: var(--ink);
}
@media (max-width: 1100px) {
  .report-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .sources-panel {
    position: static;
    max-height: none;
  }
  .overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
