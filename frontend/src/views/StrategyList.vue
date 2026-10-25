<template>
  <ListPageLayout
    title="策略管理"
    subtitle="为专项选择模型和可选的通用规则提示词；发起任务时固定当时配置"
  >
    <template #actions>
      <a-button type="primary" @click="openCreate">＋ 新建策略</a-button>
    </template>

    <template #notice>
      <PageNotice>策略关联专项类型、模型及可选提示词。文件说明仍由每次任务分别填写，内置任务和输出协议集中管理。</PageNotice>
    </template>

    <template #filters>
      <a-input-search
        v-model:value="keyword"
        class="filter-search"
        placeholder="搜索策略名称"
        allow-clear
        @search="reload"
      />
      <a-select
        v-model:value="specialistFilter"
        style="min-width: 160px"
        :options="specialistFilterOptions"
        @change="reload"
      />
    </template>

    <LoadErrorAlert :message="loadError" @retry="retry" />

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="pagination">
      <template #emptyText>
        <EmptyGuide
          :title="keyword || specialistFilter ? '没有匹配的策略' : '还没有审查策略'"
          :hint="
            keyword || specialistFilter
              ? '调整筛选条件，或清空后看全部'
              : '策略 = 专项类型 + 模型 + 可选提示词；建好后在发起审查时选用'
          "
          :action-text="keyword || specialistFilter ? '' : '新建策略'"
          @action="openCreate"
        />
      </template>
      <a-table-column title="策略名称">
        <template #default="{ record }"><strong>{{ record.name }}</strong></template>
      </a-table-column>
      <a-table-column title="专项类型" width="150">
        <template #default="{ record }">{{ specialistLabel(record.specialistType) }}</template>
      </a-table-column>
      <a-table-column title="关联模型" width="280">
        <template #default="{ record }">
          <div v-if="modelById(record.modelConfigId)" class="association-summary">
            <a-button type="link" size="small" @click="openAssociationDetails('model', record.modelConfigId)"><strong>{{ modelById(record.modelConfigId)?.name }}</strong></a-button>
          </div>
          <span v-else class="muted">{{ modelName(record.modelConfigId) }}</span>
        </template>
      </a-table-column>
      <a-table-column title="通用规则提示词" width="260">
        <template #default="{ record }">
          <template v-if="record.promptId">
            <div class="association-summary">
              <a-button type="link" size="small" @click="openAssociationDetails('prompt', record.promptId)"><strong>{{ promptName(record.promptId) }}</strong></a-button>
            </div>
          </template>
          <span v-else class="muted">不选提示词</span>
        </template>
      </a-table-column>
      <a-table-column title="操作" width="160">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
          <a-popconfirm title="删除后历史任务不受影响，确认删除？" @confirm="onDelete(record.id)">
            <a-button type="link" size="small" danger>删除</a-button>
          </a-popconfirm>
        </template>
      </a-table-column>
    </a-table>



    <a-modal
      v-model:open="modalOpen"
      :title="editing ? '编辑策略' : '新建策略'"
      :confirm-loading="saving"
      wrap-class-name="admin-form-modal"
      @ok="onSave"
    >
      <a-form class="form-grid" layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 订单服务规范" />
        </a-form-item>
        <a-form-item label="专项类型" required>
          <a-select v-model:value="form.specialistType" :options="specialistOptions" />
        </a-form-item>
        <a-form-item label="关联模型" required>
          <div class="field-with-link">
            <a-select v-model:value="form.modelConfigId" placeholder="选择模型" :options="modelOptions">
              <template #notFoundContent>
                <div class="not-found">
                  <span>暂无可用模型</span>
                  <router-link to="/models">去创建模型</router-link>
                </div>
              </template>
            </a-select>
            <router-link class="manage-link" to="/models">管理模型 →</router-link>
          </div>
          <div v-if="modelLoadError" class="field-error">
            {{ modelLoadError }}，<router-link to="/models">去模型页重试</router-link>
          </div>
        </a-form-item>
        <a-form-item label="通用规则提示词（可选）">
          <div class="field-with-link">
            <a-select v-model:value="form.promptId" placeholder="不选提示词" :options="promptOptions" allow-clear @change="onPromptChange">
              <template #notFoundContent>
                <div class="not-found">
                  <span>暂无专项提示词</span>
                  <router-link to="/prompts">去创建提示词</router-link>
                </div>
              </template>
            </a-select>
            <router-link class="manage-link" to="/prompts">管理提示词 →</router-link>
          </div>
          <div v-if="promptLoadError" class="field-error">
            {{ promptLoadError }}，<router-link to="/prompts">去提示词页重试</router-link>
          </div>
        </a-form-item>
        <div class="span-2">
          <StrategyAssociationInfo
            :model="selectedModel"
            :prompt="selectedPrompt"
            :model-selected="!!form.modelConfigId"
            :prompt-selected="!!form.promptId"
            :prompt-loading="selectedPromptLoading"
            :error="selectedPromptError"
          />
          <div class="field-hint">策略关联的是所选提示词记录；新任务使用它当时的当前正文版本，历史任务保留正文快照。</div>
        </div>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="associationDetailsOpen"
      :title="associationDetailsTitle"
      :footer="null"
      width="min(760px, calc(100vw - 40px))"
    >
      <a-spin :spinning="associationDetailsLoading">
        <a-alert v-if="associationDetailsError" type="error" show-icon :message="associationDetailsError" />
        <template v-else-if="associationDetailsKind === 'model' && associationModel">
          <div class="association-preview-title">{{ associationModel.name }} · {{ associationModel.modelName }}</div>
          <div class="association-facts detail-facts">
            <span>接口地址 <b>{{ associationModel.baseUrl }}</b></span>
            <span>协议 <b>{{ associationModel.protocol }}</b></span>
            <span>上下文窗口 <b>{{ formatNumber(associationModel.contextWindow) }}</b></span>
            <span>最大输出 <b>{{ formatNumber(associationModel.maxOutput) }}</b></span>
            <span>计量方式 <b>{{ associationModel.tokenizer || '保守估算' }}</b></span>
            <span>凭据 <b>{{ associationModel.credentialConfigured ? '已配置（不显示密钥）' : '未配置' }}</b></span>
          </div>
          <div class="association-badges">
            <StatusBadge :tone="validationTone(associationModel.validationStatus)">专项审查 {{ validationLabel(associationModel.validationStatus) }}</StatusBadge>
            <StatusBadge :tone="validationTone(associationModel.agentValidationStatus)">Agent {{ validationLabel(associationModel.agentValidationStatus) }}</StatusBadge>
          </div>
        </template>
        <template v-else-if="associationDetailsKind === 'prompt' && associationPrompt">
          <div class="association-preview-title">
            {{ associationPrompt.name }} · {{ formatPromptVersion(associationPrompt.currentVersion?.versionNo) }}
          </div>
          <p v-if="associationPrompt.description" class="field-hint">{{ associationPrompt.description }}</p>
          <div v-if="associationPrompt.tags.length" class="association-tags">
            <a-tag v-for="tag in associationPrompt.tags" :key="tag">{{ tag }}</a-tag>
          </div>
          <pre class="prompt-content-full">{{ associationPrompt.currentVersion?.content || '该提示词当前没有正文版本。' }}</pre>
