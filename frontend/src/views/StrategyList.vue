<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-button type="primary" @click="openCreate">新建策略</a-button>
    </a-space>

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="分析器" data-index="analyzerType">
        <template #default="{ text }">{{ analyzerLabel(text) }}</template>
      </a-table-column>
      <a-table-column title="创建时间" data-index="createdAt" />
    </a-table>

    <a-modal v-model:open="modalOpen" title="新建策略" :confirm-loading="saving" @ok="onCreate">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 通用代码审查" />
        </a-form-item>
        <a-form-item label="分析器" required>
          <a-select v-model:value="form.analyzerType" placeholder="选择分析器">
            <a-select-option v-for="a in ANALYZER_TYPES" :key="a.value" :value="a.value">{{ a.label }}</a-select-option>
          </a-select>
        </a-form-item>

        <template v-if="form.analyzerType !== 4">
          <a-form-item label="模型" required>
            <a-select v-model:value="form.modelConfigId" placeholder="选择模型" :options="modelOptions" />
          </a-form-item>
          <a-form-item v-if="form.analyzerType === 2" label="高耦合阈值（扇出超过即标记）">
            <a-input v-model:value="form.threshold" placeholder="默认 10" />
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
import { listStrategies, createStrategy, ANALYZER_TYPES, analyzerLabel } from '@/api/strategy'
import { listModels } from '@/api/model'

const records = ref<any[]>([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const form = ref({
  name: '',
  analyzerType: 1,
  modelConfigId: '',
  threshold: '10',
  apiUrl: '',
  resultUrl: '',
  queryUrl: '',
  token: ''
})
const modelOptions = ref<{ value: string; label: string }[]>([])

async function load() {
  loading.value = true
  try {
    const page = (await listStrategies({ pageNum: 1, pageSize: 100 })) as any
    records.value = page?.records || []
  } finally {
    loading.value = false
  }
}

async function loadModels() {
  const page = (await listModels({ pageNum: 1, pageSize: 100 })) as any
  modelOptions.value = (page?.records || []).map((m: any) => ({ value: m.id, label: m.name }))
}

function openCreate() {
  form.value = {
    name: '',
    analyzerType: 1,
    modelConfigId: '',
    threshold: '10',
    apiUrl: '',
    resultUrl: '',
    queryUrl: '',
    token: ''
  }
  modalOpen.value = true
  loadModels()
}

async function onCreate() {
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
    if (analyzerType === 2) {
      const t = Number(form.value.threshold)
      if (!Number.isNaN(t) && t > 0) params.threshold = t
    }
  }
  saving.value = true
  try {
    await createStrategy({ name: form.value.name, analyzerType, params })
    message.success('创建成功')
    modalOpen.value = false
    await load()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
