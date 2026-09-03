<template>
  <div>
    <div class="toolbar">
      <a-button type="primary" @click="openCreate">新建项目</a-button>
    </div>
    <a-table :data-source="projects" row-key="id" :loading="loading" :pagination="false">
      <a-table-column title="名称" data-index="name">
        <template #default="{ record }">
          <router-link :to="`/projects/${record.id}`">{{ record.name }}</router-link>
        </template>
      </a-table-column>
      <a-table-column title="仓库地址" data-index="giteaUrl" />
      <a-table-column title="当前分支" data-index="currentBranch" />
    </a-table>

    <a-modal v-model:open="showCreate" title="新建项目" :confirm-loading="saving" @ok="onCreate">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="项目名" />
        </a-form-item>
        <a-form-item label="仓库地址" required>
          <a-input v-model:value="form.giteaUrl" placeholder="https://github.com/owner/repo" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listProjects, createProject } from '@/api/project'

const projects = ref<any[]>([])
const loading = ref(false)
const showCreate = ref(false)
const saving = ref(false)
const form = ref({ name: '', giteaUrl: '' })

async function load() {
  loading.value = true
  try {
    const page = await listProjects({ pageNum: 1, pageSize: 100 })
    projects.value = page.records || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { name: '', giteaUrl: '' }
  showCreate.value = true
}

async function onCreate() {
  if (!form.value.name || !form.value.giteaUrl) {
    message.warning('请填写名称和仓库地址')
    return
  }
  saving.value = true
  try {
    await createProject({ name: form.value.name, giteaUrl: form.value.giteaUrl })
    message.success('创建成功')
    showCreate.value = false
    load()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="less">
.toolbar {
  margin-bottom: 16px;
}
</style>
