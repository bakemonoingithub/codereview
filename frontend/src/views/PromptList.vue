<template>
  <ListPageLayout title="提示词管理" subtitle="编写业务个性化审查规则；改正文会生成新版本，可对比历史版本">
    <template #actions>
      <a-button type="primary" @click="openCreate">新建提示词</a-button>
    </template>

    <template #filters>
      <a-input-search v-model:value="keyword" placeholder="搜索名称/标签/正文" style="width: 280px" @search="load" />
    </template>

    <LoadErrorAlert :message="loadError" @retry="load" />

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <template #emptyText>
        <EmptyGuide
          :title="keyword ? '没有匹配的提示词' : '还没有提示词'"
          :hint="
            keyword
              ? '换个关键词，或清空搜索看全部'
              : '提示词就是你的业务审查规则；写好后在「审查策略」里绑定即可生效'
          "
          :action-text="keyword ? '' : '新建提示词'"
          @action="openCreate"
        />
      </template>
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="描述" data-index="description" ellipsis />
      <a-table-column title="标签" data-index="tags">
        <template #default="{ text }">
          <template v-if="parseTags(text).length">
            <a-tag v-for="t in parseTags(text)" :key="t">{{ t }}</a-tag>
          </template>
          <span v-else>—</span>
        </template>
      </a-table-column>
      <a-table-column title="更新时间" data-index="updatedAt" />
      <a-table-column title="操作">
        <template #default="{ record }">
          <a-space>
            <a-button size="small" @click="openEdit(record)">编辑</a-button>
            <a-button size="small" @click="openVersions(record)">版本</a-button>
            <a-popconfirm title="确认删除？" @confirm="onDelete(record.id)">
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <a-modal
      v-model:open="modalOpen"
      :width="maximized ? '95vw' : '80vw'"
      :wrap-class-name="maximized ? 'prompt-edit-modal is-maximized' : 'prompt-edit-modal'"
      :confirm-loading="saving"
      @ok="onSave"
    >
      <template #title>
        <div class="modal-title-bar">
          <span>{{ editingId ? '编辑提示词' : '新建提示词' }}</span>
          <a-button type="link" size="small" @click="maximized = !maximized">
            {{ maximized ? '还原' : '最大化' }}
          </a-button>
        </div>
      </template>
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 通用代码审查规则" />
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
        <a-form-item label="正文" required>
          <a-textarea v-model:value="form.content" :auto-size="textareaAutoSize" show-count />
          <div v-if="detailLoading" class="field-hint">正在加载当前正文…此时输入的内容不会被覆盖</div>
        </a-form-item>
        <a-form-item v-if="editingId">
          <a-checkbox v-model:checked="form.createNewVersion">保存为新版本（不勾选则覆盖当前版本）</a-checkbox>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="versionOpen"
      title="版本对比"
      :width="'90vw'"
      wrap-class-name="prompt-diff-modal"
      :footer="null"
    >
      <a-space style="margin-bottom: 12px">
        <a-select v-model:value="fromVersion" style="width: 180px" :options="versionOptions" placeholder="旧版本" />
        <a-select v-model:value="toVersion" style="width: 180px" :options="versionOptions" placeholder="新版本" />
        <a-button
          type="primary"
          :loading="diffLoading"
          :disabled="!fromVersion || !toVersion || diffLoading || versionLoading"
          @click="loadDiff"
        >
          对比
        </a-button>
      </a-space>
      <LoadErrorAlert :message="diffError" @retry="loadDiff" />
      <div class="diff-scroll">
        <a-empty v-if="!diffLines.length" style="margin: 24px 0" description="暂无对比内容" />
        <a-empty v-else-if="!hasAnyChange" style="margin: 24px 0" description="两版内容相同" />
        <DiffViewer
          v-else
          :patch="patch"
          :old-file-name="oldLabel"
          :new-file-name="newLabel"
          mode="split"
        />
      </div>
    </a-modal>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listPrompts, createPrompt, getPrompt, updatePrompt, updatePromptContent, deletePrompt, diffPrompt } from '@/api/prompt'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import { hasChanges, toUnifiedPatch, type PromptDiffRow } from '@/utils/promptDiff'

const records = ref<any[]>([])
const loading = ref(false)
// 原先 load 没有 catch：请求失败时表格永远空着，且不给任何提示与重试入口
const loadError = ref('')
const keyword = ref('')
const modalOpen = ref(false)
const saving = ref(false)
const maximized = ref(false)
const editingId = ref('')
const detailLoading = ref(false)
const form = ref({ name: '', description: '', tags: [] as string[], content: '', createNewVersion: true })
const textareaAutoSize = computed(() => ({
  minRows: maximized.value ? 20 : 12,
  maxRows: maximized.value ? 40 : 30
}))

const versionOpen = ref(false)
const versionPromptId = ref('')
const versionOptions = ref<{ value: string; label: string }[]>([])
const fromVersion = ref('')
const toVersion = ref('')
const diffLines = ref<PromptDiffRow[]>([])
const versionLoading = ref(false)
const diffLoading = ref(false)
/** 版本列表/对比失败的提示（原先失败被伪装成"暂无对比内容"） */
const diffError = ref('')

