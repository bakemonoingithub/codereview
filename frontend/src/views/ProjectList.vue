<template>
  <ListPageLayout
    title="项目管理"
    subtitle="维护待审查的代码仓库。"
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
<!--    <template #filters-extra>-->
<!--      <span class="filter-hint">管理可复用配置</span>-->
<!--    </template>-->

    <LoadErrorAlert :message="loadError" @retry="retry" />

    <a-table :data-source="projects" row-key="id" :loading="loading" :pagination="pagination">
      <template #emptyText>
        <EmptyGuide
          :title="keyword ? '没有匹配的项目' : '还没有项目'"
          :hint="keyword ? '换个关键词，或清空搜索看全部' : '创建一个项目，把代码仓库接进来。'"
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
      <a-table-column title="操作" width="150">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
          <a-button type="link" size="small" danger @click="askDelete(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>



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

    <a-modal
      v-model:open="confirmOpen"
      title="删除项目"
      ok-text="删除"
      ok-type="danger"
      cancel-text="取消"
      :confirm-loading="deleting"
      @ok="confirmDelete"
    >
      <p v-if="pendingDelete">
        将从项目列表移除「{{ pendingDelete.name }}」。已完成的审查任务仍保留，不能再从该项目发起新审查。无法删除存在进行中任务的项目。
      </p>
      <p v-if="deleteError" class="delete-error">{{ deleteError }}</p>
    </a-modal>

<!--    <template #footer>-->
<!--      <PageNotice>新建时填写仓库地址；编辑时仓库地址锁定，令牌留空保留当前值。</PageNotice>-->
<!--    </template>-->
  </ListPageLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { projectsApi, type ProjectView } from '@/api/v2'
import { useListPagination } from '@/utils/useListPagination'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import PageNotice from '@/components/PageNotice.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const keyword = ref('')
const modalOpen = ref(false)
const confirmOpen = ref(false)
const deleting = ref(false)
const deleteError = ref('')
const pendingDelete = ref<ProjectView | null>(null)
const saving = ref(false)
const editing = ref<ProjectView | null>(null)
const credentialConfigured = ref(false)
const form = ref({ name: '', repoUrl: '', credential: '', currentBranch: 'main' })

const { items: projects, loading, error: loadError, pagination, load, refresh, retry } = useListPagination<ProjectView>(
  'projects', params => projectsApi.list({ ...params, q: keyword.value.trim() || undefined }), '项目列表加载失败', () => keyword.value.trim()
)
function reload() { return load() }

function openCreate() {
  editing.value = null
  credentialConfigured.value = false
  form.value = { name: '', repoUrl: '', credential: '', currentBranch: 'main' }
  modalOpen.value = true
}

function askDelete(record: ProjectView) {
  pendingDelete.value = record
  deleteError.value = ''
  confirmOpen.value = true
}

async function confirmDelete() {
  if (!pendingDelete.value || deleting.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await projectsApi.remove(pendingDelete.value.id)
    message.success('已删除')
    confirmOpen.value = false
    pendingDelete.value = null
    await refresh()
  } catch (e: any) {
    deleteError.value = e?.message || '删除失败'
    message.error(deleteError.value)
    throw e
  } finally {
    deleting.value = false
  }
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
    await (editing.value ? refresh() : load())
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="less">
.field-hint {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}
.delete-error {
  color: var(--red);
  margin-bottom: 0;
}
</style>
