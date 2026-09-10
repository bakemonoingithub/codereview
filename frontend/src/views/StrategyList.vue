<template>
  <div>
    <div class="toolbar">
      <a-space>
        <a-input v-model:value="keyword" placeholder="搜索名称" style="width: 240px" allow-clear @pressEnter="load" />
        <a-select v-model:value="filterAnalyzerType" placeholder="分析器" style="width: 180px" allow-clear>
          <a-select-option v-for="a in ANALYZER_TYPES" :key="a.value" :value="a.value">{{ a.label }}</a-select-option>
        </a-select>
        <a-button type="primary" @click="load">搜索</a-button>
        <a-button @click="resetSearch">重置</a-button>
      </a-space>
      <a-button type="primary" @click="openCreate">新建策略</a-button>
    </div>

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="分析器" data-index="analyzerType">
        <template #default="{ text }">{{ analyzerLabel(text) }}</template>
      </a-table-column>
      <a-table-column title="创建时间" data-index="createdAt" />
      <a-table-column title="操作">
        <template #default="{ record }">
          <a-space>
            <a-button size="small" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm title="确认删除？" @confirm="onDelete(record.id)">
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="editingId ? '编辑策略' : '新建策略'" :confirm-loading="saving" @ok="onSave">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 通用代码审查" />
        </a-form-item>
        <a-form-item label="分析器" required>
          <a-select v-model:value="form.analyzerType" placeholder="选择分析器" :disabled="!!editingId">
            <a-select-option v-for="a in ANALYZER_TYPES" :key="a.value" :value="a.value">{{ a.label }}</a-select-option>
          </a-select>
        </a-form-item>

        <template v-if="form.analyzerType !== 4">
          <a-form-item label="模型" required>
            <a-select v-model:value="form.modelConfigId" placeholder="选择模型" :options="modelOptions" />
          </a-form-item>
          <a-form-item label="关注点提示词（可选）">
            <a-select v-model:value="form.promptId" placeholder="选择提示词" :options="promptOptions" allow-clear />
          </a-form-item>
          <a-form-item v-if="form.analyzerType === 2" label="高耦合阈值（扇出超过即标记）">
            <a-input v-model:value="form.threshold" placeholder="默认 10" />
          </a-form-item>
          <a-form-item v-if="form.analyzerType === 5" label="方法体窗口（变更行 ± N 行，超长方法体按此截断）">
            <a-input v-model:value="form.methodWindowLines" placeholder="默认 60" />
          </a-form-item>
        </template>

        <template v-else>
          <a-form-item label="调用 API" required>
            <a-input v-model:value="form.apiUrl" placeholder="触发流水线的 API 地址" />
          </a-form-item>
          <a-form-item label="结果展示地址" required>
            <a-input v-model:value="form.resultUrl" placeholder="SonarQube 结果页 URL" />
          </a-form-item>
          <a-form-item label="查询具体结果地址">
            <a-input v-model:value="form.queryUrl" placeholder="SonarQube Web API 地址（可选）" />
          </a-form-item>
          <a-form-item label="Token">
            <a-input-password v-model:value="form.token" placeholder="只读 token（可选）" />
          </a-form-item>
        </template>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listStrategies, createStrategy, updateStrategy, deleteStrategy, ANALYZER_TYPES, analyzerLabel } from '@/api/strategy'
import { listModels } from '@/api/model'
import { listPrompts } from '@/api/prompt'

const records = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const filterAnalyzerType = ref<number | undefined>(undefined)
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref('')
const form = ref({
  name: '',
  analyzerType: 1,
  modelConfigId: '',
  promptId: '',
  threshold: '10',
  methodWindowLines: '60',
  apiUrl: '',
  resultUrl: '',
  queryUrl: '',
  token: ''
})
const modelOptions = ref<{ value: string; label: string }[]>([])
const promptOptions = ref<{ value: string; label: string }[]>([])

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = { pageNum: 1, pageSize: 100 }
    const kw = keyword.value.trim()
    if (kw) params.keyword = kw
    if (filterAnalyzerType.value !== undefined && filterAnalyzerType.value !== null) {
      params.analyzerType = filterAnalyzerType.value
    }
    const page = (await listStrategies(params)) as any
    records.value = page?.records || []
  } finally {
    loading.value = false
  }
}

function resetSearch() {
  keyword.value = ''
  filterAnalyzerType.value = undefined
  load()
}

async function loadModels() {
  const page = (await listModels({ pageNum: 1, pageSize: 100 })) as any
  modelOptions.value = (page?.records || []).map((m: any) => ({ value: m.id, label: m.name }))
}

async function loadPrompts() {
  try {
    const page = (await listPrompts({ pageNum: 1, pageSize: 100 })) as any
    promptOptions.value = (page?.records || []).map((p: any) => ({ value: p.id, label: p.name }))
  } catch {
    // 忽略
  }
}

function openCreate() {
  editingId.value = ''
  form.value = {
    name: '',
    analyzerType: 1,
    modelConfigId: '',
    promptId: '',
    threshold: '10',
    methodWindowLines: '60',
    apiUrl: '',
    resultUrl: '',
    queryUrl: '',
    token: ''
  }
  modalOpen.value = true
  loadModels()
  loadPrompts()
}

function openEdit(record: any) {
  editingId.value = record.id
  let params: Record<string, any> = {}
  try {
    params = JSON.parse(record.paramsJson || '{}')
  } catch {
    params = {}
  }
  form.value = {
    name: record.name,
    analyzerType: record.analyzerType,
    modelConfigId: params.modelConfigId || '',
    promptId: params.promptId || params.promptVersionId || '',
    threshold: params.threshold != null ? String(params.threshold) : '10',
    methodWindowLines: params.methodWindowLines != null ? String(params.methodWindowLines) : '60',
    apiUrl: params.apiUrl || '',
    resultUrl: params.resultUrl || '',
    queryUrl: params.queryUrl || '',
    token: params.token || ''
  }
  modalOpen.value = true
  loadModels()
  loadPrompts()
}

async function onSave() {
  if (!form.value.name) {
    message.warning('请填写名称')
    return
  }
  const analyzerType = Number(form.value.analyzerType)
  const params: Record<string, any> = {}
  if (analyzerType === 4) {
    if (!form.value.apiUrl || !form.value.resultUrl) {
      message.warning('请填写调用 API 与结果展示地址')
      return
    }
    params.apiUrl = form.value.apiUrl
    params.resultUrl = form.value.resultUrl
    if (form.value.queryUrl) params.queryUrl = form.value.queryUrl
    if (form.value.token) params.token = form.value.token
  } else {
    if (!form.value.modelConfigId) {
      message.warning('请选择模型')
      return
    }
    params.modelConfigId = form.value.modelConfigId
    if (form.value.promptId) params.promptId = form.value.promptId
    if (analyzerType === 2) {
      const t = Number(form.value.threshold)
      if (!Number.isNaN(t) && t > 0) params.threshold = t
    }
    if (analyzerType === 5) {
      const w = Number(form.value.methodWindowLines)
      if (!Number.isNaN(w) && w > 0) params.methodWindowLines = w
    }
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateStrategy(editingId.value, { name: form.value.name, params })
      message.success('保存成功')
    } else {
      await createStrategy({ name: form.value.name, analyzerType, params })
      message.success('创建成功')
    }
    modalOpen.value = false
    await load()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDelete(id: string) {
  try {
    await deleteStrategy(id)
    message.success('删除成功')
    await load()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped lang="less">
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
