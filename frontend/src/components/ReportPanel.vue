<template>
  <div>
    <a-row :gutter="16">
      <a-col :span="12">
        <a-card title="选择审查记录（已完成）" size="small">
          <div class="picker-toolbar">
            <span class="picked">已选 {{ selectedIds.length }} 条</span>
            <a-button v-if="selectedIds.length" size="small" type="link" @click="clearSelection">清空</a-button>
          </div>
          <a-table
            :data-source="records"
            row-key="id"
            :loading="recordsLoading"
            :pagination="pagination"
            size="small"
            :row-selection="{ selectedRowKeys: selectedIds, onChange: onSelect }"
          >
            <a-table-column title="创建时间" data-index="createdAt" width="170" />
            <a-table-column title="分支" data-index="branch" width="100" />
            <a-table-column title="提交" data-index="commitSha" width="90">
              <template #default="{ text }">{{ text ? text.slice(0, 7) : '—' }}</template>
            </a-table-column>
            <a-table-column title="策略" data-index="strategyName" ellipsis />
            <a-table-column title="状态" data-index="status" width="90">
              <template #default="{ text }">{{ statusText(text) }}</template>
            </a-table-column>
            <a-table-column title="操作" width="80">
              <template #default="{ record }">
                <a-button size="small" @click="viewRecord(record)">查看</a-button>
              </template>
            </a-table-column>
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
        <a-table-column title="耗时" width="110">
          <template #default="{ record }">{{ formatDuration(record.startedAt, record.finishedAt) }}</template>
        </a-table-column>
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

    <!-- 只读查看审查记录：与「审查记录」页签用的是同一个弹窗组件 -->
    <ReviewRecordViewer
      v-model:open="recordViewerOpen"
      :record-id="viewerRecordId"
      :project-id="projectId"
      :row="viewerRow"
      :strategies="strategies"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { listReviews, type ReviewRecordRow } from '@/api/review'
import { listModels } from '@/api/model'
import { listPrompts } from '@/api/prompt'
import { listStrategies } from '@/api/strategy'
import { generateReport, listReports, getReport } from '@/api/report'
import { formatDuration } from '@/utils/duration'
import { useRecordPagination } from '@/utils/useRecordPagination'
import ReviewRecordViewer from '@/components/ReviewRecordViewer.vue'

const props = defineProps<{ projectId: string }>()

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
/** 查看弹窗只认 id：完整记录由弹窗自己按 id 拉（列表行不带 resultJson） */
const recordViewerOpen = ref(false)
const viewerRecordId = ref('')
const viewerRow = ref<ReviewRecordRow | null>(null)
/** 策略名由后端 join 在列表行上；详情接口没有这个字段，弹窗取数期间靠行数据兜底 */
const strategies = ref<{ value: string; label: string }[]>([])

/** 只要已完成的记录：未完成的还没有 resultJson，勾进报告也生成不出内容 */
const COMPLETED_STATUS_MIN = 2

/**
 * 记录选择器：服务端分页 + 状态过滤。
 *
 * 过滤条件必须下推到服务端 —— 若仍在当前页内 `filter(status>=2)`，
 * total 会把未完成记录也算进去，于是会出现"整页只剩一两条"甚至空页。
 */
async function loadRecordsPage(params: { pageNum: number; pageSize: number }) {
  try {
    return await listReviews(props.projectId, { ...params, statusMin: COMPLETED_STATUS_MIN })
  } catch {
    return null
  }
}

const {
  records,
  loading: recordsLoading,
  pagination,
  fetchPage: reloadRecords
} = useRecordPagination<ReviewRecordRow>({ loader: loadRecordsPage })

function statusText(s: number) {
  return s === 2 ? '成功' : s === 3 ? '失败' : s === 4 ? '部分成功' : '执行中'
}
function reportStatusText(s: number) {
  return s === 0 ? '排队' : s === 1 ? '生成中' : s === 2 ? '成功' : '失败'
}

function viewRecord(row: ReviewRecordRow) {
  viewerRow.value = row
  viewerRecordId.value = row.id
  recordViewerOpen.value = true
}

function onSelect(keys: any[]) {
  // 跨页保留：分页只换了当前页数据，已勾选的记录不会因为翻页被丢掉
  selectedIds.value = keys as string[]
}

function clearSelection() {
  selectedIds.value = []
}

const loadAll = async () => {
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
  try {
    const sp = (await listStrategies({ pageNum: 1, pageSize: 100 })) as any
    strategies.value = (sp?.records || []).map((s: any) => ({ value: s.id, label: s.name }))
  } catch {
    // 忽略
  }
  reportsLoading.value = true
  try {
    const rp2 = (await listReports(props.projectId, { pageNum: 1, pageSize: 100 })) as any
    reports.value = rp2?.records || []
  } finally {
    reportsLoading.value = false
  }
}

async function onGenerate() {
  generating.value = true
  try {
    await generateReport(props.projectId, {
      name: reportName.value || undefined,
      modelConfigId: modelId.value,
      recordIds: selectedIds.value,
      promptId: promptId.value
    })
    message.success('已提交生成')
    await Promise.all([reloadRecords(), loadReports()])
  } catch (e: any) {
    message.error(e?.message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function loadReports() {
  reportsLoading.value = true
  try {
    const page = (await listReports(props.projectId, { pageNum: 1, pageSize: 100 })) as any
    reports.value = page?.records || []
  } finally {
    reportsLoading.value = false
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

onMounted(loadAll)
</script>

<style scoped lang="less">
.picker-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.picked {
  color: #0958d9;
  font-size: 12px;
}
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
