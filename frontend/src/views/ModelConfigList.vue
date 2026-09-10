<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-button type="primary" @click="openCreate">新建模型</a-button>
    </a-space>

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="接口地址" data-index="baseUrl" />
      <a-table-column title="模型名" data-index="modelName" />
      <a-table-column title="状态" data-index="status">
        <template #default="{ text }">
          <a-tag :color="text === 1 ? 'green' : text === 2 ? 'red' : 'default'">
            {{ text === 1 ? '验证成功' : text === 2 ? '验证失败' : '未验证' }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="操作">
        <template #default="{ record }">
          <a-space>
            <a-button size="small" @click="onVerify(record)">验证</a-button>
            <a-button size="small" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm title="确认删除？" @confirm="onDelete(record.id)">
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="editingId ? '编辑模型' : '新建模型'" :confirm-loading="saving" @ok="onSave">
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
import { listModels, createModel, updateModel, deleteModel, verifyModel } from '@/api/model'

const records = ref<any[]>([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref('')
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
  editingId.value = ''
  form.value = { name: '', baseUrl: 'https://api.deepseek.com', token: '', modelName: 'deepseek-chat' }
  modalOpen.value = true
}

function openEdit(record: any) {
  editingId.value = record.id
  form.value = { name: record.name, baseUrl: record.baseUrl || '', token: '', modelName: record.modelName || '' }
  modalOpen.value = true
}

async function onSave() {
  if (!form.value.name) {
    message.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateModel(editingId.value, form.value)
    } else {
      await createModel(form.value)
    }
    message.success('保存成功')
    modalOpen.value = false
    await load()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onVerify(record: any) {
  try {
    await verifyModel(record.id)
    message.success('验证完成')
    await load()
  } catch (e: any) {
    message.error(e?.message || '验证失败')
  }
}

async function onDelete(id: string) {
  try {
    await deleteModel(id)
    message.success('删除成功')
    await load()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onMounted(load)
</script>
