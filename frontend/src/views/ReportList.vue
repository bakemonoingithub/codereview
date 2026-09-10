<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-select v-model:value="projectId" style="width: 280px" placeholder="选择项目" :options="projectOptions" @change="loadAll" />
    </a-space>

    <a-row v-if="projectId" :gutter="16">
      <a-col :span="12">
        <a-card title="选择审查记录（已完成）" size="small">
          <a-table
            :data-source="records"
            row-key="id"
            :loading="recordsLoading"
            :pagination="false"
            size="small"
            :row-selection="{ selectedRowKeys: selectedIds, onChange: onSelect }"
          >
            <a-table-column title="ID" data-index="id" width="120" />
            <a-table-column title="状态" data-index="status">
              <template #default="{ text }">{{ statusText(text) }}</template>
            </a-table-column>
            <a-table-column title="创建时间" data-index="createdAt" />
          </a-table>
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card title="生成报告" size="small">
          <a-form layout="vertical">
            <a-form-item label="报告名称">
              <a-input v-model:value="reportName" placeholder="综合报告" />
            </a-form-item>
            <a-form-item label="模型" required>
              <a-select v-model:value="modelId" :options="modelOptions" placeholder="选择模型" />
            </a-form-item>
            <a-form-item label="报告提示词（可选）">
              <a-select v-model:value="promptId" :options="promptOptions" placeholder="选择提示词" allow-clear />
            </a-form-item>
            <a-button type="primary" :loading="generating" :disabled="!modelId || !selectedIds.length" @click="onGenerate">
              生成报告
            </a-button>
          </a-form>
        </a-card>
      </a-col>
    </a-row>

    <a-card title="报告列表" size="small" style="margin-top: 16px">
      <a-table :data-source="reports" row-key="id" :loading="reportsLoading" :pagination="false">
        <a-table-column title="名称" data-index="name" />
        <a-table-column title="状态" data-index="status">
          <template #default="{ text }">{{ reportStatusText(text) }}</template>
        </a-table-column>
        <a-table-column title="创建时间" data-index="createdAt" />
        <a-table-column title="操作">
          <template #default="{ record }">
            <a-button size="small" @click="openReport(record)">查看</a-button>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <a-modal v-model:open="reportOpen" :title="currentReport?.name" :width="720" :footer="null">
      <a-space style="margin-bottom: 8px">
        <a-button size="small" @click="downloadMarkdown">下载 Markdown</a-button>
      </a-space>
      <pre class="markdown">{{ currentReport?.contentMarkdown }}</pre>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listProjects } from '@/api/project'
import { listReviews } from '@/api/review'
import { listModels } from '@/api/model'
import { listPrompts } from '@/api/prompt'
import { generateReport, listReports, getReport } from '@/api/report'

const projectId = ref('')
const projectOptions = ref<{ value: string; label: string }[]>([])
const records = ref<any[]>([])
const recordsLoading = ref(false)
const selectedIds = ref<string[]>([])
const modelId = ref('')
const modelOptions = ref<{ value: string; label: string }[]>([])
const promptId = ref<string | undefined>(undefined)
const promptOptions = ref<{ value: string; label: string }[]>([])
const reportName = ref('')
const generating = ref(false)
const reports = ref<any[]>([])
const reportsLoading = ref(false)
const reportOpen = ref(false)
const currentReport = ref<any>(null)

function statusText(s: number) {
  return s === 2 ? '成功' : s === 3 ? '失败' : s === 4 ? '部分成功' : '执行中'
}
function reportStatusText(s: number) {
  return s === 0 ? '排队' : s === 1 ? '生成中' : s === 2 ? '成功' : '失败'
}

async function loadProjects() {
  const page = (await listProjects({ pageNum: 1, pageSize: 100 })) as any
  projectOptions.value = (page?.records || []).map((p: any) => ({ value: p.id, label: p.name }))
}

async function loadAll() {
  if (!projectId.value) return
  recordsLoading.value = true
  try {
    const rp = (await listReviews(projectId.value, { pageNum: 1, pageSize: 100 })) as any
    records.value = (rp?.records || []).filter((r: any) => r.status >= 2)
  } finally {
    recordsLoading.value = false
  }
  try {
    const mp = (await listModels({ pageNum: 1, pageSize: 100 })) as any
    modelOptions.value = (mp?.records || []).map((m: any) => ({ value: m.id, label: m.name }))
  } catch {
    // 忽略
  }
  try {
    const pp = (await listPrompts({ pageNum: 1, pageSize: 100 })) as any
    promptOptions.value = (pp?.records || []).map((p: any) => ({ value: p.id, label: p.name }))
  } catch {
    // 忽略
  }
  reportsLoading.value = true
  try {
    const rp2 = (await listReports(projectId.value, { pageNum: 1, pageSize: 100 })) as any
    reports.value = rp2?.records || []
  } finally {
    reportsLoading.value = false
  }
}

function onSelect(keys: any[]) {
  selectedIds.value = keys
}

async function onGenerate() {
  generating.value = true
  try {
    await generateReport(projectId.value, {
      name: reportName.value || undefined,
      modelConfigId: modelId.value,
      recordIds: selectedIds.value,
      promptId: promptId.value
    })
    message.success('已提交生成')
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function openReport(r: any) {
  currentReport.value = await getReport(r.id)
  reportOpen.value = true
}

function downloadMarkdown() {
  const content = currentReport.value?.contentMarkdown || ''
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${currentReport.value?.name || 'report'}.md`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(loadProjects)
</script>

<style scoped lang="less">
.markdown {
  white-space: pre-wrap;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  padding: 12px;
  max-height: 60vh;
  overflow: auto;
}
</style>
