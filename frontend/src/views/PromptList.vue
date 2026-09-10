<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-input-search v-model:value="keyword" placeholder="搜索名称/标签/正文" style="width: 240px" @search="load" />
      <a-button type="primary" @click="openCreate">新建提示词</a-button>
    </a-space>

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="描述" data-index="description" />
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

    <a-modal v-model:open="modalOpen" :title="editingId ? '编辑提示词' : '新建提示词'" :confirm-loading="saving" @ok="onSave">
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
          <a-textarea v-model:value="form.content" :rows="8" />
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
        <a-button type="primary" :disabled="!fromVersion || !toVersion" @click="loadDiff">对比</a-button>
      </a-space>
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listPrompts, createPrompt, getPrompt, updatePrompt, updatePromptContent, deletePrompt, diffPrompt } from '@/api/prompt'
import DiffViewer from '@/components/DiffViewer.vue'
import { hasChanges, toUnifiedPatch, type PromptDiffRow } from '@/utils/promptDiff'

const records = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref('')
const form = ref({ name: '', description: '', tags: [] as string[], content: '', createNewVersion: true })

const versionOpen = ref(false)
const versionPromptId = ref('')
const versionOptions = ref<{ value: string; label: string }[]>([])
const fromVersion = ref('')
const toVersion = ref('')
const diffLines = ref<PromptDiffRow[]>([])

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
  try {
    const page = (await listPrompts({ pageNum: 1, pageSize: 100, keyword: keyword.value || undefined })) as any
    records.value = page?.records || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = ''
  form.value = { name: '', description: '', tags: [], content: '', createNewVersion: true }
  modalOpen.value = true
}

function openEdit(record: any) {
  editingId.value = record.id
  form.value = { name: record.name, description: record.description || '', tags: parseTags(record.tags), content: '', createNewVersion: true }
  getPrompt(record.id).then((d: any) => {
    form.value.content = d.currentContent || ''
  })
  modalOpen.value = true
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
  diffLines.value = []
  fromVersion.value = ''
  toVersion.value = ''
  const d = (await getPrompt(record.id)) as any
  versionOptions.value = (d.versions || []).map((v: any) => ({ value: v.id, label: `v${v.versionNo}` }))
  if (versionOptions.value.length >= 2) {
    fromVersion.value = versionOptions.value[versionOptions.value.length - 2].value
    toVersion.value = versionOptions.value[versionOptions.value.length - 1].value
    await loadDiff()
  }
}

async function loadDiff() {
  if (!fromVersion.value || !toVersion.value) return
  diffLines.value = await diffPrompt(versionPromptId.value, fromVersion.value, toVersion.value)
}

onMounted(load)
</script>

<style scoped lang="less">
.diff-scroll {
  max-height: 70vh;
  overflow: auto;
}
</style>

<!-- 弹窗被传送到 body，scoped 样式够不到，故用非 scoped 块给 90vw 加个上限 -->
<style lang="less">
.prompt-diff-modal {
  .ant-modal {
    max-width: 1400px;
  }
}
</style>