const oldLabel = computed(() => `旧版本 ${versionLabel(fromVersion.value)}`.trim())
const newLabel = computed(() => `新版本 ${versionLabel(toVersion.value)}`.trim())
const hasAnyChange = computed(() => hasChanges(diffLines.value))

/** patch 头用标识符风格的名字；人类可读的展示名由 oldLabel/newLabel 交给组件当列头 */
const patch = computed(() =>
  toUnifiedPatch(diffLines.value, {
    oldName: `prompt-${versionLabel(fromVersion.value) || 'old'}`,
    newName: `prompt-${versionLabel(toVersion.value) || 'new'}`
  })
)

function versionLabel(id: string) {
  return versionOptions.value.find((o) => o.value === id)?.label || ''
}

function parseTags(tags: string): string[] {
  try {
    const arr = JSON.parse(tags || '[]')
    return Array.isArray(arr) ? arr.filter((x) => typeof x === 'string') : []
  } catch {
    return []
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const page = (await listPrompts({ pageNum: 1, pageSize: 100, keyword: keyword.value || undefined })) as any
    records.value = page?.records || []
  } catch (e: any) {
    loadError.value = e?.message || '提示词列表加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = ''
  maximized.value = false
  form.value = { name: '', description: '', tags: [], content: '', createNewVersion: true }
  modalOpen.value = true
}

function openEdit(record: any) {
  const id = record.id
  const blank = ''
  editingId.value = id
  maximized.value = false
  form.value = { name: record.name, description: record.description || '', tags: parseTags(record.tags), content: blank, createNewVersion: true }
  modalOpen.value = true
  detailLoading.value = true
  getPrompt(id)
    .then((d: any) => {
      // 只回填**仍是这条记录**且**用户还没动过正文**的表单：
      // ① 慢响应期间用户可能已切到别的记录或点了"新建"（editingId 变了）—— 写进去会把另一条记录的正文搞坏；
      // ② 用户可能已经开始输入 —— 覆盖就是静默丢数据（原先就是无条件回填，`|| ''` 连空响应也会清空）。
      if (editingId.value !== id) return
      if (form.value.content !== blank) return
      form.value.content = d.currentContent || ''
    })
    .catch((e: any) => {
      if (editingId.value !== id) return
      // 原先没有 catch：失败时正文永久空着，用户点保存只会被告知"请填写名称与正文"
      message.error(e?.message || '加载提示词正文失败，请重试')
    })
    .finally(() => {
      if (editingId.value === id) detailLoading.value = false
    })
}

async function onSave() {
  if (!form.value.name || !form.value.content) {
    message.warning('请填写名称与正文')
    return
  }
  saving.value = true
  try {
    const tags = Array.from(new Set(form.value.tags.map((t) => t.trim()).filter(Boolean)))
    if (editingId.value) {
      await updatePrompt(editingId.value, { name: form.value.name, description: form.value.description, tags })
      await updatePromptContent(editingId.value, { content: form.value.content, createNewVersion: form.value.createNewVersion })
    } else {
      await createPrompt({ name: form.value.name, description: form.value.description, tags, content: form.value.content })
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

async function onDelete(id: string) {
  try {
    await deletePrompt(id)
    message.success('删除成功')
    await load()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

async function openVersions(record: any) {
  versionPromptId.value = record.id
  versionOpen.value = true
  // 先清空上一次的选项与结果：失败时若残留上一条提示词的版本，
  // 用户点「对比」会带着"新记录的 id + 旧记录的版本 id"发出**跨提示词**的请求，结果毫无意义
  versionOptions.value = []
  diffLines.value = []
  diffError.value = ''
  fromVersion.value = ''
  toVersion.value = ''
  versionLoading.value = true
  try {
    const d = (await getPrompt(record.id)) as any
    if (versionPromptId.value !== record.id) return // 期间切到了别的提示词/关了弹窗
    versionOptions.value = (d.versions || []).map((v: any) => ({ value: v.id, label: `v${v.versionNo}` }))
    if (versionOptions.value.length >= 2) {
      fromVersion.value = versionOptions.value[versionOptions.value.length - 2].value
      toVersion.value = versionOptions.value[versionOptions.value.length - 1].value
      await loadDiff()
    }
  } catch (e: any) {
    if (versionPromptId.value !== record.id) return
    diffError.value = e?.message || '加载版本列表失败，请重试'
  } finally {
    if (versionPromptId.value === record.id) versionLoading.value = false
  }
}

async function loadDiff() {
  if (!fromVersion.value || !toVersion.value) return
  diffLoading.value = true
  diffError.value = ''
  try {
    diffLines.value = await diffPrompt(versionPromptId.value, fromVersion.value, toVersion.value)
  } catch (e: any) {
    // 失败不能伪装成"暂无对比内容"，也不能把上一对版本的 diff 留在屏幕上冒充结果
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
.modal-title-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 24px;
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>

<!-- 弹窗被传送到 body，scoped 样式够不到，故用非 scoped 块给 90vw 加个上限 -->
<style lang="less">
.prompt-diff-modal {
  .ant-modal {
    max-width: 1400px;
  }
}
.prompt-edit-modal {
  .ant-modal {
    max-width: 1200px;
  }
  .ant-modal-body {
    max-height: 70vh;
    overflow: auto;
  }
  &.is-maximized .ant-modal {
    max-width: none;
  }
}
</style>
