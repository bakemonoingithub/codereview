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
      <a-table-column title="关联模型" width="180">
        <template #default="{ record }">{{ modelName(record.modelConfigId) }}</template>
      </a-table-column>
      <a-table-column title="通用规则提示词" width="220">
        <template #default="{ record }">
          <template v-if="record.promptId">
            {{ promptName(record.promptId) }}
            <code v-if="promptRevision(record.promptId) != null">r{{ promptRevision(record.promptId) }}</code>
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
            <a-select v-model:value="form.promptId" placeholder="不选提示词" :options="promptOptions" allow-clear>
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
          <div class="field-hint">只列出用途为“专项通用规则”的提示词；正文版本在发起任务时固定。</div>
        </a-form-item>
      </a-form>
    </a-modal>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  modelsApi,
  promptsApi,
  strategiesApi,
  type ModelView,
  type PromptSummary,
  type StrategyView
} from '@/api/v2'
import { useListPagination } from '@/utils/useListPagination'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import PageNotice from '@/components/PageNotice.vue'

const models = ref<ModelView[]>([])
const prompts = ref<PromptSummary[]>([])
const keyword = ref('')
const specialistFilter = ref<string | undefined>(undefined)
const modalOpen = ref(false)
const saving = ref(false)
const editing = ref<StrategyView | null>(null)
const modelLoadError = ref('')
const promptLoadError = ref('')
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
const modelOptions = computed(() => models.value.map((m) => ({ value: m.id, label: m.name })))
const promptOptions = computed(() =>
  prompts.value
    .filter((p) => p.purpose === 'SPECIALIST')
    .map((p) => ({ value: p.id, label: p.name }))
)

function specialistLabel(value: string) {
  return specialistOptions.find((s) => s.value === value)?.label || value
}

function modelName(id: string | null) {
  if (!id) return '未配置'
  return models.value.find((m) => m.id === id)?.name || '已失效的模型'
}

function promptName(id: string | null) {
  if (!id) return ''
  return prompts.value.find((p) => p.id === id)?.name || '已失效的提示词'
}

function promptRevision(id: string | null): number | null {
  if (!id) return null
  const prompt = prompts.value.find((item) => item.id === id)
  return prompt && typeof prompt.revision === 'number' ? prompt.revision : null
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
    prompts.value = page.items
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
  modalOpen.value = true
  ensureOptions()
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
</style>
