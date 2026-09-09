<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-button type="primary" @click="openCreate">新建模型</a-button>
    </a-space>

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="接口地址" data-index="baseUrl" />
      <a-table-column title="模型名" data-index="modelName" />
      <a-table-column title="创建时间" data-index="createdAt" />
    </a-table>

    <a-modal v-model:open="modalOpen" title="新建模型" :confirm-loading="saving" @ok="onCreate">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 DeepSeek(默认)" />
        </a-form-item>
        <a-form-item label="接口地址">
          <a-input v-model:value="form.baseUrl" placeholder="https://api.deepseek.com" />
        </a-form-item>
        <a-form-item label="Token">
          <a-input-password v-model:value="form.token" placeholder="sk-..." />
        </a-form-item>
        <a-form-item label="模型名">
          <a-input v-model:value="form.modelName" placeholder="deepseek-chat" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listModels, createModel } from '@/api/model'

const records = ref<any[]>([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const form = ref({ name: '', baseUrl: 'https://api.deepseek.com', token: '', modelName: 'deepseek-chat' })

async function load() {
  loading.value = true
  try {
    const page = (await listModels({ pageNum: 1, pageSize: 100 })) as any
    records.value = page?.records || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { name: '', baseUrl: 'https://api.deepseek.com', token: '', modelName: 'deepseek-chat' }
  modalOpen.value = true
}

async function onCreate() {
  if (!form.value.name) {
    message.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    await createModel(form.value)
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
