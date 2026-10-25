<template>
  <ListPageLayout
    title="提示词管理"
    subtitle="维护审查通用规则，可按版本保存。"
  >
    <template #actions>
      <a-button type="primary" @click="openCreate">＋ 新建提示词</a-button>
    </template>

    <template #filters>
      <a-input-search
        v-model:value="keyword"
        class="filter-search"
        placeholder="搜索名称或标签"
        allow-clear
        @search="reload"
      />
    </template>

    <LoadErrorAlert :message="loadError" @retry="retry" />

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="pagination">
      <template #emptyText>
        <EmptyGuide
          :title="keyword ? '没有匹配的提示词' : '还没有提示词'"
          :hint="keyword ? '换个关键词，或清空搜索看全部' : '保存可复用的审查规则；发起任务时可直接选择。'"
          :action-text="keyword ? '' : '新建提示词'"
          @action="openCreate"
        />
      </template>
      <a-table-column title="名称">
        <template #default="{ record }"><strong>{{ record.name }}</strong></template>
      </a-table-column>
      <a-table-column title="用途" width="120">
        <template #default="{ record }">{{ purposeLabel(record.purpose) }}</template>
      </a-table-column>
      <a-table-column title="标签" width="200">
        <template #default="{ record }">
          <template v-if="record.tags?.length">
            <a-tag v-for="tag in record.tags" :key="tag" class="tag-chip">{{ tag }}</a-tag>
          </template>
          <span v-else>—</span>
        </template>
      </a-table-column>
      <a-table-column title="版本" width="100">
        <template #default="{ record }"><code>{{ formatPromptVersion(record.currentVersionNo) }}</code></template>
      </a-table-column>
      <a-table-column title="更新时间" width="170">
        <template #default="{ record }">{{ formatWhen(record.updatedAt) }}</template>
      </a-table-column>
      <a-table-column title="操作" width="280">
        <template #default="{ record }">
          <a-space>
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" @click="openVersions(record)">对比</a-button>
            <a-popconfirm title="确认删除？" @confirm="onDelete(record.id)">
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </a-table-column>
    </a-table>



    <a-modal
      v-model:open="modalOpen"
      :title="editingId ? '编辑提示词' : '新建提示词'"
      :confirm-loading="saving"
      wrap-class-name="prompt-form-modal"
      @ok="onSave"
    >
      <a-form class="form-grid" layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 订单服务通用规则" />
        </a-form-item>
        <a-form-item label="描述">
          <a-input v-model:value="form.description" />
        </a-form-item>
        <a-form-item label="标签">
          <a-select
            v-model:value="form.tags"
            mode="tags"
            :token-separators="[',', '，']"
            :open="false"
            placeholder="回车或逗号添加标签"
          />
        </a-form-item>
        <a-form-item label="用途">
          <a-select v-model:value="form.purpose" :options="purposeOptions" />
          <div class="field-hint">专项审查可选通用规则；Agent 任务使用指导提示词。</div>
        </a-form-item>
        <a-form-item class="span-2" label="正文" required>
          <a-textarea
            v-model:value="form.content"
            :auto-size="{ minRows: 12, maxRows: 28 }"
            show-count
          />
          <div v-if="detailLoading" class="field-hint">正在加载当前正文…此时输入的内容不会被覆盖</div>
        </a-form-item>
        <a-form-item v-if="editingId" class="span-2" label="版本保存方式">
          <a-checkbox v-model:checked="form.createNewVersion">保存为新版本</a-checkbox>
<!--          <div class="field-hint">-->
<!--            勾选会保留当前版本并新增版本；取消勾选会覆盖当前版本正文，不增加版本号。-->
<!--          </div>-->
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="versionOpen"
      :width="'90vw'"
      :footer="null"
      wrap-class-name="prompt-diff-modal"
    >
      <template #title>
        <div class="modal-title-block">
          <div>{{ versionPromptName }} · 版本与对比</div>
