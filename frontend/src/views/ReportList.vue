<template>
  <ListPageLayout title="报告" subtitle="将多个审查结果整理成独立报告，保留各项来源和版本。">
    <template #actions>
      <a-button type="primary" @click="router.push('/reports/new')">＋ 生成报告</a-button>
    </template>

    <template #notice>
      <LoadErrorAlert :message="error" @retry="retry" />
    </template>

    <template #filters>
      <a-input-search
        v-model:value="keyword"
        class="filter-search"
        placeholder="按报告名称或 ID 搜索"
        allow-clear
        @search="searchReports"
      />
      <a-select
        v-model:value="stateFilter"
        allow-clear
        placeholder="全部状态"
        style="width: 160px"
        :options="stateOptions"
        @change="searchReports"
      />
    </template>
    <template #filters-extra>
      <a-button :loading="loading" @click="refresh">刷新</a-button>
    </template>

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
<!--            <span class="muted small">-->
<!--              {{ record.modelName || '未记录模型' }} · {{ shortId(record.id) }}-->
<!--            </span>-->
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
          <a-button size="small" type="link" @click="open(record.id)">查看详情 →</a-button>
        </template>
      </a-table-column>
      <template #emptyText>
        <EmptyGuide
          :title="hasFilters ? '没有匹配的报告' : '还没有报告'"
          :hint="hasFilters ? '调整或清除筛选条件，查看全部报告' : '将审查结果整理成独立报告。'"
          :action-text="hasFilters ? '' : '生成第一份报告'"
          @action="router.push('/reports/new')"
        />
      </template>
    </a-table>
    <div class="table-footer">
      <span>按创建时间排序</span>
    </div>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { reportsApi, type ReportSummary } from '@/api/v2'
import { useListPagination } from '@/utils/useListPagination'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'

const router = useRouter()
const keyword = ref('')
const stateFilter = ref<string | undefined>()
const stateOptions = [
  { value: 'INPUT_FROZEN', label: '待生成' },
  { value: 'QUEUED', label: '排队中' },
  { value: 'RUNNING', label: '生成中' },
  { value: 'SUCCEEDED', label: '已完成' },
  { value: 'FAILED', label: '生成失败' }
]
const hasFilters = computed(() => !!(keyword.value.trim() || stateFilter.value))
const { items: reports, loading, error, pagination, pageNum, pageSize, refresh, retry, fetchPage } =
  useListPagination<ReportSummary>(
    'reports',
    params => reportsApi.list({
      ...params,
      ...(keyword.value.trim() ? { q: keyword.value.trim() } : {}),
      ...(stateFilter.value ? { state: stateFilter.value } : {})
    }),
    '报告列表读取失败',
    () => JSON.stringify([keyword.value.trim(), stateFilter.value || ''])
  )

function searchReports() {
  return fetchPage(1)
}

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
.muted {
  color: var(--muted);
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
  color: var(--red);
}
</style>
