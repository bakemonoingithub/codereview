<template>
  <ListPageLayout
    title="审查中心"
    subtitle="查看逐文件分析结果，跟进反馈，并将已完成结果整理成报告。"
  >
    <template #actions>
      <a-button @click="router.push('/reports/new')">生成报告</a-button>
      <a-button type="primary" @click="router.push('/reviews/new')">＋ 发起审查</a-button>
    </template>

    <template #notice>
      <PageNotice v-if="created" closable @close="created = ''">
        新任务已受理。任务正在后台逐文件执行，可稍后刷新查看状态。
      </PageNotice>
      <LoadErrorAlert :message="error" @retry="load" />
    </template>

    <template #filters>
      <a-input
        v-model:value="search"
        class="filter-search"
        allow-clear
        placeholder="按任务 ID 搜索"
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
        placeholder="全部专项类型"
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
      <a-button :loading="loading" @click="load">刷新</a-button>
    </template>

    <a-table
      :data-source="tasks"
      row-key="id"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 'max-content' }"
    >
      <a-table-column title="任务" key="task" width="310">
        <template #default="{ record }">
          <strong><a @click="openTask(record.id)">{{ shortId(record.id) }}</a></strong>
          <span class="cell-sub">{{ projectName(record.projectId) }}</span>
        </template>
      </a-table-column>
      <a-table-column title="类型" key="type" width="110">
        <template #default="{ record }">{{ typeText(record.specialistType) }}</template>
      </a-table-column>
      <a-table-column title="状态" key="state" width="120">
        <template #default="{ record }">
          <StatusBadge :tone="stateTone(record.state)">{{ stateText(record.state) }}</StatusBadge>
        </template>
      </a-table-column>
      <a-table-column title="文件完成情况" key="counts" width="170">
        <template #default="{ record }">
          {{ record.succeededCount }} / {{ record.targetCount }}
          <span v-if="record.failedCount" class="cell-sub">失败 {{ record.failedCount }}</span>
        </template>
      </a-table-column>
      <a-table-column title="发起时间" key="createdAt" width="170">
        <template #default="{ record }">{{ formatTime(record.createdAt) }}</template>
      </a-table-column>
      <a-table-column title="操作" key="actions" width="200">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openTask(record.id)">查看详情 →</a-button>
          <a-button type="link" size="small" @click="copyTask(record.id)">复制参数</a-button>
        </template>
      </a-table-column>
      <template #emptyText>
        <EmptyGuide
          title="还没有审查任务"
          action-text="发起第一个审查"
          @action="router.push('/reviews/new')"
        />
        <a-button v-if="hasFilters" type="link" @click="resetFilters">重置筛选</a-button>
      </template>
    </a-table>
    <div class="table-footer">
      <span>按发起时间排序</span>
      <a-button v-if="hasMore" type="link" :loading="loading" @click="loadMore">加载更多</a-button>
    </div>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectsApi, tasksApi, type ProjectView, type TaskSummary } from '@/api/v2'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import PageNotice from '@/components/PageNotice.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const route = useRoute()
const router = useRouter()
const tasks = ref<TaskSummary[]>([])
const projects = ref<ProjectView[]>([])
let listEpoch = 0
let loadedPages = 1
let loadedFilterKey = ''
let disposed = false
const filterKey = () => JSON.stringify([filterProject.value, filterType.value, filterState.value, search.value])
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
const created = ref(String(route.query.created || ''))
const filterProject = ref<string | undefined>()
const filterType = ref<string | undefined>()
const filterState = ref<string | undefined>()
const search = ref('')
const hasFilters = computed(
  () => !!(filterProject.value || filterType.value || filterState.value || search.value)
)

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: project.name }))
)
const typeOptions = [
  { value: 'ISSUE', label: '问题审查' },
  { value: 'PATTERN', label: '设计模式' },
  { value: 'COUPLING', label: '耦合度' }
]
const stateOptions = [
  { value: 'QUEUED', label: '排队中' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCEEDED', label: '已完成' },
  { value: 'PARTIAL', label: '部分完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' }
]

onMounted(async () => {
  let saved: string | null = null
  let restorePages = 1
  let scrollY = 0
  try { saved = sessionStorage.getItem('review-center-filters') } catch { /* 无现场 */ }
  if (saved) {
    try {
      const filters = JSON.parse(saved) as {
        project?: string
        type?: string
        state?: string
        q?: string
        pages?: number
        scrollY?: number
      }
      filterProject.value = filters.project
      filterType.value = filters.type
      filterState.value = filters.state
      search.value = filters.q || ''
      restorePages = Math.min(100, Math.max(1, Number(filters.pages) || 1))
      scrollY = Number(filters.scrollY) || 0
    } catch {
      try { sessionStorage.removeItem('review-center-filters') } catch { /* 无现场 */ }
    }
  }
  try {
    const page = await projectsApi.list({ limit: 100 })
    projects.value = page.items
  } catch {
    // 项目筛选失败不阻塞任务列表；任务仍可按项目 ID 展示
  }
  if (disposed) return
  const restorationKey = filterKey()
  await load()
  for (let index = 1; index < restorePages && hasMore.value && !error.value && !disposed && filterKey() === restorationKey; index++) await loadMore()
  await nextTick()
  if (!disposed && scrollY) window.scrollTo(0, scrollY)
})

function saveScene() {
  try {
    sessionStorage.setItem('review-center-filters', JSON.stringify({
      project: filterProject.value, type: filterType.value, state: filterState.value,
      q: search.value, pages: loadedFilterKey === filterKey() ? loadedPages : 1, scrollY: window.scrollY
    }))
  } catch { /* 存储不可用不阻断查询 */ }
}
onBeforeUnmount(() => { saveScene(); disposed = true; listEpoch++ })

async function load() {
  await loadPage(false)
}

async function loadMore() {
  if (loading.value || !hasMore.value || filterKey() !== loadedFilterKey) return
  await loadPage(true)
}

function resetFilters() {
  filterProject.value = undefined
  filterType.value = undefined
  filterState.value = undefined
  search.value = ''
  return load()
}

async function loadPage(append: boolean) {
  const epoch = ++listEpoch
  const key = filterKey()
  loading.value = true
  error.value = ''
  try {
    const page = await tasksApi.list({
      cursor: append ? nextCursor.value || undefined : undefined,
      projectId: filterProject.value,
      type: filterType.value,
      state: filterState.value,
      q: search.value || undefined,
      limit: 50
    })
    if (epoch !== listEpoch || disposed) return
    tasks.value = append ? [...tasks.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    loadedFilterKey = key
    loadedPages = append ? loadedPages + 1 : 1
    saveScene()
  } catch (failure) {
    if (epoch !== listEpoch || disposed) return
    error.value = (failure as Error).message || '任务列表加载失败'
  } finally {
    if (epoch === listEpoch && !disposed) loading.value = false
  }
}

function projectName(id: string): string {
  return projects.value.find((project) => project.id === id)?.name || id
}

function shortId(id: string): string {
  return id.length > 10 ? `${id.slice(0, 10)}…` : id
}

function openTask(id: string) {
  router.push({ path: `/reviews/${id}`, query: { from: '/reviews' } })
}

function copyTask(id: string) {
  router.push({ path: '/reviews/new', query: { copyFrom: id } })
}

function typeText(type: string): string {
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度' } as Record<string, string>)[type] || type
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
