<template>
  <div>
    <LoadErrorAlert :message="loadError" @retry="loadAll" />
    <LoadErrorAlert :message="recordsError" @retry="reloadRecords" />
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
            :scroll="{ x: 'max-content' }"
            :row-selection="{ selectedRowKeys: selectedIds, onChange: onSelect }"
          >
            <a-table-column title="创建时间" data-index="createdAt" width="150" />
            <a-table-column title="分支" data-index="branch" width="100" />
            <a-table-column title="提交" data-index="commitSha" width="90">
              <template #default="{ text }">{{ text ? text.slice(0, 7) : '—' }}</template>
            </a-table-column>
            <a-table-column title="策略" data-index="strategyName" ellipsis />
            <a-table-column title="状态" data-index="status" width="80">
              <template #default="{ text }">{{ statusText(text) }}</template>
            </a-table-column>
            <a-table-column title="操作" width="72">
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
              <a-select v-model:value="modelId" :options="modelOptions" placeholder="选择模型">
                <template #notFoundContent>
                  <div class="not-found">
                    <span>暂无可用模型</span>
                    <router-link to="/models">去创建模型</router-link>
                  </div>
                </template>
              </a-select>
            </a-form-item>
            <a-form-item label="报告提示词（可选）">
              <a-select v-model:value="promptId" :options="promptOptions" placeholder="选择提示词" allow-clear />
            </a-form-item>
            <!-- 原先只把按钮置灰、不说原因，用户不知道还差什么 -->
            <a-tooltip :title="generateDisabledReason">
              <a-button
                type="primary"
                :loading="generating"
                :disabled="!!generateDisabledReason"
                @click="onGenerate"
              >
                生成报告
              </a-button>
            </a-tooltip>
            <!-- 提示被"不再提示"关掉之后，必须留一个能找回来的入口 -->
            <a-button v-if="tipDismissed" type="link" size="small" @click="restoreTip">
              重新开启生成前提示
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

    <!--
      生成前提示（T-02）：不校验、不阻断判断，只把"容易勾错"的两点摆到眼前。
      背景：报告章节（概述/问题/修复方案/设计模式/模块耦合度）全靠提示词一篇生成，
      若所选记录里没有耦合度/设计模式/业务规则的审查结果，对应章节就只能由模型自由发挥；
      而不同代码库的记录混在一起，结论也会互相打架。这两点是"提示"，不是"校验项"。
    -->
    <a-modal
      v-model:open="tipOpen"
      title="生成报告前，请确认勾选的审查记录"
      ok-text="确认生成"
      cancel-text="取消"
      @ok="confirmGenerate"
    >
      <ol class="tip-list">
        <li>所选审查记录涉及的代码<strong>最好是一致的</strong>（此项不校验，仅提醒）。</li>
        <li>
          所选审查记录<strong>应包含耦合度审查、设计模式审查、业务规则审查</strong> ——
          否则提示词里要求的「设计模式」「模块耦合度」章节没有数据支撑，只能由模型自行发挥。
        </li>
      </ol>
      <div class="tip-count">本次已选 {{ selectedIds.length }} 条审查记录</div>
      <a-checkbox v-model:checked="tipMuted">不再提示（可在卡片上重新开启）</a-checkbox>
    </a-modal>

    <a-modal
      v-model:open="reportOpen"
      :title="currentReport?.name || '报告详情'"
      :width="880"
      :footer="null"
    >
      <a-spin :spinning="reportLoading" tip="加载报告…">
        <a-alert v-if="reportError" type="error" show-icon :message="reportError" class="mb8" />
        <template v-if="currentReport">
          <!-- 报告生成失败时给明确说明：原先弹窗里只有空内容 + 一个下载按钮 -->
          <a-alert
            v-if="currentReport.status === 3"
            type="error"
            show-icon
            class="mb8"
            message="该报告生成失败"
            description="可回到左侧重新勾选审核记录并再次生成。"
          />
          <a-space style="margin-bottom: 8px">
            <a-button
              size="small"
              :disabled="!currentReport.contentMarkdown"
              @click="downloadMarkdown"
            >
              下载 Markdown
            </a-button>
          </a-space>
          <!-- 用 Markdown 渲染：原先 <pre> 直出源码，报告里满是 ** 和 # -->
          <MarkdownView v-if="currentReport.contentMarkdown" :text="currentReport.contentMarkdown" />
          <a-empty v-else description="该报告没有正文内容" />
        </template>
      </a-spin>
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
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { listReviews, type ReviewRecordRow } from '@/api/review'
import { listModels } from '@/api/model'
import { listPrompts } from '@/api/prompt'
import { listStrategies } from '@/api/strategy'
import { generateReport, listReports, getReport } from '@/api/report'
import { formatDuration } from '@/utils/duration'
import { useRecordPagination } from '@/utils/useRecordPagination'
import ReviewRecordViewer from '@/components/ReviewRecordViewer.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'

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
/** 报告详情取数期间的 loading 与失败提示（原先两者都没有） */
const reportLoading = ref(false)
const reportError = ref('')
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
  // 失败就抛：错误信息由 useRecordPagination 兜住并暴露到界面（不再"失败即清空列表"）
  return await listReviews(props.projectId, { ...params, statusMin: COMPLETED_STATUS_MIN })
}