<!--          <p>正文版本独立保存；实际调用输入按任务回看</p>-->
        </div>
      </template>
      <a-space style="margin-bottom: 12px">
        <a-select v-model:value="fromVersion" style="width: 160px" :options="versionOptions" placeholder="旧版本" />
        <a-select v-model:value="toVersion" style="width: 160px" :options="versionOptions" placeholder="新版本" />
        <a-button
          type="primary"
          :loading="diffLoading"
          :disabled="!fromVersion || !toVersion || diffLoading || versionLoading"
          @click="loadDiff"
        >
          对比正文
        </a-button>
      </a-space>
      <LoadErrorAlert :message="diffError" @retry="loadDiff" />
      <div class="diff-scroll">
        <a-empty v-if="!diffLines.length" style="margin: 24px 0" description="暂无对比内容" />
        <a-empty v-else-if="!hasAnyChange" style="margin: 24px 0" description="两版内容相同" />
        <DiffViewer v-else :patch="patch" :old-file-name="oldLabel" :new-file-name="newLabel" mode="split" />
      </div>
    </a-modal>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  promptsApi,
  type PromptDiffRow,
  type PromptSummary,
  type PromptVersion
} from '@/api/v2'
import { useListPagination } from '@/utils/useListPagination'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import { hasChanges, toUnifiedPatch } from '@/utils/promptDiff'
import { formatPromptVersion } from '@/utils/promptVersion'

const keyword = ref('')
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref('')
const editingRevision = ref(0)
const detailLoading = ref(false)
const originalContent = ref('')
const form = ref({
  name: '',
  description: '',
  tags: [] as string[],
  purpose: 'SPECIALIST',
  content: '',
  createNewVersion: true
})
const purposeOptions = [
  { value: 'SPECIALIST', label: '专项审查' },
  { value: 'REPORT', label: '报告生成' },
  { value: 'AGENT', label: 'Agent 分析' }
]

const versionOpen = ref(false)
const versionPromptId = ref('')
const versionPromptName = ref('')
const versionOptions = ref<{ value: string; label: string }[]>([])
const fromVersion = ref('')
const toVersion = ref('')
const diffLines = ref<PromptDiffRow[]>([])
const versionLoading = ref(false)
const diffLoading = ref(false)
const diffError = ref('')

const oldLabel = computed(() => `旧版本 ${versionLabel(fromVersion.value)}`.trim())
const newLabel = computed(() => `新版本 ${versionLabel(toVersion.value)}`.trim())
const hasAnyChange = computed(() => hasChanges(diffLines.value))
const patch = computed(() =>
  toUnifiedPatch(diffLines.value, {
    oldName: `prompt-${versionLabel(fromVersion.value) || 'old'}`,
    newName: `prompt-${versionLabel(toVersion.value) || 'new'}`
  })
)

function versionLabel(id: string) {
  return versionOptions.value.find((o) => o.value === id)?.label || ''
}

function purposeLabel(purpose: string) {
  return purposeOptions.find((p) => p.value === purpose)?.label || purpose
}

