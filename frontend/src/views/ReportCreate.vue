<template>
  <div>
    <div class="page-head">
      <div>
        <a-breadcrumb>
          <a-breadcrumb-item><router-link to="/reports">报告</router-link></a-breadcrumb-item>
          <a-breadcrumb-item>生成报告</a-breadcrumb-item>
        </a-breadcrumb>
        <h2>生成报告</h2>
        <p class="muted">勾选已完整发布的审查结果；提交后冻结来源、版本和当时反馈。</p>
      </div>
      <a-button @click="router.push('/reports')">返回报告</a-button>
    </div>

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

    <div class="create-grid">
      <a-card title="2. 选择审查结果" class="materials-card">
        <div class="filters">
          <a-select
            v-model:value="filterProject"
            allow-clear
            placeholder="全部项目"
            style="width: 190px"
            :options="projectOptions"
            @change="resetCandidates"
          />
          <a-select
            v-model:value="filterKind"
            allow-clear
            placeholder="全部类型"
            style="width: 150px"
            :options="kindOptions"
            @change="resetCandidates"
          />
          <span class="spacer" />
          <span class="muted small">已选 {{ selected.size }} 个</span>
          <a-button size="small" :loading="candidatesLoading" @click="resetCandidates">刷新</a-button>
        </div>

        <a-alert v-if="candidatesError" type="error" show-icon :message="candidatesError" class="mb12">
          <template #action><a-button size="small" @click="loadCandidates">重试</a-button></template>
        </a-alert>

        <a-table
          :data-source="candidates"
          row-key="resultId"
          :loading="candidatesLoading"
          :pagination="false"
          size="middle"
          :scroll="{ x: 760 }"
        >
          <a-table-column title="选择" key="select" width="64">
            <template #default="{ record }">
              <a-checkbox
                :checked="selected.has(record.resultId)"
                :aria-label="`选择 ${record.projectName || record.projectId} ${record.path || record.kind}`"
                @change="toggle(record)"
              />
            </template>
          </a-table-column>
          <a-table-column title="来源" key="source" width="220">
            <template #default="{ record }">
              <div class="source-cell">
                <strong>{{ record.projectName || record.projectId }}</strong>
                <span class="muted small">
                  {{ kindText(record.kind) }} · {{ record.taskState }} · {{ record.sourceKind === 'DIFF' ? '提交差异' : '文件内容' }}
                </span>
                <span class="muted small">{{ shortId(record.taskId) }}</span>
              </div>
            </template>
          </a-table-column>
          <a-table-column title="目标" key="path" width="250">
            <template #default="{ record }">
              <div class="path-cell">
                <span>{{ record.path || 'Agent Markdown' }}</span>
                <code class="muted small">{{ shortSha(record.headSha) }}</code>
              </div>
            </template>
          </a-table-column>
          <a-table-column title="问题" key="issues" width="80">
            <template #default="{ record }">
              {{ record.kind === 'ISSUE' ? `${record.issueCount} 个` : '结果级' }}
            </template>
          </a-table-column>
          <a-table-column title="反馈" key="feedback" width="120">
            <template #default="{ record }">
              <span class="muted small">{{ feedbackText(record) }}</span>
            </template>
          </a-table-column>
          <template #emptyText>
            <a-empty description="没有可选的完整审查结果">
              <a-button type="primary" @click="router.push('/reviews/new')">发起审查</a-button>
            </a-empty>
          </template>
        </a-table>
        <div v-if="candidatesHasMore" class="more">
          <a-button :loading="candidatesLoadingMore" @click="loadMoreCandidates">加载更多</a-button>
        </div>
      </a-card>

      <aside class="card selected-panel">
        <div class="section-head">
          <strong>已选材料</strong>
          <span class="spacer" />
          <span class="muted small">{{ selected.size }} 个</span>
        </div>
        <p v-if="!selected.size" class="empty muted small">
          还没有选择审查结果。提交前会冻结所选结果及其代码版本、问题和当时反馈。
        </p>
        <div v-else class="selected-list">
          <div v-for="item in selectedList" :key="item.resultId" class="selected-item">
            <div>
              <strong>{{ item.projectName || item.projectId }}</strong>
              <span class="muted small">
                {{ kindText(item.kind) }} · {{ item.path || 'Agent Markdown' }}
              </span>
              <span class="muted small">{{ shortSha(item.headSha) }}</span>
            </div>
            <a-button size="small" type="text" danger @click="remove(item.resultId)">移除</a-button>
          </div>
        </div>
        <a-alert
          type="info"
          show-icon
          class="freeze-note"
          message="提交后冻结来源、版本和当时反馈；后续修改反馈或源码不改变本报告。"
        />
        <a-button
          type="primary"
          block
          :loading="submitting"
          :disabled="!hasUsableModel"
          @click="submit"
        >
          冻结材料并生成
        </a-button>
        <p class="muted small submit-note">生成在后台执行；可在报告详情查看状态、失败原因和实际调用。</p>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  modelsApi,
  projectsApi,
  reportsApi,
  type ModelView,
  type ProjectView,
  type ReportCandidate
} from '@/api/v2'

