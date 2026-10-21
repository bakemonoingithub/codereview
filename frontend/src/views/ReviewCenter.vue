<template>
  <div>
    <div class="page-head">
      <div>
        <h2>审查中心</h2>
        <p class="muted">查看逐文件分析状态，复制历史参数发起新任务。</p>
      </div>
      <a-button type="primary" @click="router.push('/reviews/new')">＋ 发起审查</a-button>
    </div>

    <a-alert
      v-if="created"
      type="info"
      show-icon
      message="新任务已受理。任务正在后台逐文件执行，可稍后刷新查看状态。"
      class="mb16"
      closable
      @close="created = ''"
    />
    <a-alert v-if="error" type="error" show-icon :message="error" class="mb16">
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>

    <a-card>
      <div class="filters">
        <a-input
          v-model:value="search"
          allow-clear
          placeholder="按任务 ID 搜索"
          style="width: 220px"
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
        <span class="spacer" />
        <a-button :loading="loading" @click="load">刷新</a-button>
      </div>

      <a-table
        :data-source="tasks"
        row-key="id"
        :loading="loading"
        :pagination="false"
        size="middle"
        :scroll="{ x: 'max-content' }"
      >
        <a-table-column title="任务" key="task" width="310">
          <template #default="{ record }">
            <div class="task-cell">
              <a @click="openTask(record.id)">{{ shortId(record.id) }}</a>
              <span class="muted small">{{ projectName(record.projectId) }}</span>
            </div>
          </template>
        </a-table-column>
        <a-table-column title="类型" key="type" width="110">
          <template #default="{ record }">{{ typeText(record.specialistType) }}</template>
        </a-table-column>
        <a-table-column title="状态" key="state" width="110">
          <template #default="{ record }">
            <a-tag :color="stateColor(record.state)">{{ stateText(record.state) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="文件完成情况" key="counts" width="170">
          <template #default="{ record }">
            {{ record.succeededCount }} / {{ record.targetCount }}
            <span v-if="record.failedCount" class="muted small">（失败 {{ record.failedCount }}）</span>
          </template>
        </a-table-column>
        <a-table-column title="发起时间" key="createdAt" width="170">
          <template #default="{ record }">{{ formatTime(record.createdAt) }}</template>
        </a-table-column>
        <a-table-column title="操作" key="actions" width="180">
          <template #default="{ record }">
            <a-space>
              <a-button size="small" @click="copyTask(record.id)">复制参数</a-button>
              <a-button size="small" type="link" @click="openTask(record.id)">查看</a-button>
            </a-space>
          </template>
        </a-table-column>
        <template #emptyText>
          <a-empty description="还没有审查任务">
            <a-button type="primary" @click="router.push('/reviews/new')">发起第一个审查</a-button>
          </a-empty>
        </template>
      </a-table>
      <a-button v-if="hasMore" :loading="loading" @click="loadMore">加载更多</a-button>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectsApi, tasksApi, type ProjectView, type TaskSummary } from '@/api/v2'

const route = useRoute()
const router = useRouter()
const tasks = ref<TaskSummary[]>([])
const projects = ref<ProjectView[]>([])
let listEpoch = 0
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
const created = ref(String(route.query.created || ''))
const filterProject = ref<string | undefined>()
const filterType = ref<string | undefined>()
const filterState = ref<string | undefined>()
const search = ref('')

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
  const saved = sessionStorage.getItem('review-center-filters')
  if (saved) {
    try {
      const filters = JSON.parse(saved) as {
        project?: string
        type?: string
        state?: string
        q?: string
      }
      filterProject.value = filters.project
      filterType.value = filters.type
      filterState.value = filters.state
      search.value = filters.q || ''
    } catch {
      sessionStorage.removeItem('review-center-filters')
    }
  }
  try {
    const page = await projectsApi.list({ limit: 100 })
    projects.value = page.items
  } catch {
    // 项目筛选失败不阻塞任务列表；任务仍可按项目 ID 展示
  }
  await load()
})

async function load() {
  await loadPage(false)
}

async function loadMore() {
  if (loading.value || !hasMore.value) return
  await loadPage(true)
}

async function loadPage(append: boolean) {
  const epoch = ++listEpoch
  loading.value = true
  error.value = ''
  try {
    sessionStorage.setItem('review-center-filters', JSON.stringify({
      project: filterProject.value,
      type: filterType.value,
      state: filterState.value,
      q: search.value
    }))
    const page = await tasksApi.list({
      cursor: append ? nextCursor.value || undefined : undefined,
      projectId: filterProject.value,
      type: filterType.value,
      state: filterState.value,
      q: search.value || undefined,
      limit: 50
    })
    if (epoch !== listEpoch) return
    tasks.value = append ? [...tasks.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (failure) {
    if (epoch !== listEpoch) return
    error.value = (failure as Error).message || '任务列表加载失败'
  } finally {
    if (epoch === listEpoch) loading.value = false
  }
}

function projectName(id: string): string {
  return projects.value.find((project) => project.id === id)?.name || id
}

function shortId(id: string): string {
  return id.length > 10 ? `${id.slice(0, 10)}…` : id
}

function openTask(id: string) {
  router.push({ path: `/reviews/${id}`, query: { from: route.fullPath } })
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

function stateColor(state: string): string {
  return (
    ({
      SUCCEEDED: 'green',
      RUNNING: 'blue',
      PARTIAL: 'orange',
      FAILED: 'red',
      CANCELLED: 'default'
    } as Record<string, string>)[state] || 'default'
  )
}

function formatTime(value: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped lang="less">
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 18px;
}
.page-head h2 {
  margin: 0 0 4px;
}
.muted {
  color: #69798d;
}
.small {
  font-size: 12px;
}
.mb16 {
  margin-bottom: 16px;
}
.filters {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.spacer {
  flex: 1;
}
.task-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
</style>