function formatWhen(value: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

const { items: records, loading, error: loadError, pagination, load, refresh, retry } = useListPagination<PromptSummary>(
  'prompts', params => promptsApi.list({ ...params, q: keyword.value.trim() || undefined }), '提示词列表加载失败', () => keyword.value.trim()
)
function reload() { return load() }

function openCreate() {
  editingId.value = ''
  originalContent.value = ''
  detailLoading.value = false
  form.value = {
    name: '',
    description: '',
    tags: [],
    purpose: 'SPECIALIST',
    content: '',
    createNewVersion: true
  }
  modalOpen.value = true
}

function openEdit(record: PromptSummary) {
  const id = record.id
  editingId.value = id
  editingRevision.value = record.revision
  originalContent.value = ''
  form.value = {
    name: record.name,
    description: '',
    tags: [...(record.tags || [])],
    purpose: record.purpose || 'SPECIALIST',
    content: '',
    createNewVersion: true
  }
  modalOpen.value = true
  detailLoading.value = true
  promptsApi
    .detail(id)
    .then((detail) => {
      // 只回填仍是这条记录且用户还没动过正文的表单，避免慢响应覆盖输入或串到别的记录
      if (editingId.value !== id || form.value.content !== '') return
      form.value.name = detail.name
      form.value.description = detail.description || ''
      form.value.tags = [...(detail.tags || [])]
      form.value.purpose = detail.purpose || 'SPECIALIST'
      form.value.content = detail.currentVersion?.content || ''
      originalContent.value = form.value.content
      editingRevision.value = detail.revision
    })
    .catch((e: any) => {
      if (editingId.value === id) message.error(e?.message || '加载提示词正文失败，请重试')
    })
    .finally(() => {
      if (editingId.value === id) detailLoading.value = false
    })
}

async function onSave() {
  if (saving.value) return
  if (!form.value.name.trim() || !form.value.content.trim()) {
    message.warning('请填写名称与正文')
    return
  }
  saving.value = true
  try {
    const tags = Array.from(new Set(form.value.tags.map((t) => t.trim()).filter(Boolean)))
    if (editingId.value) {
      const updated = await promptsApi.update(editingId.value, {
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
        tags,
        purpose: form.value.purpose,
        expectedRevision: editingRevision.value
      })
      // 元数据已提交；后续正文保存失败时，也必须以此修订号继续重试。
      editingRevision.value = updated.revision
      if (form.value.content !== originalContent.value) {
        const versionRequest: { content: string; expectedRevision: number; createNewVersion?: boolean } = {
          content: form.value.content,
          expectedRevision: editingRevision.value
        }
        if (!form.value.createNewVersion) versionRequest.createNewVersion = false
        const versioned = await promptsApi.addVersion(editingId.value, versionRequest)
        editingRevision.value = versioned.revision
        originalContent.value = versioned.currentVersion?.content || form.value.content
      }
      message.success('保存成功')
    } else {
      await promptsApi.create({
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
        tags,
        purpose: form.value.purpose,
        content: form.value.content
      })
      message.success('创建成功')
    }
    modalOpen.value = false
    await (editingId.value ? refresh() : load())
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDelete(id: string) {
  try {
    await promptsApi.remove(id)
    message.success('删除成功')
    await refresh()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

async function openVersions(record: PromptSummary) {
  versionPromptId.value = record.id
  versionPromptName.value = record.name
  versionOpen.value = true
  versionOptions.value = []
  diffLines.value = []
  diffError.value = ''
  fromVersion.value = ''
  toVersion.value = ''
  versionLoading.value = true
  try {
    const versions: PromptVersion[] = await promptsApi.versions(record.id)
    if (versionPromptId.value !== record.id) return
    versionOptions.value = versions.map((v) => ({ value: v.id, label: formatPromptVersion(v.versionNo) }))
    if (versionOptions.value.length >= 2) {
      fromVersion.value = versionOptions.value[versionOptions.value.length - 2].value
      toVersion.value = versionOptions.value[versionOptions.value.length - 1].value
      await loadDiff()
    }
  } catch (e: any) {
    if (versionPromptId.value === record.id) {
      diffError.value = e?.message || '加载版本列表失败，请重试'
    }
  } finally {
    if (versionPromptId.value === record.id) versionLoading.value = false
  }
}

async function loadDiff() {
  if (!fromVersion.value || !toVersion.value) return
  diffLoading.value = true
  diffError.value = ''
  try {
    diffLines.value = await promptsApi.diff(versionPromptId.value, fromVersion.value, toVersion.value)
  } catch (e: any) {
    diffLines.value = []
    diffError.value = e?.message || '对比失败，请重试'
  } finally {
    diffLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="less">
.diff-scroll {
  max-height: 70vh;
  overflow: auto;
}
.field-hint {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}
</style>

<style lang="less">
.prompt-diff-modal {
  .ant-modal {
    max-width: 1400px;
  }
}
</style>
