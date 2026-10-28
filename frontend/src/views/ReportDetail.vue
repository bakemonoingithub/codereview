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
            <TaskDetailMetadata :task-id="report.id" :show-id="false">
              {{ projectSummary }} · 审查报告
            </TaskDetailMetadata>
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
              选择生成配置
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

        <div class="report-layout">
          <section class="card report-content">
            <div class="tabbar" aria-label="报告详情内容">
              <button type="button" :class="{ active: activeTab === 'result' }" @click="activeTab = 'result'">报告正文</button>
              <button type="button" :class="{ active: activeTab === 'input' }" @click="activeTab = 'input'">输入与配置</button>
              <button type="button" :class="{ active: activeTab === 'calls' }" @click="activeTab = 'calls'">实际调用</button>
            </div>

            <div v-show="activeTab === 'result'" class="report-body">
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
                  <p class="muted small">可在「实际调用」中查看请求、返回与错误，或重新选材生成。</p>
                  <a-button @click="activeTab = 'calls'">查看实际调用</a-button>
                  <a-button @click="router.push('/reports/new')">重新选材生成</a-button>
                </a-empty>
              </div>
              <div v-else class="empty muted">报告正文生成后显示在这里。</div>
            </div>

            <div v-show="activeTab === 'input'" class="report-input">
              <section class="input-section" aria-label="任务配置">
                <h3>任务配置</h3>
                <dl class="input-pairs">
                  <dt>涉及项目</dt>
                  <dd>{{ projectSummary }}</dd>
                  <dt>任务类型</dt>
                  <dd>审查报告</dd>
                  <dt>使用的模型</dt>
                  <dd>{{ report.model?.name || (report.state === 'INPUT_FROZEN' ? '尚未选择' : '模型名称未记录') }}</dd>
                </dl>
              </section>

              <section class="input-section" aria-label="报告规则">
                <h3>报告规则</h3>
                <dl class="input-pairs">
                  <dt>本次要求</dt>
                  <dd class="input-text">{{ report.instruction?.trim() ? report.instruction : (report.state === 'INPUT_FROZEN' ? '尚未设置' : '未填写') }}</dd>
                  <dt>通用规则</dt>
                  <dd>{{ report.prompt ? `${report.prompt.name} · v${report.prompt.versionNo}` : (report.state === 'INPUT_FROZEN' ? '尚未选择' : '未使用通用规则') }}</dd>
                  <template v-if="report.prompt">
                    <dt>规则正文</dt>
                    <dd>
                      <details>
                        <summary>展开查看</summary>
                        <pre class="input-rule-content">{{ report.prompt.content?.trim() ? report.prompt.content : '保存的规则正文不可用。' }}</pre>
                      </details>
                    </dd>
                  </template>
                </dl>
              </section>

              <section class="input-section" aria-label="报告材料">
                <h3>报告材料</h3>
                <dl class="input-pairs">
                  <dt>来源范围</dt>
                  <dd>{{ report.sourceCount }} 个审查结果 · {{ report.taskCount }} 个来源任务 · {{ report.versionCount }} 个源码版本</dd>
                  <dt>来源类型</dt>
                  <dd class="kind-tags">
                    <a-tag v-for="item in kindDistribution" :key="item.kind">{{ item.label }} {{ item.count }}</a-tag>
                    <span v-if="!kindDistribution.length" class="muted">来源类型未记录</span>
                  </dd>
                </dl>
                <p v-if="report.sources.length" class="muted small input-material-intro">可在「报告来源」中打开生成时保存的结果、版本、反馈和代码材料。</p>
              </section>

              <section class="input-section" aria-label="任务信息">
                <h3>任务信息</h3>
                <dl class="input-pairs">
                  <dt>报告 ID</dt>
                  <dd><code>{{ report.id }}</code></dd>
                  <dt>创建与结束</dt>
                  <dd>
                    创建：{{ report.createdAt ? formatTime(report.createdAt) : '创建时间未记录' }}<br>
                    结束：{{ report.finishedAt ? formatTime(report.finishedAt) : (report.state === 'INPUT_FROZEN' ? '尚未开始生成' : (['QUEUED', 'RUNNING'].includes(report.state) ? '尚未结束' : '结束时间未记录')) }}
                  </dd>
                  <dt>模型配置修订</dt>
                  <dd>{{ report.model?.revision ?? (report.state === 'INPUT_FROZEN' ? '尚未选择模型' : '未记录') }}</dd>
                </dl>
              </section>
            </div>

            <section v-show="activeTab === 'calls'" class="calls">
              <ActualCallList :calls="report.calls" :format-time="formatTime" @open="openCall" />
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
              type="button"
              class="source-card"
              :aria-label="`查看来源 S${source.ordinal}`"
              @click="openSource(source.ordinal)"
            >
              <div class="row wrap">
                <StatusBadge tone="blue">S{{ source.ordinal }} · {{ kindText(source.kind) }}</StatusBadge>
              </div>
              <strong class="source-title">{{ source.projectName || '项目名称不可用' }}</strong>
              <span v-if="source.kind === 'AGENT'" class="muted small">{{ source.summary || 'Agent 分析结果' }}</span>
              <code v-else class="source-path">{{ source.path || '文件路径未记录' }}</code>
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
      title="生成配置"
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
        <label>报告通用规则 <span class="muted small">（可选）</span></label>
        <a-select
          v-model:value="generateForm.promptId"
          allow-clear
          style="width: 100%"
          placeholder="选择报告规则"
          :loading="promptOptionsLoading"
          :options="promptOptions"
        />
        <span class="muted small">只显示报告生成用途的规则。</span>
      </div>
      <div v-if="generateForm.promptId" class="field">
        <label>规则版本</label>
        <PromptVersionSelect
          v-model:value="generateForm.promptVersionId"
          :versions="promptVersions"
          :current-version-id="selectedPrompt?.currentVersion?.id"
          :loading="promptLoading"
        />
      </div>
      <PromptInfoPreview
        v-if="generateForm.promptId"
        class="mb12"
        title="所选报告规则正文"
        :prompt="selectedPrompt"
        :version="selectedPromptVersion"
        selected
        :loading="promptLoading"
        :error="promptError"
        @retry="loadPromptSelection(generateForm.promptId, generateForm.promptVersionId)"
      />
      <div class="field">
        <label>补充要求 <span class="muted small">（可选）</span></label>
        <a-textarea
          v-model:value="generateForm.instruction"
          :maxlength="4000"
          :auto-size="{ minRows: 2, maxRows: 4 }"
          placeholder="例如：优先说明支付重试风险，保留来源引用。"
        />
      </div>
      <p class="muted small">
        来源、版本、问题和反馈保持冻结；本次模型、要求和规则版本在生成受理时一并固定。
      </p>
    </a-modal>

    <ReportSourceModal
      :open="sourceOpen"
      :report-id="id"
      :ordinal="sourceOrdinal"
      @update:open="sourceOpen = $event"
    />
    <CallReaderModal :open="callOpen" :call-id="callId" @update:open="callOpen = $event" />
  </div>
