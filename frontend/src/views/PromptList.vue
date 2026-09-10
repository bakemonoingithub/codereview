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

    <a-modal v-model:open="versionOpen" title="版本对比" :width="1100" :footer="null">
      <a-space style="margin-bottom: 12px">
        <a-select v-model:value="fromVersion" style="width: 180px" :options="versionOptions" placeholder="旧版本" />
        <a-select v-model:value="toVersion" style="width: 180px" :options="versionOptions" placeholder="新版本" />
        <a-button type="primary" :disabled="!fromVersion || !toVersion" @click="loadDiff">对比</a-button>
      </a-space>
      <div class="diff-header">
        <div class="diff-col">旧版本 {{ versionLabel(fromVersion) }}</div>
        <div class="diff-col">新版本 {{ versionLabel(toVersion) }}</div>
      </div>
      <div class="diff-scroll">
        <a-empty v-if="!diffRows.length" style="margin: 24px 0" description="暂无对比内容" />
        <div v-for="(row, i) in diffRows" :key="i" class="diff-row">
          <div :class="['diff-cell', row.type === 'remove' ? 'remove' : '']">
            <span class="diff-ln">{{ row.left?.line ?? '' }}</span>
            <span class="diff-txt">{{ row.left?.text ?? '' }}</span>
          </div>
          <div :class="['diff-cell', row.type === 'add' ? 'add' : '']">
            <span class="diff-ln">{{ row.right?.line ?? '' }}</span>
            <span class="diff-txt">{{ row.right?.text ?? '' }}</span>
          </div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listPrompts, createPrompt, getPrompt, updatePrompt, updatePromptContent, deletePrompt, diffPrompt } from '@/api/prompt'

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
const diffLines = ref<any[]>([])

const diffRows = computed(() =>
  diffLines.value.map((l: any) => {
    if (l.type === 'same') {
      return { left: { line: l.oldLine, text: l.text }, right: { line: l.newLine, text: l.text }, type: 'same' }
    }
    if (l.type === 'remove') {
      return { left: { line: l.oldLine, text: l.text }, right: null, type: 'remove' }
    }
    return { left: null, right: { line: l.newLine, text: l.text }, type: 'add' }
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
.diff-header {
  display: flex;
  border: 1px solid #f0f0f0;
  border-bottom: none;
  background: #fafafa;
  font-weight: 600;
  padding: 8px 0;
}
.diff-col {
  flex: 1;
  padding: 0 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.diff-scroll {
  max-height: 60vh;
  overflow: auto;
  border: 1px solid #f0f0f0;
  font-family: monospace;
  font-size: 12px;
}
.diff-row {
  display: flex;
  min-height: 20px;
}
.diff-cell {
  flex: 1;
  display: flex;
  white-space: pre-wrap;
  padding: 1px 4px;
  min-width: 0;
}
.diff-cell + .diff-cell {
  border-left: 1px solid #f0f0f0;
}
.diff-cell.remove {
  background: #ffebe6;
}
.diff-cell.add {
  background: #e6ffed;
}
.diff-ln {
  flex: none;
  width: 48px;
  min-width: 48px;
  color: #999;
  text-align: right;
  padding-right: 8px;
  user-select: none;
}
.diff-txt {
  flex: 1;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
