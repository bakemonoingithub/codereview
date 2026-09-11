<template>
  <ListPageLayout title="项目管理" subtitle="维护要审查的代码仓库；审查入口在项目详情页">
    <template #actions>
      <a-button type="primary" @click="openCreate">新建项目</a-button>
    </template>

    <LoadErrorAlert :message="loadError" @retry="load" />
    <a-table :data-source="projects" row-key="id" :loading="loading" :pagination="false">
      <template #emptyText>
        <EmptyGuide
          title="还没有项目"
          hint="创建一个项目，把内网 Gitea 的仓库接进来，之后就能对它做审查"
          action-text="新建项目"
          @action="openCreate"
        />
      </template>
      <a-table-column title="名称" data-index="name">
        <template #default="{ record }">
          <router-link :to="`/projects/${record.id}`">{{ record.name }}</router-link>
        </template>
      </a-table-column>
      <a-table-column title="仓库地址" data-index="giteaUrl" ellipsis />
      <a-table-column title="当前分支" data-index="currentBranch" width="140" />
    </a-table>

    <a-modal v-model:open="showCreate" title="新建项目" :confirm-loading="saving" @ok="onCreate">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="项目名" />
        </a-form-item>
        <a-form-item label="仓库地址" required>
          <a-input v-model:value="form.giteaUrl" placeholder="https://gitea.内网/owner/repo" />
        </a-form-item>
        <a-form-item label="访问令牌（可选）">
          <a-input-password v-model:value="form.credential"
                            placeholder="仓库访问令牌；填了可避免 API 限流，私有仓库必填"
                            autocomplete="new-password" />
        </a-form-item>
      </a-form>
    </a-modal>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listProjects, createProject } from '@/api/project'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'

const projects = ref<any[]>([])
const loading = ref(false)
// 原先 load 没有 catch：请求失败时表格永远空着，且不给任何提示与重试入口
const loadError = ref('')
const showCreate = ref(false)
const saving = ref(false)
const form = ref({ name: '', giteaUrl: '', credential: '' })

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const page = await listProjects({ pageNum: 1, pageSize: 100 })
    projects.value = page.records || []
  } catch (e: any) {
    loadError.value = e?.message || '项目列表加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { name: '', giteaUrl: '', credential: '' }
  showCreate.value = true
}

async function onCreate() {
  if (!form.value.name || !form.value.giteaUrl) {
    message.warning('请填写名称和仓库地址')
    return
  }
  saving.value = true
  try {
    await createProject({
      name: form.value.name,
      giteaUrl: form.value.giteaUrl,
      credential: form.value.credential?.trim() || undefined
    })
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