</template>

<script setup lang="ts">
import { runtimeConfig } from '@/runtime/config'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { modelsApi, promptsApi, reportsApi, type ModelView, type PromptSummary, type PromptVersion, type PromptView, type ReportDetail } from '@/api/v2'
import TaskDetailMetadata from '@/components/TaskDetailMetadata.vue'
import ReportMarkdown from '@/components/ReportMarkdown.vue'
import PromptInfoPreview from '@/components/PromptInfoPreview.vue'
import PromptVersionSelect from '@/components/PromptVersionSelect.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ReportSourceModal from '@/components/ReportSourceModal.vue'
import CallReaderModal from '@/components/CallReaderModal.vue'
import ActualCallList from '@/components/ActualCallList.vue'
import { isPromptApplicable, readPromptSelection, unavailablePromptVersion } from '@/utils/promptSelection'

const route = useRoute()
const router = useRouter()
const id = computed(() => String(route.params.id || ''))
const report = ref<ReportDetail | null>(null)
const activeTab = ref<'result' | 'input' | 'calls'>('result')
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
const generateForm = reactive({ modelConfigId: undefined as string | undefined, instruction: '', promptId: '', promptVersionId: '' })
const prompts = ref<PromptSummary[]>([])
const promptsLoaded = ref(false)
const promptOptionsLoading = ref(false)
const promptLoading = ref(false)
const promptError = ref('')
const selectedPrompt = ref<PromptView | null>(null)
const promptVersions = ref<PromptVersion[]>([])
let loadEpoch = 0
let pollTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false
let reportEpoch = 0
let promptEpoch = 0

