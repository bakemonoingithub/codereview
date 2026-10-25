<template>
  <LaunchModalShell :open="true" title="生成报告" @cancel="closeCreate">
    <template #alerts>
      <a-alert v-if="formError" type="error" show-icon :message="formError" />
      <a-alert
        v-if="!modelsLoading && !hasUsableModel"
        type="warning"
        show-icon
        message="还没有可用于报告的模型。报告生成需要模型声明上下文窗口。"
      >
        <template #action><a-button size="small" @click="router.push('/models')">去配置模型</a-button></template>
      </a-alert>
    </template>

    <template #body>
      <LaunchFormSections :titles="{ goal: '报告信息', scope: '审查材料', extra: '补充要求' }">
        <template #goal>
          <div class="form-grid">
            <a-form-item label="报告名称" required>
              <a-input v-model:value="form.title" :maxlength="200" placeholder="例如：订单服务专项审查报告" />
            </a-form-item>
            <a-form-item label="模型" required>
              <a-select
                v-model:value="form.modelConfigId"
                style="width: 100%"
                placeholder="选择模型"
                :loading="modelsLoading"
                :options="modelOptions"
              />
            </a-form-item>
          </div>
        </template>

        <template #scope>
          <div class="section-head">
            <a-button type="primary" :disabled="submitting" @click="pickerOpen = true">选择审查结果</a-button>
            <span class="muted small">已选 {{ selected.size }} 个结果</span>
          </div>
          <p v-if="!selected.size" class="muted small">尚未选择材料。</p>
          <div v-else class="selected-list">
            <div v-for="item in selectedList" :key="item.resultId" class="selected-item">
              <div>
                <strong :title="item.taskId">{{ taskLabel(item.taskName, item.taskId) }}</strong>
                <span class="file-name" :title="pathTitle(item)">{{ fileName(item.path) }}</span>
                <span class="muted small">{{ item.projectName || item.projectId }} · {{ kindText(item.kind) }} · {{ shortSha(item.headSha) }}</span>
                <span v-if="item.kind === 'AGENT'" class="small" :class="{ 'evidence-warning': item.answerStatus !== 'ANSWERED' }">
                  {{ item.answerStatus === 'ANSWERED' ? '已回答' : '材料不足' }}<template v-if="item.unresolvedCount"> · 未解决 {{ item.unresolvedCount }} 项</template>
                </span>
              </div>
              <a-space>
                <a-button size="small" type="link" :disabled="item.kind !== 'AGENT' && !item.targetId" @click="openResult(item)">查看结果</a-button>
                <a-button size="small" type="text" danger :disabled="submitting" @click="remove(item.resultId)">移除</a-button>
              </a-space>
            </div>
          </div>
        </template>

        <template #extra>
          <div class="form-grid">
            <a-form-item>
              <template #label>通用规则 <span class="muted small">（可选）</span></template>
              <a-select
                v-model:value="form.promptId"
                allow-clear
                style="width: 100%"
                placeholder="不使用通用规则"
                :loading="promptOptionsLoading"
                :options="promptOptions"
              />
            </a-form-item>
            <a-form-item v-if="form.promptId" label="规则版本" required>
              <PromptVersionSelect
                v-model:value="form.promptVersionId"
                :versions="promptVersions"
                :current-version-id="selectedPrompt?.currentVersion?.id"
                :loading="promptLoading"
              />
            </a-form-item>
            <div v-if="form.promptId" class="full">
              <PromptInfoPreview
                title="所选版本正文"
                :prompt="selectedPrompt"
                :version="selectedPromptVersion"
                selected
                :loading="promptLoading"
                :error="promptError"
                @retry="loadPromptSelection(form.promptId, form.promptVersionId)"
              />
            </div>
            <a-form-item class="full">
              <template #label>补充要求 <span class="muted small">（可选）</span></template>
              <a-textarea
                v-model:value="form.instruction"
                :maxlength="4000"
                show-count
                :auto-size="{ minRows: 4, maxRows: 8 }"
                placeholder="例如：优先说明支付重试风险，保留来源引用。"
              />
            </a-form-item>
          </div>
        </template>
      </LaunchFormSections>
    </template>

    <template #footer>
      <a-button @click="closeCreate">取消</a-button>
      <a-button type="primary" :loading="submitting" :disabled="!hasUsableModel || !selected.size" @click="submit">冻结材料并生成</a-button>
    </template>

    <template #overlay>
      <ReportResultPickerModal v-model:open="pickerOpen" :projects="projects" :selected="selectedList"
        @confirm="applySelection" @preview="openResult" />

      <a-modal
        v-model:open="resultOpen"
        :title="resultTitle"
        width="min(960px, calc(100vw - 48px))"
        :footer="null"
        destroy-on-close
      >
        <a-spin :spinning="resultLoading">
          <a-alert v-if="resultError" type="error" show-icon :message="resultError">
            <template #action><a-button size="small" @click="previewRecord && openResult(previewRecord)">重试</a-button></template>
          </a-alert>
          <AgentResultPanel v-else-if="agentResult" :body="agentResult.body" :show-download="false" @open-code="openCodeReference" />
          <SpecialistResultView
            v-else-if="resultView && resultTaskId"
            :result="resultView"
            :task-id="resultTaskId"
          />
        </a-spin>
      </a-modal>
      <CodeReferenceModal v-if="codePayload" v-model:open="codeOpen" :task-id="resultTaskId" :project-id="resultProjectId"
        :scope="codePayload.scope" :path="codePayload.path" :sha="codePayload.sha || ''" :side="codePayload.side"
        :references="codePayload.references" :active-start="codePayload.startLine" :active-end="codePayload.endLine"
        :initial-mode="codePayload.sha ? 'full' : 'saved'" />
    </template>
  </LaunchModalShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import SpecialistResultView from '@/components/SpecialistResultView.vue'
