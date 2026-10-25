<template>
  <a-modal
    :open="true"
    title="生成报告"
    :footer="null"
    width="min(920px, calc(100vw - 48px))"
    :mask-closable="false"
    wrap-class-name="report-create-modal"
    @cancel="closeCreate"
  >
    <p class="muted intro">选择已完整发布的审查结果；提交后冻结来源、版本和当时反馈。</p>

    <a-alert v-if="formError" type="error" show-icon :message="formError" class="mb16" />
    <a-alert
      v-if="!modelsLoading && !hasUsableModel"
      type="warning"
      show-icon
      class="mb16"
      message="还没有可用于报告的模型。报告生成需要模型声明上下文窗口。"
    >
      <template #action><a-button size="small" @click="router.push('/models')">去配置模型</a-button></template>
    </a-alert>

    <a-card title="1. 报告信息" class="mb16">
      <div class="form-grid">
        <div class="field">
          <label>报告名称</label>
          <a-input v-model:value="form.title" :maxlength="200" placeholder="例如：订单服务专项审查报告" />
        </div>
        <div class="field">
          <label>生成模型</label>
          <a-select
            v-model:value="form.modelConfigId"
            style="width: 100%"
            placeholder="选择模型"
            :loading="modelsLoading"
            :options="modelOptions"
          />
          <p class="muted small">使用模型当前声明的窗口和输出上限；超限会明确失败，不静默截断。</p>
        </div>
        <div class="field full">
          <label>报告补充要求（可选）</label>
          <a-textarea
            v-model:value="form.instruction"
            :maxlength="4000"
            :auto-size="{ minRows: 2, maxRows: 4 }"
            placeholder="例如：优先说明支付重试风险，保留来源引用。"
          />
        </div>
      </div>
    </a-card>

    <a-card title="2. 审查材料" class="mb16">
      <div class="section-head">
        <a-button type="primary" :disabled="submitting" @click="pickerOpen = true">选择审查结果</a-button>
        <span class="muted small">已选 {{ selected.size }} 个结果</span>
      </div>
      <p v-if="!selected.size" class="muted small">尚未选择材料。点击「选择审查结果」，可同时选用专项与 Agent 的完整结果。</p>
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
    </a-card>
    <a-alert type="info" show-icon class="freeze-note" message="提交后冻结来源、版本和当时反馈；后续修改反馈或源码不改变本报告。" />
    <div class="form-footer">
      <span class="muted small">生成在后台执行，可在报告详情查看状态。</span>
      <span class="spacer" />
      <a-button @click="closeCreate">取消</a-button>
      <a-button type="primary" :loading="submitting" :disabled="!hasUsableModel || !selected.size" @click="submit">冻结材料并生成</a-button>
    </div>

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
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import SpecialistResultView from '@/components/SpecialistResultView.vue'
import AgentResultPanel from '@/components/AgentResultPanel.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import ReportResultPickerModal from '@/components/ReportResultPickerModal.vue'
import { kindText, pathTitle, shortSha } from '@/utils/reportCandidates'
import type { CodeReferencePayload } from '@/utils/codeReferences'
import {
  agentTasksApi,
  modelsApi,
  projectsApi,
  reportsApi,
  tasksApi,
  type AgentResultView,
  type ModelView,
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
const form = reactive({ title: '专项审查报告', modelConfigId: undefined as string | undefined, instruction: '' })
const formError = ref('')
const submitting = ref(false)
const projects = ref<ProjectView[]>([])
const models = ref<ModelView[]>([])
const modelsLoading = ref(false)
const selected = reactive(new Map<string, ReportCandidate>())
let disposed = false
onBeforeUnmount(() => { disposed = true; resultEpoch++ })
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
const selectedList = computed(() => Array.from(selected.values()))

onMounted(async () => {
  await loadOptions()
  if (!form.modelConfigId) {
    form.modelConfigId = defaultModelId()
  }
})

async function loadOptions() {
  modelsLoading.value = true
  try {
    const [projectPage, modelPage] = await Promise.all([
      projectsApi.list({ limit: 100 }),
      modelsApi.list({ limit: 100 })
    ])
    projects.value = projectPage.items
    models.value = modelPage.items
  } catch (failure) {
    formError.value = (failure as Error).message || '项目或模型读取失败'
  } finally {
    modelsLoading.value = false
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
    formError.value = '请选择生成模型。'
    return
  }
  submitting.value = true
  const generation = {
    modelConfigId: form.modelConfigId,
    instruction: form.instruction.trim() || undefined
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
.intro { margin: 0 0 16px; }
.mb16 { margin-bottom: 16px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field.full { grid-column: 1 / -1; }
.field label { font-size: 12px; font-weight: 550; color: var(--ink); }
.section-head, .form-footer { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.section-head { margin-bottom: 12px; }
.selected-list { display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow: auto; }
.selected-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; }
.selected-item > div { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.selected-item strong, .file-name { overflow-wrap: anywhere; }
.evidence-warning { color: #ad6800; }
.freeze-note { margin: 12px 0 16px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.spacer { flex: 1; }
@media (max-width: 650px) {
  .form-grid { grid-template-columns: 1fr; }
  .selected-item { align-items: flex-start; flex-direction: column; }
}
</style>

<style lang="less">
.report-create-modal .ant-modal {
  top: 24px;
  padding-bottom: 24px;
}
.report-create-modal .ant-modal-body {
  max-height: calc(100vh - 120px);
  overflow: auto;
}
</style>