<!--          <p class="field-hint">策略按提示词 ID 关联，不固定版本；新任务发起时读取该提示词当时的当前版本。已创建任务使用保存的正文快照。</p>-->
        </template>
      </a-spin>
    </a-modal>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  modelsApi,
  promptsApi,
  strategiesApi,
  type ModelView,
  type PromptSummary,
  type PromptView,
  type StrategyView
} from '@/api/v2'
import { useListPagination } from '@/utils/useListPagination'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import PageNotice from '@/components/PageNotice.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import StrategyAssociationInfo from '@/components/StrategyAssociationInfo.vue'
import { formatPromptVersion } from '@/utils/promptVersion'

const models = ref<ModelView[]>([])
const prompts = ref<PromptSummary[]>([])
const keyword = ref('')
const specialistFilter = ref<string | undefined>(undefined)
const modalOpen = ref(false)
const saving = ref(false)
const editing = ref<StrategyView | null>(null)
const modelLoadError = ref('')
const promptLoadError = ref('')
const selectedPrompt = ref<PromptView | null>(null)
const selectedPromptLoading = ref(false)
const selectedPromptError = ref('')
const associationDetailsOpen = ref(false)
const associationDetailsKind = ref<'model' | 'prompt'>('model')
const associationModel = ref<ModelView | null>(null)
const associationPrompt = ref<PromptView | null>(null)
const associationDetailsLoading = ref(false)
const associationDetailsError = ref('')
let selectedPromptEpoch = 0
let associationDetailsEpoch = 0
const form = ref({
  name: '',
  specialistType: 'ISSUE',
  modelConfigId: undefined as string | undefined,
  promptId: undefined as string | undefined
})