import AgentResultPanel from '@/components/AgentResultPanel.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import ReportResultPickerModal from '@/components/ReportResultPickerModal.vue'
import PromptVersionSelect from '@/components/PromptVersionSelect.vue'
import PromptInfoPreview from '@/components/PromptInfoPreview.vue'
import { kindText, pathTitle, shortSha } from '@/utils/reportCandidates'
import type { CodeReferencePayload } from '@/utils/codeReferences'
import { readPromptSelection, unavailablePromptVersion } from '@/utils/promptSelection'
import {
  agentTasksApi,
  modelsApi,
  promptsApi,
  projectsApi,
  reportsApi,
  tasksApi,
  type AgentResultView,
  type ModelView,
  type PromptSummary,
  type PromptVersion,
  type PromptView,
  type ProjectView,
  type ReportCandidate,
  type ResultView
} from '@/api/v2'
import { fileName, taskLabel } from '@/utils/taskLabel'

const router = useRouter()
const pickerOpen = ref(false)
const agentResult = ref<AgentResultView | null>(null)
const previewRecord = ref<ReportCandidate | null>(null)
const resultProjectId = ref('')
const codePayload = ref<CodeReferencePayload | null>(null)
const codeOpen = ref(false)
let resultEpoch = 0
const resultOpen = ref(false)
const resultLoading = ref(false)
const resultError = ref('')
const resultView = ref<ResultView | null>(null)
const resultTaskId = ref('')
const resultTitle = ref('审查结果')
const form = reactive({
  title: '专项审查报告', modelConfigId: undefined as string | undefined, instruction: '',
  promptId: '', promptVersionId: ''
})
const formError = ref('')
const submitting = ref(false)
const projects = ref<ProjectView[]>([])
const models = ref<ModelView[]>([])
const modelsLoading = ref(false)
const prompts = ref<PromptSummary[]>([])
const promptOptionsLoading = ref(false)
const promptLoading = ref(false)
const promptError = ref('')
const selectedPrompt = ref<PromptView | null>(null)
const promptVersions = ref<PromptVersion[]>([])
const selected = reactive(new Map<string, ReportCandidate>())
let disposed = false
let promptEpoch = 0
onBeforeUnmount(() => { disposed = true; resultEpoch++; promptEpoch++ })
watch(resultOpen, open => {
  if (!open) { resultEpoch++; resultLoading.value = false; codeOpen.value = false }
}, { flush: 'sync' })

const modelOptions = computed(() =>
  models.value.map((model) => ({
    value: model.id,
    disabled: !model.contextWindow,
    label: `${model.name} · ${model.modelName}${model.contextWindow ? '' : '（未声明窗口，不可用于报告）'}${model.validationStatus === 'PASSED' ? '' : '（未验证）'}`
  }))
)
const hasUsableModel = computed(() =>
  models.value.some((model) => !!model.contextWindow && model.contextWindow > 0)
)
const promptOptions = computed(() => prompts.value
  .filter(prompt => prompt.purpose === 'REPORT')
  .map(prompt => ({ value: prompt.id, label: prompt.name }))
)
const selectedPromptVersion = computed(() =>
  promptVersions.value.find(version => version.id === form.promptVersionId) || null
)
const selectedList = computed(() => Array.from(selected.values()))

watch(() => form.promptId, promptId => { void loadPromptSelection(promptId) }, { flush: 'sync' })
watch(() => form.promptVersionId, versionId => {
  if (form.promptId && versionId && !selectedPromptVersion.value && !promptLoading.value) {
    promptError.value = unavailablePromptVersion
  } else if (selectedPromptVersion.value) {
    promptError.value = ''
  }
}, { flush: 'sync' })

onMounted(async () => {
  await loadOptions()
  if (!form.modelConfigId) {
    form.modelConfigId = defaultModelId()
  }
})

async function loadOptions() {
  modelsLoading.value = true
  promptOptionsLoading.value = true
  try {
    const [projectPage, modelPage, reportPrompts] = await Promise.all([
      projectsApi.list({ limit: 100 }),
      modelsApi.list({ limit: 100 }),
      loadReportPrompts()
    ])
    projects.value = projectPage.items
    models.value = modelPage.items
    prompts.value = reportPrompts
  } catch (failure) {
    formError.value = (failure as Error).message || '项目或模型读取失败'
  } finally {
    modelsLoading.value = false
    promptOptionsLoading.value = false
  }
}