const {
  records,
  loading: recordsLoading,
  error: recordsError,
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

/**
 * 「生成报告」被禁用的原因。
 *
 * 原先按钮只是灰着，用户看不出还差什么（是没选模型？还是没勾记录？）。
 */
const generateDisabledReason = computed(() => {
  if (!modelId.value) {
    return '请先选择模型'
  }
  if (!selectedIds.value.length) {
    return '请先在左侧勾选要纳入报告的审查记录'
  }
  return ''
})

/**
 * 生成前提示（T-02）的开关与"不再提示"偏好。
 *
 * 偏好存 localStorage（跨会话有效）。读写都包 try/catch：隐私模式/禁用存储时
 * `localStorage` 会直接抛异常，不能因为一个"记住偏好"把生成流程带崩 ——
 * 存不了就退化成"每次都提示"。
 */
const TIP_DISMISSED_KEY = 'report-generate-tip-dismissed'
const tipOpen = ref(false)
/** 弹窗里那个勾选框的当前状态（只在点「确认生成」时才会被记住） */
const tipMuted = ref(false)
const tipDismissed = ref(readTipDismissed())

function readTipDismissed(): boolean {
  try {
    return window.localStorage.getItem(TIP_DISMISSED_KEY) === '1'
  } catch {
    return false
  }
}

function writeTipDismissed(dismissed: boolean) {
  try {
    if (dismissed) {
      window.localStorage.setItem(TIP_DISMISSED_KEY, '1')
    } else {
      window.localStorage.removeItem(TIP_DISMISSED_KEY)
    }
  } catch {
    // 存储不可用：本次会话内仍然生效（内存里的 tipDismissed 已改），下次进来会重新提示
  }
}

const loadModels = async () => {
  const mp = (await listModels({ pageNum: 1, pageSize: 100 })) as any
  modelOptions.value = (mp?.records || []).map((m: any) => ({ value: m.id, label: m.name }))
}

const loadPrompts = async () => {
  const pp = (await listPrompts({ pageNum: 1, pageSize: 100 })) as any
  promptOptions.value = (pp?.records || []).map((p: any) => ({ value: p.id, label: p.name }))
}

const loadStrategies = async () => {
  const sp = (await listStrategies({ pageNum: 1, pageSize: 100 })) as any
  strategies.value = (sp?.records || []).map((s: any) => ({ value: s.id, label: s.name }))
}

/**
 * 面板级加载：模型/提示词/策略字典 + 报告列表。
 *
 * 这些请求原先各自 `catch {}` 吞掉（注释写着"忽略"），后果是：
 * 模型下拉空 → 「生成报告」按钮常驻 disabled 且**不说明原因**；
 * 策略字典空 → 记录行的策略名显示不出来；报告列表失败则一片空白。
 */
const loadError = ref('')

const loadAll = async () => {
  loadError.value = ''
  const failures: string[] = []
  await Promise.all([
    loadModels().catch(() => failures.push('模型')),
    loadPrompts().catch(() => failures.push('提示词')),
    loadStrategies().catch(() => failures.push('策略')),
    loadReports().catch(() => failures.push('报告列表'))
  ])
  if (failures.length) {
    loadError.value = `以下数据加载失败：${failures.join('、')}，请重试`
  }
}

async function onGenerate() {
  // 已经勾过"不再提示"就直接生成；否则先弹提示，确认后才发请求
  if (tipDismissed.value) {
    await submitGenerate()
    return
  }
  tipMuted.value = false
  tipOpen.value = true
}

/** 弹窗里点「确认生成」：记住偏好（如果勾了），然后真正提交。 */
async function confirmGenerate() {
  if (tipMuted.value) {
    tipDismissed.value = true
    writeTipDismissed(true)
  }
  tipOpen.value = false
  await submitGenerate()
}

/** 「重新开启生成前提示」：把偏好清掉，下一次生成会再弹。 */
function restoreTip() {
  tipDismissed.value = false
  writeTipDismissed(false)
}

async function submitGenerate() {
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
    // 报告是异步任务：这里不盯一眼，列表会一直停在"排队"
    startReportPoll()
  } catch (e: any) {
    message.error(e?.message || '生成失败')
  } finally {
    generating.value = false
  }
}