const specialistOptions = [
  { value: 'ISSUE', label: '问题审查（含提交差异）' },
  { value: 'PATTERN', label: '设计模式' },
  { value: 'COUPLING', label: '耦合度' }
]
const specialistFilterOptions = [{ value: '', label: '全部专项' }, ...specialistOptions]
const modelOptions = computed(() => models.value.map((m) => ({
  value: m.id,
  label: `${m.name} · ${m.modelName}`
})))
const promptOptions = computed(() =>
  prompts.value
    .filter((p) => p.purpose === 'SPECIALIST' || p.id === form.value.promptId)
    .map((p) => ({
      value: p.id,
      label: `${p.name} · ${formatPromptVersion(p.currentVersionNo)}`
    }))
)
const selectedModel = computed(() => modelById(form.value.modelConfigId || null))
const associationDetailsTitle = computed(() => {
  if (associationDetailsKind.value === 'model') return '关联模型详情'
  return '关联提示词详情'
})

function specialistLabel(value: string) {
  return specialistOptions.find((s) => s.value === value)?.label || value
}

function modelById(id: string | null) {
  return id ? models.value.find((m) => m.id === id) || null : null
}

function modelName(id: string | null) {
  if (!id) return '未配置'
  return modelById(id)?.name || '已失效的模型'
}

function promptName(id: string | null) {
  if (!id) return ''
  return prompts.value.find((p) => p.id === id)?.name || '已失效的提示词'
}

function formatNumber(value: number | null | undefined): string {
  return value == null ? '未填写' : value.toLocaleString()
}

const validationLabels: Record<string, string> = {
  PASSED: '验证通过', FAILED: '验证失败', EXPIRED: '配置已变更，验证过期', NONE: '未验证'
}
const promptSummaryLoads = new Set<string>()
const modelSummaryLoads = new Set<string>()

function validationLabel(status: string): string {
  return validationLabels[status] || status
}

function validationTone(status: string): 'green' | 'amber' | 'red' | 'default' {
  if (status === 'PASSED') return 'green'
  if (status === 'FAILED') return 'red'
  if (status === 'EXPIRED' || status === 'NONE') return 'amber'
  return 'default'
}

const { items: records, loading, error: loadError, pagination, load, refresh, retry } = useListPagination<StrategyView>(
  'strategies', params => strategiesApi.list({ ...params, q: keyword.value.trim() || undefined, specialistType: specialistFilter.value || undefined }), '策略列表加载失败', () => JSON.stringify([keyword.value.trim(), specialistFilter.value])
)
function reload() { return load() }

async function loadModels() {
  modelLoadError.value = ''
  try {
    const page = await modelsApi.list({ limit: 100 })
    models.value = page.items
  } catch (e: any) {
    modelLoadError.value = e?.message || '模型列表加载失败'
  }
}

