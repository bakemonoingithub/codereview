<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-button type="primary" @click="openCreate">新建策略</a-button>
    </a-space>

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="分析器" data-index="analyzerType">
        <template #default="{ text }">{{ analyzerText(text) }}</template>
      </a-table-column>
      <a-table-column title="创建时间" data-index="createdAt" />
    </a-table>

    <a-modal v-model:open="modalOpen" title="新建策略" :confirm-loading="saving" @ok="onCreate">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 通用代码审查" />
        </a-form-item>
        <a-form-item label="模型" required>
          <a-select v-model:value="form.modelConfigId" placeholder="选择模型" :options="modelOptions" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listStrategies, createStrategy } from '@/api/strategy'
import { listModels } from '@/api/model'

const records = ref<any[]>([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const form = ref({ name: '', modelConfigId: '' })
const modelOptions = ref<{ value: string; label: string }[]>([])

function analyzerText(t: number) {
  return t === 1 ? 'llm-review' : `类型${t}`
}

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
  form.value = { name: '', modelConfigId: '' }
  modalOpen.value = true
  loadModels()
}

async function onCreate() {
  if (!form.value.name) {
    message.warning('请填写名称')
    return
  }
  if (!form.value.modelConfigId) {
    message.warning('请选择模型')
    return
  }
  saving.value = true
  try {
    await createStrategy(form.value)
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