const modelOptions = computed(() =>
  models.value.map((model) => ({
    value: model.id,
    disabled: !model.contextWindow,
    label: `${model.name} · ${model.modelName}${model.contextWindow ? '' : '（未声明窗口，不可用于报告）'}${model.validationStatus === 'PASSED' ? '' : '（未验证）'}`
  }))
)
const promptOptions = computed(() => prompts.value
  .filter(prompt => isPromptApplicable(prompt.purpose, 'REPORT'))
  .map(prompt => ({ value: prompt.id, label: prompt.name }))
)
const selectedPromptVersion = computed(() =>
  promptVersions.value.find(version => version.id === generateForm.promptVersionId) || null
)
const projectSummary = computed(() => {
  const projects = new Map<string, string>()
  for (const source of report.value?.sources || []) {
    projects.set(source.projectId, source.projectName || '项目名称不可用')
  }
  return [...projects.values()].join('、') || '项目未记录'
})
const kindDistribution = computed(() =>
  Object.entries(report.value?.kindCounts || {}).map(([kind, count]) => ({ kind, count, label: kindText(kind) }))
)

watch(
  () => id.value,
  () => {
    reportEpoch++
    report.value = null
    activeTab.value = 'result'
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
    generateForm.promptId = ''
    generateForm.promptVersionId = ''
    promptEpoch++
    startFailed.value = !!route.query.startFailed
    void load()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  disposed = true
  loadEpoch++
  promptEpoch++
  if (pollTimer) clearTimeout(pollTimer)
})

watch(() => generateForm.promptId, promptId => { void loadPromptSelection(promptId) }, { flush: 'sync' })
watch(() => generateForm.promptVersionId, versionId => {
  if (generateForm.promptId && versionId && !selectedPromptVersion.value && !promptLoading.value) {
    promptError.value = unavailablePromptVersion
  } else if (selectedPromptVersion.value) {
    promptError.value = ''
  }
}, { flush: 'sync' })

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
      pollTimer = setTimeout(() => void load(), runtimeConfig().reportPollMillis)
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
  if (!models.value.length || !promptsLoaded.value) {
    modelsLoading.value = true
    promptOptionsLoading.value = true
    try {
      const [modelPage, reportPrompts] = await Promise.all([
        models.value.length ? Promise.resolve({ items: models.value }) : modelsApi.list({ limit: 100 }),
        promptsLoaded.value ? Promise.resolve(prompts.value) : loadReportPrompts()
      ])
      if (disposed || epoch !== reportEpoch) return
      models.value = modelPage.items
      prompts.value = reportPrompts
      promptsLoaded.value = true
    } catch (failure) {
      if (disposed || epoch !== reportEpoch) return
      generateError.value = (failure as Error).message || '生成配置读取失败'
    } finally {
      modelsLoading.value = false
      promptOptionsLoading.value = false
    }
  }
  if (disposed || epoch !== reportEpoch) return
  if (!generateForm.modelConfigId) {
    generateForm.modelConfigId = models.value.find((model) =>
      model.contextWindow && model.contextWindow > 0)?.id || models.value[0]?.id
  }
}