async function loadReportPrompts(): Promise<PromptSummary[]> {
  const items = new Map<string, PromptSummary>()
  const seen = new Set<string>()
  let cursor: string | undefined
  do {
    const page = await promptsApi.list({ limit: 100, ...(cursor ? { cursor } : {}) })
    for (const prompt of page.items) {
      if (prompt.purpose === 'REPORT') items.set(prompt.id, prompt)
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
  form.promptVersionId = preferredVersionId || ''
  if (!promptId) {
    promptLoading.value = false
    return
  }
  promptLoading.value = true
  try {
    const selection = await readPromptSelection(promptId, preferredVersionId, true)
    if (disposed || epoch !== promptEpoch) return
    if (selection.prompt.purpose !== 'REPORT') throw new Error('所选提示词不属于报告生成用途，请重新选择。')
    selectedPrompt.value = selection.prompt
    promptVersions.value = selection.versions
    if (selection.version) {
      form.promptVersionId = selection.version.id
    } else if (preferredVersionId) {
      form.promptVersionId = preferredVersionId
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

function applySelection(items: ReportCandidate[]) {
  if (submitting.value) return
  selected.clear()
  items.forEach(item => selected.set(item.resultId, item))
}

function remove(resultId: string) {
  selected.delete(resultId)
}

function closeCreate() {
  router.push('/reports')
}

function openCodeReference(value: CodeReferencePayload) {
  codePayload.value = value
  codeOpen.value = true
}

async function openResult(record: ReportCandidate) {
  if (record.kind !== 'AGENT' && !record.targetId) {
    message.warning('这条结果没有可查看的文件目标')
    return
  }
  const epoch = ++resultEpoch
  previewRecord.value = record
  resultTitle.value = `${taskLabel(record.taskName, record.taskId)} · ${record.kind === 'AGENT' ? 'Agent 调查结果' : fileName(record.path)}`
  resultTaskId.value = record.taskId
  resultProjectId.value = record.projectId
  resultView.value = null
  agentResult.value = null
  codePayload.value = null
  codeOpen.value = false
  resultError.value = ''
  resultOpen.value = true
  resultLoading.value = true
  try {
    if (record.kind === 'AGENT') {
      const result = await agentTasksApi.result(record.taskId)
      if (!disposed && epoch === resultEpoch) agentResult.value = result
    } else {
      const result = await tasksApi.result(record.taskId, record.targetId!)
      if (!disposed && epoch === resultEpoch) resultView.value = result
    }
  } catch (failure) {
    if (!disposed && epoch === resultEpoch) resultError.value = (failure as Error).message || '审查结果读取失败'
  } finally {
    if (epoch === resultEpoch) resultLoading.value = false
  }
}

async function submit() {
  if (submitting.value || disposed) return
  formError.value = ''
  if (!form.title.trim()) {
    formError.value = '请填写报告名称。'
    return
  }
  if (!selected.size) {
    formError.value = '请至少选择一个完整审查结果。'
    return
  }
  if (!form.modelConfigId) {
    formError.value = '请选择模型。'
    return
  }
  if (form.promptId && (promptLoading.value || promptError.value || !form.promptVersionId || !selectedPromptVersion.value)) {
    formError.value = '请确认报告规则及其有效版本。'
    return
  }
  submitting.value = true
  const generation = {
    modelConfigId: form.modelConfigId,
    instruction: form.instruction.trim() || undefined,
    ...(form.promptId ? { promptId: form.promptId, promptVersionId: form.promptVersionId } : {})
  }
  let reportId = ''
  try {
    const frozen = await reportsApi.freezeInputs({
      title: form.title.trim(),
      resultIds: selectedList.value.map((item) => item.resultId)
    })
    reportId = frozen.reportId
    await reportsApi.start(reportId, generation)
    if (!disposed) router.push(`/reports/${reportId}`)
  } catch (failure) {
    if (disposed) return
    if (reportId) {
      router.push({ path: `/reports/${reportId}`, query: { startFailed: '1' } })
    } else {
      formError.value = (failure as Error).message || '报告提交失败，已保留当前选材。'
    }
  } finally {
    submitting.value = false
  }
}

function defaultModelId() {
  return models.value.find((model) => model.contextWindow && model.contextWindow > 0)?.id
    || models.value[0]?.id
}

</script>

<style scoped lang="less">
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.form-grid :deep(.ant-form-item) { margin-bottom: 0; }
.full { grid-column: 1 / -1; }
.section-head { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.selected-list { display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow: auto; }
.selected-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; }
.selected-item > div { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.selected-item strong, .file-name { overflow-wrap: anywhere; }
.evidence-warning { color: #ad6800; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
@media (max-width: 650px) {
  .form-grid { grid-template-columns: 1fr; }
  .selected-item { align-items: flex-start; flex-direction: column; }
}
</style>