const router = useRouter()
const form = reactive({ title: '专项审查报告', modelConfigId: undefined as string | undefined, instruction: '' })
const formError = ref('')
const submitting = ref(false)
const projects = ref<ProjectView[]>([])
const models = ref<ModelView[]>([])
const modelsLoading = ref(false)
const candidates = ref<ReportCandidate[]>([])
const selected = reactive(new Map<string, ReportCandidate>())
const filterProject = ref<string | undefined>()
const filterKind = ref<string | undefined>()
const candidatesLoading = ref(false)
const candidatesLoadingMore = ref(false)
const candidatesError = ref('')
const nextCursor = ref<string | null>(null)
const candidatesHasMore = ref(false)
let candidateEpoch = 0
let disposed = false
onBeforeUnmount(() => { disposed = true; candidateEpoch++ })

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: project.name }))
)
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
const kindOptions = [
  { value: 'ISSUE', label: '问题审查' },
  { value: 'PATTERN', label: '设计模式' },
  { value: 'COUPLING', label: '耦合度' },
  { value: 'AGENT', label: 'Agent' }
]
const selectedList = computed(() => Array.from(selected.values()))

onMounted(async () => {
  await Promise.all([loadOptions(), resetCandidates()])
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

async function resetCandidates() {
  candidates.value = []
  nextCursor.value = null
  candidatesHasMore.value = false
  await loadCandidates()
}

async function loadCandidates() {
  const epoch = ++candidateEpoch
  candidatesLoadingMore.value = false
  candidatesLoading.value = true
  candidatesError.value = ''
  try {
    const page = await reportsApi.candidates({
      projectId: filterProject.value,
      kind: filterKind.value,
      limit: 20
    })
    if (epoch !== candidateEpoch) return
    candidates.value = page.items
    nextCursor.value = page.nextCursor
    candidatesHasMore.value = page.hasMore
  } catch (failure) {
    if (epoch === candidateEpoch) candidatesError.value = (failure as Error).message || '可选结果读取失败'
  } finally {
    if (epoch === candidateEpoch) candidatesLoading.value = false
  }
}

async function loadMoreCandidates() {
  if (!nextCursor.value || candidatesLoading.value || candidatesLoadingMore.value) return
  const epoch = candidateEpoch
  candidatesLoadingMore.value = true
  candidatesError.value = ''
  try {
    const page = await reportsApi.candidates({
      projectId: filterProject.value,
      kind: filterKind.value,
      cursor: nextCursor.value,
      limit: 20
    })
    if (epoch !== candidateEpoch || nextCursor.value === null) return
    candidates.value = [...candidates.value, ...page.items]
    nextCursor.value = page.nextCursor
    candidatesHasMore.value = page.hasMore
  } catch (failure) {
    if (epoch === candidateEpoch) candidatesError.value = (failure as Error).message || '更多可选结果读取失败'
  } finally {
    if (epoch === candidateEpoch) candidatesLoadingMore.value = false
  }
}

function toggle(candidate: ReportCandidate) {
  if (selected.has(candidate.resultId)) {
    selected.delete(candidate.resultId)
  } else {
    selected.set(candidate.resultId, candidate)
  }
}

function remove(resultId: string) {
  selected.delete(resultId)
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

function kindText(kind: string) {
  return ({ ISSUE: '问题', PATTERN: '模式', COUPLING: '耦合', AGENT: 'Agent' } as Record<string, string>)[kind] || kind
}

function defaultModelId() {
  return models.value.find((model) => model.contextWindow && model.contextWindow > 0)?.id
    || models.value[0]?.id
}

function feedbackText(candidate: ReportCandidate) {
  if (!candidate.feedback || !candidate.feedback.assessment) {
    return candidate.feedback?.comment ? '未判定（有评论）' : '未反馈'
  }
  return ({
    ACCURATE: '准确', INACCURATE: '不准确', UNKNOWN: '无法判断'
  } as Record<string, string>)[candidate.feedback.assessment] || candidate.feedback.assessment
}

function shortSha(sha: string | null) {
  return sha ? sha.slice(0, 10) : '—'
}

function shortId(id: string) {
  return id.length <= 10 ? id : id.slice(-10)
}
</script>

<style scoped lang="less">
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-head h2 {
  margin: 4px 0;
}
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field.full {
  grid-column: 1 / -1;
}
.field label {
  font-weight: 500;
  color: #2c3e50;
}
.create-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 16px;
  align-items: start;
}
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.muted {
  color: #69798d;
}
.small {
  font-size: 12px;
}
.source-cell,
.path-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.selected-panel {
  position: sticky;
  top: 16px;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.selected-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 360px;
  overflow: auto;
  margin-bottom: 12px;
}
.selected-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  background: #f7f9fc;
  border: 1px solid #e3e9f0;
  border-radius: 6px;
}
.selected-item > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.freeze-note {
  margin: 12px 0;
}
.submit-note {
  margin: 8px 0 0;
}
.more {
  margin-top: 12px;
}
@media (max-width: 1100px) {
  .create-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .selected-panel {
    position: static;
  }
}
</style>