async function loadReportPrompts(): Promise<PromptSummary[]> {
  const items = new Map<string, PromptSummary>()
  const seen = new Set<string>()
  let cursor: string | undefined
  do {
    const page = await promptsApi.list({ limit: 100, ...(cursor ? { cursor } : {}) })
    for (const prompt of page.items) {
      if (isPromptApplicable(prompt.purpose, 'REPORT')) items.set(prompt.id, prompt)
    }
    if (!page.hasMore) return [...items.values()]
    if (!page.nextCursor || seen.has(page.nextCursor)) throw new Error('报告规则分页异常，请重新加载。')
    cursor = page.nextCursor
    seen.add(cursor)
  } while (true)
}

async function loadPromptSelection(promptId: string, preferredVersionId?: string | null) {
  const epoch = ++promptEpoch
  promptError.value = ''
  selectedPrompt.value = null
  promptVersions.value = []
  generateForm.promptVersionId = preferredVersionId || ''
  if (!promptId) {
    promptLoading.value = false
    return
  }
  promptLoading.value = true
  try {
    const selection = await readPromptSelection(promptId, preferredVersionId, true)
    if (disposed || epoch !== promptEpoch) return
    if (!isPromptApplicable(selection.prompt.purpose, 'REPORT')) throw new Error('所选提示词须为报告生成或通用用途，请重新选择。')
    selectedPrompt.value = selection.prompt
    promptVersions.value = selection.versions
    if (selection.version) {
      generateForm.promptVersionId = selection.version.id
    } else if (preferredVersionId) {
      generateForm.promptVersionId = preferredVersionId
      promptError.value = unavailablePromptVersion
    } else {
      promptError.value = '该报告规则没有可用的正文版本，请先到提示词管理补充版本。'
    }
  } catch (failure) {
    if (!disposed && epoch === promptEpoch) promptError.value = (failure as Error).message || '报告规则读取失败。'
  } finally {
    if (!disposed && epoch === promptEpoch) promptLoading.value = false
  }
}

async function startGeneration() {
  if (!report.value || report.value.id !== id.value || generating.value) return
  const epoch = reportEpoch
  if (!generateForm.modelConfigId) {
    generateError.value = '请选择生成模型。'
    return
  }
  if (generateForm.promptId && (promptLoading.value || promptError.value || !generateForm.promptVersionId || !selectedPromptVersion.value)) {
    generateError.value = '请确认报告规则及其有效版本。'
    return
  }
  generating.value = true
  generateError.value = ''
  try {
    await reportsApi.start(report.value.id, {
      modelConfigId: generateForm.modelConfigId,
      instruction: generateForm.instruction.trim() || undefined,
      ...(generateForm.promptId ? { promptId: generateForm.promptId, promptVersionId: generateForm.promptVersionId } : {})
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
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度', AGENT: 'Agent 分析' } as Record<string, string>)[kind] || kind
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
.kind-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.kind-tags :deep(.ant-tag) {
  margin: 0;
  font-size: 13px;
  white-space: normal;
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
.report-content { min-width: 0; }
.tabbar {
  display: flex;
  gap: 28px;
  padding: 0 24px;
  border-bottom: 1px solid var(--line);
  overflow-x: auto;
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
.tabbar button.active {
  color: var(--blue);
  border-bottom: 2px solid var(--blue);
  font-weight: 600;
}
.report-input {
  padding: 20px 24px 24px;
  .input-section {
    padding: 20px 0;
    border-top: 1px solid var(--line);
    h3 { margin: 0 0 15px; font-size: 16px; }
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
    dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
  }
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
  .input-material-intro { margin: 16px 0 14px; line-height: 1.8; }
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
  overflow-wrap: anywhere;
}
.source-card:hover {
  background: var(--bg);
}
.source-title {
  margin-top: 2px;
}
.source-path { color: var(--muted); font-size: 12px; }
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
  min-height: 320px;
  padding: 20px 24px 24px;
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
}
@media (max-width: 760px) {
  .page-head { flex-direction: column; align-items: flex-start; }
  .report-input .input-pairs { grid-template-columns: 96px minmax(0, 1fr); gap: 12px; }
}
</style>