/** 静默刷新：轮询时不要每 5 秒闪一次表格 loading */
async function loadReports(silent = false) {
  if (!silent) {
    reportsLoading.value = true
  }
  try {
    const page = (await listReports(props.projectId, { pageNum: 1, pageSize: 100 })) as any
    reports.value = page?.records || []
  } finally {
    if (!silent) {
      reportsLoading.value = false
    }
  }
}

/** 报告状态：0 排队 / 1 生成中 */
const PENDING_REPORT_STATUS = [0, 1]
const REPORT_POLL_INTERVAL = 5000
let reportPollTimer: number | undefined

function hasPendingReport() {
  return reports.value.some((r: any) => PENDING_REPORT_STATUS.includes(r.status))
}

function stopReportPoll() {
  if (reportPollTimer) {
    window.clearInterval(reportPollTimer)
    reportPollTimer = undefined
  }
}

/**
 * 生成报告后盯住"排队/生成中"的行。
 *
 * 原先生成完只弹一句"已提交生成"，此后**不再刷新**（ReportPanel 一直在 DOM 里、
 * 切页签也不会重挂载），现场看到的是状态永远停在"排队"，只能手动刷页。
 */
function startReportPoll() {
  stopReportPoll()
  if (!hasPendingReport()) {
    return
  }
  reportPollTimer = window.setInterval(async () => {
    try {
      await loadReports(true)
    } catch {
      // 单次失败不打断轮询：下一周期再试，列表本身有错误态
      return
    }
    if (!hasPendingReport()) {
      stopReportPoll()
    }
  }, REPORT_POLL_INTERVAL)
}

async function openReport(r: any) {
  // 原先无 loading、无 catch：慢请求期间界面毫无反馈，失败则静默打开一个空弹窗
  reportOpen.value = true
  reportLoading.value = true
  reportError.value = ''
  currentReport.value = null
  try {
    currentReport.value = await getReport(r.id)
  } catch (e: any) {
    reportError.value = e?.message || '报告加载失败'
  } finally {
    reportLoading.value = false
  }
}

function downloadMarkdown() {
  const content = currentReport.value?.contentMarkdown || ''
  if (!content) {
    return
  }
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${currentReport.value?.name || 'report'}.md`
  // 必须真挂到 DOM 再点击：游离节点在部分浏览器（Firefox/Safari）不触发下载
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // 立刻 revoke 会让慢磁盘/大文件拿到空内容，延后释放
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

onMounted(loadAll)

onUnmounted(stopReportPoll)

// 供测试驱动轮询（与 ProjectDetail 的进度轮询同理：定时器只能这样验）
defineExpose({ startReportPoll, stopReportPoll })
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
.not-found {
  padding: 4px 0;
  text-align: center;
  color: #8c8c8c;
  a {
    margin-left: 6px;
  }
}
.mb8 {
  margin-bottom: 8px;
}
.tip-list {
  margin: 0 0 8px;
  padding-left: 20px;
}
.tip-count {
  color: #0958d9;
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