async function loadPrompts() {
  promptLoadError.value = ''
  try {
    const page = await promptsApi.list({ limit: 100 })
    const associatedIds = new Set(records.value.map((record) => record.promptId).filter(Boolean))
    const cachedAssociations = prompts.value.filter((prompt) => associatedIds.has(prompt.id)
      && !page.items.some((item) => item.id === prompt.id))
    prompts.value = [...page.items, ...cachedAssociations]
  } catch (e: any) {
    promptLoadError.value = e?.message || '提示词列表加载失败'
  }
}

async function ensureOptions() {
  await Promise.all([loadModels(), loadPrompts()])
}

function openCreate() {
  editing.value = null
  form.value = {
    name: '',
    specialistType: 'ISSUE',
    modelConfigId: undefined,
    promptId: undefined
  }
  selectedPromptEpoch++
  selectedPrompt.value = null
  selectedPromptError.value = ''
  modalOpen.value = true
  ensureOptions()
}

function openEdit(record: StrategyView) {
  editing.value = record
  form.value = {
    name: record.name,
    specialistType: record.specialistType,
    modelConfigId: record.modelConfigId || undefined,
    promptId: record.promptId || undefined
  }
  selectedPromptEpoch++
  selectedPrompt.value = null
  selectedPromptError.value = ''
  modalOpen.value = true
  if (record.promptId) void loadSelectedPrompt(record.promptId)
  ensureOptions()
}

function onPromptChange(id?: string) {
  selectedPromptEpoch++
  selectedPrompt.value = null
  selectedPromptError.value = ''
  if (id) void loadSelectedPrompt(id)
}

async function loadSelectedPrompt(id: string) {
  const epoch = ++selectedPromptEpoch
  selectedPromptLoading.value = true
  selectedPromptError.value = ''
  try {
    const detail = await promptsApi.detail(id)
    if (epoch !== selectedPromptEpoch || form.value.promptId !== id) return
    selectedPrompt.value = detail
    const summary = promptSummaryFromDetail(detail)
    const index = prompts.value.findIndex((prompt) => prompt.id === id)
    if (index < 0) prompts.value = [...prompts.value, summary]
    else prompts.value[index] = summary
  } catch (failure) {
    if (epoch === selectedPromptEpoch && form.value.promptId === id) {
      selectedPromptError.value = (failure as Error).message || '读取所选提示词失败'
    }
  } finally {
    if (epoch === selectedPromptEpoch) selectedPromptLoading.value = false
  }
}

function promptSummaryFromDetail(detail: PromptView): PromptSummary {
  return {
    id: detail.id,
    name: detail.name,
    purpose: detail.purpose,
    tags: detail.tags,
    revision: detail.revision,
    currentVersionNo: detail.currentVersion?.versionNo ?? null,
    updatedAt: detail.updatedAt
  }
}

async function loadAssociatedPromptSummary(id: string) {
  if (!id || promptSummaryLoads.has(id) || prompts.value.some((prompt) => prompt.id === id)) return
  promptSummaryLoads.add(id)
  try {
    const detail = await promptsApi.detail(id)
    const summary = promptSummaryFromDetail(detail)
    if (!prompts.value.some((prompt) => prompt.id === id)) prompts.value = [...prompts.value, summary]
  } catch {
    // Table keeps an explicit unavailable label; detail errors surface when the user opens it.
  } finally {
    promptSummaryLoads.delete(id)
  }
}

async function loadAssociatedModelSummary(id: string) {
  if (!id || modelSummaryLoads.has(id) || models.value.some((model) => model.id === id)) return
  modelSummaryLoads.add(id)
  try {
    const model = await modelsApi.detail(id)
    if (!models.value.some((item) => item.id === id)) models.value = [...models.value, model]
  } catch {
    // The table keeps an explicit unavailable label; detail errors surface when opened.
  } finally {
    modelSummaryLoads.delete(id)
  }
}

