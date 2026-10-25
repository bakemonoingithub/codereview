<template>
  <ListPageLayout
    title="审查中心"
    subtitle="查看专项审查与 Agent 调查，将完整审查结果整理成报告。"
  >
    <template #actions>
      <a-button @click="router.push('/reports/new')">生成报告</a-button>
      <a-button @click="openReviewLaunch()">发起专项审查</a-button>
      <a-button type="primary" @click="agentLaunchOpen = true">＋ 发起 Agent 分析</a-button>
    </template>

    <template #notice>
      <PageNotice v-if="created" closable @close="created = ''">
        新任务已受理。任务正在后台逐文件执行，可稍后刷新查看状态。
      </PageNotice>
      <LoadErrorAlert :message="error" @retry="retry" />
    </template>

    <template #filters>
      <a-input
        v-model:value="search"
        class="filter-search"
        allow-clear
        placeholder="按任务名称或 ID 搜索"
        @change="load"
      />
      <a-select
        v-model:value="filterProject"
        allow-clear
        placeholder="全部项目"
        style="width: 200px"
        :options="projectOptions"
        @change="load"
      />
      <a-select
        v-model:value="filterType"
        allow-clear
        placeholder="全部类型"
        style="width: 160px"
        :options="typeOptions"
        @change="load"
      />
      <a-select
        v-model:value="filterState"
        allow-clear
        placeholder="全部状态"
        style="width: 150px"
        :options="stateOptions"
        @change="load"
      />
    </template>
    <template #filters-extra>
      <a-button :loading="loading" @click="refresh">刷新</a-button>
    </template>

    <a-table
      :data-source="tasks"
      row-key="id"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 'max-content' }"
    >
      <a-table-column title="任务" key="task">
        <template #default="{ record }">
          <div class="task-cell">
            <strong><a :title="taskLabel(record.name, record.id)" @click="openTask(record.id)">{{ taskLabel(record.name, record.id) }}</a></strong>
            <span class="cell-sub" :title="record.id">{{ record.id }}</span>
            <span class="cell-sub">{{ projectName(record.projectId) }}</span>
          </div>
        </template>
      </a-table-column>
      <a-table-column title="类型 / 范围" key="type" width="180">
        <template #default="{ record }">
          {{ typeText(record) }}
          <span class="cell-sub">{{ record.targetCount }} 个目标</span>
        </template>
      </a-table-column>
      <a-table-column title="状态" key="state" width="160">
        <template #default="{ record }">
          <StatusBadge :tone="stateTone(record.state)">{{ stateText(record.state) }}</StatusBadge>
          <span class="cell-sub">
            {{ record.succeededCount }} / {{ record.targetCount }} 完成
            <template v-if="record.failedCount"> · 失败 {{ record.failedCount }}</template>
          </span>
        </template>
      </a-table-column>
      <a-table-column title="发起时间" key="createdAt" width="170">
        <template #default="{ record }">{{ formatTime(record.createdAt) }}</template>
      </a-table-column>
      <a-table-column title="操作" key="actions" width="200">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openTask(record.id)">查看详情 →</a-button>
          <a-button type="link" size="small" @click="copyTask(record)">复制参数</a-button>
        </template>
      </a-table-column>
      <template #emptyText>
        <EmptyGuide
          title="还没有审查任务"
          action-text="发起第一个审查"
          @action="openReviewLaunch()"
        />
        <a-button v-if="hasFilters" type="link" @click="resetFilters">重置筛选</a-button>
      </template>
    </a-table>
    <div class="table-footer">
      <span>按发起时间排序</span>
    </div>
    <ReviewLaunch v-if="reviewLaunchOpen" />
    <AgentLaunch v-model:open="agentLaunchOpen" />
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectsApi, tasksApi, type ProjectView, type TaskSummary } from '@/api/v2'
import { useListPagination } from '@/utils/useListPagination'
import { taskLabel } from '@/utils/taskLabel'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import PageNotice from '@/components/PageNotice.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import AgentLaunch from '@/views/AgentLaunch.vue'
import ReviewLaunch from '@/views/ReviewLaunch.vue'

const route = useRoute()
const router = useRouter()
const projects = ref<ProjectView[]>([])
let loadedFilterKey = ''
let disposed = false
const filterKey = () => JSON.stringify([filterProject.value, filterType.value, filterState.value, search.value])
const created = ref(String(route.query.created || ''))
const filterProject = ref<string | undefined>()
const filterType = ref<string | undefined>()
const filterState = ref<string | undefined>()
const search = ref('')
const agentLaunchOpen = ref(false)
const reviewLaunchOpen = computed(() => route.query.launchReview === '1')

