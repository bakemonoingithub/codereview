<template>
  <div>
    <div class="page-head">
      <div>
        <h2>报告</h2>
        <p class="muted">勾选已完成的审查结果，独立生成可追溯的多结果报告。</p>
      </div>
      <a-button type="primary" @click="router.push('/reports/new')">＋ 生成报告</a-button>
    </div>

    <a-alert v-if="error" type="error" show-icon :message="error" class="mb16">
      <template #action><a-button size="small" @click="retry">重试</a-button></template>
    </a-alert>

    <a-card>
      <div class="section-head">
        <strong>报告记录</strong>
        <span class="spacer" />
        <span class="muted small">正文、来源和下载使用同一份冻结内容</span>
        <a-button size="small" :loading="loading" @click="refresh">刷新</a-button>
      </div>

      <a-table
        :data-source="reports"
        row-key="id"
        :loading="loading"
        :pagination="pagination"
        size="middle"
        :scroll="{ x: 'max-content' }"
      >
        <a-table-column title="报告名称" key="title" width="360">
          <template #default="{ record }">
            <div class="title-cell">
              <a @click="open(record.id)">{{ record.title }}</a>
              <span class="muted small">
                {{ record.modelName || '未记录模型' }} · {{ shortId(record.id) }}
              </span>
            </div>
          </template>
        </a-table-column>
        <a-table-column title="选取材料" key="sources" width="250">
          <template #default="{ record }">
            <div>{{ record.sourceCount }} 个审查结果</div>
            <span class="muted small">
              {{ record.taskCount }} 个任务 · {{ record.versionCount }} 个版本
            </span>
          </template>
        </a-table-column>
        <a-table-column title="类型" key="kinds" width="190">
          <template #default="{ record }">
            <a-space wrap>
              <a-tag v-for="kind in kindEntries(record.kindCounts)" :key="kind.key">
                {{ kind.label }} {{ kind.count }}
              </a-tag>
              <span v-if="!kindEntries(record.kindCounts).length" class="muted small">—</span>
            </a-space>
          </template>
        </a-table-column>
        <a-table-column title="状态" key="state" width="150">
          <template #default="{ record }">
            <a-tag :color="stateColor(record.state)">{{ stateText(record.state) }}</a-tag>
            <div v-if="record.errorCode" class="error small">{{ record.errorCode }}</div>
          </template>
        </a-table-column>
        <a-table-column title="时间" key="time" width="170">
          <template #default="{ record }">{{ formatTime(record.updatedAt || record.createdAt) }}</template>
        </a-table-column>
        <a-table-column title="操作" key="actions" width="120">
          <template #default="{ record }">
            <a-button size="small" type="link" @click="open(record.id)">查看</a-button>
          </template>
        </a-table-column>
        <template #emptyText>
          <a-empty description="还没有报告">
            <a-button type="primary" @click="router.push('/reports/new')">生成第一份报告</a-button>
          </a-empty>
        </template>
      </a-table>

    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { reportsApi, type ReportSummary } from '@/api/v2'
import { useListPagination } from '@/utils/useListPagination'

const router = useRouter()
const { items: reports, loading, error, pagination, pageNum, pageSize, refresh, retry, fetchPage } =
  useListPagination<ReportSummary>('reports', params => reportsApi.list(params), '报告列表读取失败')

onMounted(() => {
  let page = 1
  try {
    const scene = JSON.parse(sessionStorage.getItem('report-list-page') || 'null')
    if (scene?.pageSize === pageSize.value && Number.isSafeInteger(scene.page) && scene.page > 0) page = scene.page
  } catch { /* 从第一页查询。 */ }
  return fetchPage(page)
})
onBeforeUnmount(() => {
  try { sessionStorage.setItem('report-list-page', JSON.stringify({ page: pageNum.value, pageSize: pageSize.value })) }
  catch { /* 不影响离页。 */ }
})

function open(id: string) {
  router.push(`/reports/${id}`)
}

function shortId(id: string) {
  return id.length <= 10 ? id : id.slice(-10)
}

function kindEntries(counts: Record<string, number>) {
  return Object.entries(counts || {}).map(([key, count]) => ({
    key,
    count,
    label: ({ ISSUE: '问题', PATTERN: '模式', COUPLING: '耦合', AGENT: 'Agent' } as Record<string, string>)[key] || key
  }))
}

function stateText(state: string) {
  return ({
    INPUT_FROZEN: '待生成',
    QUEUED: '排队中',
    RUNNING: '生成中',
    SUCCEEDED: '已完成',
    FAILED: '生成失败'
  } as Record<string, string>)[state] || state
}

function stateColor(state: string) {
  return ({
    INPUT_FROZEN: 'blue',
    QUEUED: 'gold',
    RUNNING: 'processing',
    SUCCEEDED: 'green',
    FAILED: 'red'
  } as Record<string, string>)[state] || 'default'
}

function formatTime(value: string) {
  if (!value) return '—'
  const normalized = /[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<style scoped lang="less">
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-head h2 {
  margin: 0 0 4px;
}
.mb16 {
  margin-bottom: 16px;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.spacer {
  flex: 1;
}
.muted {
  color: #69798d;
}
.small {
  font-size: 12px;
}
.title-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.error {
  color: #b42318;
}
</style>
