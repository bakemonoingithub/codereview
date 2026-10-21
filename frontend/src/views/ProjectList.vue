<template>
  <ListPageLayout
    title="项目管理"
    subtitle="维护待审查的代码仓库；仓库地址创建后锁定，令牌只写不读。代码浏览与任务执行在 P31 接入"
  >
    <template #actions>
      <a-button type="primary" @click="openCreate">＋ 新建项目</a-button>
    </template>

    <template #filters>
      <a-input-search
        v-model:value="keyword"
        class="filter-search"
        placeholder="搜索项目名称"
        allow-clear
        @search="reload"
      />
    </template>
    <template #filters-extra>
      <span class="filter-hint">管理可复用配置</span>
    </template>

    <LoadErrorAlert :message="loadError" @retry="load" />

    <a-table :data-source="projects" row-key="id" :loading="loading" :pagination="false">
      <template #emptyText>
        <EmptyGuide
          :title="keyword ? '没有匹配的项目' : '还没有项目'"
          :hint="keyword ? '换个关键词，或清空搜索看全部' : '创建一个项目，把代码仓库接进来；代码浏览与任务执行在 P31 接入'"
          :action-text="keyword ? '' : '新建项目'"
          @action="openCreate"
        />
      </template>
      <a-table-column title="名称">
        <template #default="{ record }"><strong>{{ record.name }}</strong></template>
      </a-table-column>
      <a-table-column title="仓库地址" data-index="repoUrl" ellipsis />
      <a-table-column title="当前分支" width="140">
        <template #default="{ record }">
          <code v-if="record.currentBranch">{{ record.currentBranch }}</code>
          <span v-else>—</span>
        </template>
      </a-table-column>
      <a-table-column title="访问令牌" width="120">
        <template #default="{ record }">
          <StatusBadge :tone="record.credentialConfigured ? 'green' : 'default'">
            {{ record.credentialConfigured ? '已配置' : '未配置' }}
          </StatusBadge>
        </template>
      </a-table-column>
      <a-table-column title="操作" width="100">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
        </template>
      </a-table-column>
    </a-table>

    <div v-if="hasMore" class="load-more">
      <a-button :loading="loading" @click="loadMore">加载更多</a-button>
    </div>

    <a-modal
      v-model:open="modalOpen"
      :title="editing ? '编辑项目' : '新建项目'"
      :confirm-loading="saving"
      @ok="onSave"
    >
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 订单服务" />
        </a-form-item>
        <a-form-item label="仓库地址" required>
          <a-input v-model:value="form.repoUrl" :disabled="!!editing" placeholder="https://git.example.com/owner/repo" />
          <div v-if="editing" class="field-hint">仓库地址创建后锁定；如需更换地址，请新建项目。</div>
        </a-form-item>
        <a-form-item label="访问令牌">
          <a-input-password
            v-model:value="form.credential"
            :placeholder="editing && credentialConfigured ? '已配置，留空不修改' : '可选；私有仓库必填'"
            autocomplete="new-password"
          />
        </a-form-item>
        <a-form-item v-if="!editing" label="当前分支">
          <a-input v-model:value="form.currentBranch" placeholder="main" />
        </a-form-item>
      </a-form>
    </a-modal>

    <template #footer>
      <PageNotice>新建时填写仓库地址；编辑时仓库地址锁定，令牌留空保留当前值。</PageNotice>
    </template>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { projectsApi, type ProjectView } from '@/api/v2'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import PageNotice from '@/components/PageNotice.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const projects = ref<ProjectView[]>([])
const loading = ref(false)
const loadError = ref('')
const keyword = ref('')
const nextCursor = ref<string | null>(null)
const hasMore = computed(() => !!nextCursor.value)
const modalOpen = ref(false)
const saving = ref(false)
const editing = ref<ProjectView | null>(null)
const credentialConfigured = ref(false)
const form = ref({ name: '', repoUrl: '', credential: '', currentBranch: 'main' })
let listGeneration = 0

async function load(cursor?: string) {
  if (cursor && loading.value) return
  const generation = ++listGeneration
  if (!cursor) nextCursor.value = null
  loading.value = true
  loadError.value = ''
  try {
    const page = await projectsApi.list({
      q: keyword.value.trim() || undefined,
      cursor,
      limit: 100
    })
    if (generation !== listGeneration) return
    projects.value = cursor ? [...projects.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (e: any) {
    if (generation !== listGeneration) return
    loadError.value = e?.message || '项目列表加载失败'
  } finally {
    if (generation === listGeneration) loading.value = false
  }
}

function reload() { return load() }

function loadMore() {
  if (nextCursor.value) load(nextCursor.value)
}

function openCreate() {
  editing.value = null
  credentialConfigured.value = false
  form.value = { name: '', repoUrl: '', credential: '', currentBranch: 'main' }
  modalOpen.value = true
}

function openEdit(record: ProjectView) {
  editing.value = record
  credentialConfigured.value = record.credentialConfigured
  form.value = {
    name: record.name,
    repoUrl: record.repoUrl,
    credential: '',
    currentBranch: record.currentBranch
  }
  modalOpen.value = true
}

async function onSave() {
  if (!form.value.name.trim() || !form.value.repoUrl.trim()) {
    message.warning('请填写名称和仓库地址')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await projectsApi.update(editing.value.id, {
        name: form.value.name.trim(),
        credential: form.value.credential.trim() || undefined,
        expectedRevision: editing.value.revision
      })
      message.success('保存成功')
    } else {
      await projectsApi.create({
        name: form.value.name.trim(),
        repoUrl: form.value.repoUrl.trim(),
        credential: form.value.credential.trim() || undefined,
        currentBranch: form.value.currentBranch.trim() || 'main'
      })
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

onMounted(load)
</script>

<style scoped lang="less">
.load-more {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
.field-hint {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}
</style>