function openReviewLaunch(copyFrom?: string) {
  router.push({ path: '/reviews', query: { ...route.query, launchReview: '1', ...(copyFrom ? { copyFrom } : {}) } })
}
const hasFilters = computed(
  () => !!(filterProject.value || filterType.value || filterState.value || search.value)
)

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: project.name }))
)
const typeOptions = [
  { value: 'ISSUE', label: '问题审查' },
  { value: 'PATTERN', label: '设计模式' },
  { value: 'COUPLING', label: '耦合度' },
  { value: 'AGENT', label: 'Agent 分析' }
]
const stateOptions = [
  { value: 'QUEUED', label: '排队中' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCEEDED', label: '已完成' },
  { value: 'PARTIAL', label: '部分完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' }
]

const list = useListPagination<TaskSummary>('reviews', params => tasksApi.list({
  ...params,
  projectId: filterProject.value,
  type: filterType.value === 'AGENT' ? undefined : filterType.value,
  route: filterType.value === 'AGENT' ? 'AGENT' : undefined,
  state: filterState.value,
  q: search.value.trim() || undefined
}), '任务列表加载失败', filterKey)
const { items: tasks, loading, error, pageNum, pageSize, retry } = list
const pagination = computed(() => ({ ...list.pagination.value, onChange: changePage }))

onMounted(async () => {
  let restorePage = 1
  let scrollY = 0
  try {
    const saved = sessionStorage.getItem('review-center-filters')
    if (saved) {
      const filters = JSON.parse(saved)
      filterProject.value = typeof filters.project === 'string' ? filters.project : undefined
      filterType.value = typeof filters.type === 'string' ? filters.type : undefined
      filterState.value = typeof filters.state === 'string' ? filters.state : undefined
      search.value = typeof filters.q === 'string' ? filters.q : ''
      if (filters.pageSize === pageSize.value && Number.isSafeInteger(filters.page) && filters.page > 0) {
        restorePage = filters.page
        scrollY = Math.max(0, Number(filters.scrollY) || 0)
      }
    }
  } catch { /* 存储不可用或旧现场不合法时从第一页查询。 */ }
  const restorationKey = filterKey()
  void projectsApi.list({ limit: 100 }).then(page => {
    if (!disposed) projects.value = page.items
  }).catch(() => { /* 项目筛选失败仍可按项目 ID 展示。 */ })
  if (await list.fetchPage(restorePage)) {
    if (!disposed && filterKey() === restorationKey) {
      loadedFilterKey = restorationKey
      saveScene()
      await nextTick()
      if (scrollY) window.scrollTo(0, scrollY)
    }
  }
})

function saveScene() {
  try {
    sessionStorage.setItem('review-center-filters', JSON.stringify({
      project: filterProject.value, type: filterType.value, state: filterState.value,
      q: search.value, page: loadedFilterKey === filterKey() ? pageNum.value : 1,
      pageSize: pageSize.value, scrollY: window.scrollY
    }))
  } catch { /* 存储不可用不阻断查询。 */ }
}
onBeforeUnmount(() => { saveScene(); disposed = true })

async function remember(request: Promise<boolean>) {
  const key = filterKey()
  if (await request) { loadedFilterKey = key; saveScene() }
}
function load() { return remember(list.load()) }
function refresh() { return remember(list.refresh()) }
function changePage(page: number, size: number) { return remember(list.changePage(page, size)) }

function resetFilters() {
  filterProject.value = undefined
  filterType.value = undefined
  filterState.value = undefined
  search.value = ''
  return load()
}

function projectName(id: string): string {
  return projects.value.find((project) => project.id === id)?.name || id
}

function openTask(id: string) {
  router.push({ path: `/reviews/${id}`, query: { from: '/reviews' } })
}

function copyTask(task: TaskSummary) {
  if (task.route === 'AGENT') {
    router.push({ path: '/agents/new', query: { copyFrom: task.id } })
  } else {
    openReviewLaunch(task.id)
  }
}

function typeText(task: TaskSummary): string {
  if (task.route === 'AGENT') return 'Agent 分析'
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度' } as Record<string, string>)[task.specialistType] || task.specialistType
}

function stateText(state: string): string {
  return (
    ({
      QUEUED: '排队中',
      PREPARING: '准备中',
      RUNNING: '执行中',
      SUCCEEDED: '已完成',
      PARTIAL: '部分完成',
      FAILED: '失败',
      CANCELLED: '已取消'
    } as Record<string, string>)[state] || state
  )
}

function stateTone(state: string): 'green' | 'amber' | 'blue' | 'red' | 'default' {
  if (state === 'SUCCEEDED') return 'green'
  if (state === 'PARTIAL') return 'amber'
  if (state === 'RUNNING') return 'blue'
  if (state === 'FAILED') return 'red'
  return 'default'
}

function formatTime(value: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped lang="less">
.task-cell {
  white-space: normal;
  overflow-wrap: anywhere;
  min-width: 16em;
}
.task-cell strong,
.task-cell .cell-sub {
  display: block;
}
</style>