async function openAssociationDetails(kind: 'model' | 'prompt', id: string | null) {
  if (!id) return
  const epoch = ++associationDetailsEpoch
  associationDetailsKind.value = kind
  associationDetailsOpen.value = true
  associationDetailsError.value = ''
  associationModel.value = null
  associationPrompt.value = null
  if (kind === 'model') {
    associationDetailsLoading.value = true
    try {
      const model = modelById(id) || await modelsApi.detail(id)
      if (epoch !== associationDetailsEpoch) return
      associationModel.value = model
      if (!models.value.some((item) => item.id === id)) models.value = [...models.value, model]
    } catch (failure) {
      if (epoch === associationDetailsEpoch) {
        associationDetailsError.value = (failure as Error).message || '读取关联模型失败'
      }
    } finally {
      if (epoch === associationDetailsEpoch) associationDetailsLoading.value = false
    }
    return
  }
  associationDetailsLoading.value = true
  try {
    const prompt = await promptsApi.detail(id)
    if (epoch === associationDetailsEpoch) associationPrompt.value = prompt
  } catch (failure) {
    if (epoch === associationDetailsEpoch) {
      associationDetailsError.value = (failure as Error).message || '读取关联提示词失败'
    }
  } finally {
    if (epoch === associationDetailsEpoch) associationDetailsLoading.value = false
  }
}

async function onSave() {
  if (!form.value.name.trim()) {
    message.warning('请填写策略名称')
    return
  }
  if (!form.value.modelConfigId) {
    message.warning('请选择关联模型')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      specialistType: form.value.specialistType,
      modelConfigId: form.value.modelConfigId,
      promptId: form.value.promptId || undefined
    }
    if (editing.value) {
      await strategiesApi.update(editing.value.id, {
        ...payload,
        expectedRevision: editing.value.revision
      })
      message.success('保存成功')
    } else {
      await strategiesApi.create(payload)
      message.success('创建成功')
    }
    modalOpen.value = false
    await (editing.value ? refresh() : load())
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDelete(id: string) {
  try {
    await strategiesApi.remove(id)
    message.success('删除成功')
    await refresh()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onMounted(async () => {
  await Promise.all([load(), loadModels(), loadPrompts()])
})

watch(records, (items) => {
  const ids = [...new Set(items.map((item) => item.promptId).filter((id): id is string => !!id))]
  ids.forEach((id) => void loadAssociatedPromptSummary(id))
  const modelIds = [...new Set(items.map((item) => item.modelConfigId).filter((id): id is string => !!id))]
  modelIds.forEach((id) => void loadAssociatedModelSummary(id))
}, { immediate: true })
</script>

<style scoped lang="less">
.muted {
  color: var(--muted);
}
.field-hint {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}
.field-error {
  margin-top: 4px;
  color: #cf1322;
  font-size: 12px;
}
.not-found {
  padding: 4px 0;
  color: #8c8c8c;
  text-align: center;

  a {
    margin-left: 6px;
  }
}
.association-summary {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-width: 0;
}
.association-badges,
.association-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.association-tags :deep(.ant-tag) {
  margin-inline-end: 0;
}
.association-preview {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}
.association-preview-title {
  margin-bottom: 8px;
  font-weight: 600;
}
.association-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px 16px;
  color: var(--muted);
  font-size: 12px;
}
.association-facts b {
  display: block;
  overflow-wrap: anywhere;
  color: var(--text, #1f2937);
  font-weight: 500;
}
.association-placeholder {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  color: var(--muted);
  font-size: 12px;
}
.prompt-content-preview,
.prompt-content-full {
  margin: 10px 0 4px;
  padding: 10px 12px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  color: var(--text, #1f2937);
  font: inherit;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.prompt-content-preview {
  max-height: 180px;
}
.prompt-content-preview.expanded {
  max-height: 440px;
}
.prompt-content-full {
  max-height: 55vh;
}
.detail-facts {
  margin: 14px 0;
}
@media (max-width: 600px) {
  .association-facts {
    grid-template-columns: 1fr;
  }
}
</style>
