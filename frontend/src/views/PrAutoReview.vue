<template>
  <ListPageLayout title="PR 自动审查" :card="false">
    <template #actions>
      <a-select
        v-model:value="selectedProjectId"
        class="project-select"
        show-search
        option-filter-prop="label"
        placeholder="选择项目"
        :loading="projectsLoading"
        :options="projectOptions"
        @change="onProjectChange"
      />
      <StatusBadge v-if="currentProject" :tone="currentHost.hostType === 'github' ? 'blue' : 'green'">
        {{ currentHost.hostLabel }}
      </StatusBadge>
      <code v-if="boundRepoName" class="bound-repo-code">{{ boundRepoName }}</code>
      <a-radio-group v-model:value="activeView" button-style="solid" @change="onViewChange">
        <a-radio-button value="config">自动审查配置</a-radio-button>
        <a-radio-button value="records">处理记录</a-radio-button>
      </a-radio-group>
      <a-button
        v-if="activeView === 'config'"
        type="primary"
        :loading="saving"
        :disabled="!canSaveConfig"
        @click="saveConfig"
      >
        保存配置
      </a-button>
      <a-button
        v-else
        :loading="deliveriesLoading"
        :disabled="!selectedProjectId"
        @click="refreshDeliveries"
      >
        刷新
      </a-button>
    </template>

    <template #notice>
      <LoadErrorAlert :message="activeError" @retry="retryActiveView" />
    </template>

    <EmptyGuide
      v-if="!projectsLoading && !projects.length && !projectsError"
      title="还没有项目"
      action-text="前往项目管理"
      @action="router?.push('/projects')"
    />

    <template v-else-if="selectedProjectId">
      <a-spin v-if="activeView === 'config'" :spinning="configLoading">
        <a-alert v-if="saveError" type="error" show-icon :message="saveError" class="mb16" />
        <div class="config-grid">
          <a-card title="审查配置">
            <template #extra>
              <a-checkbox v-model:checked="form.enabled">启用自动审查</a-checkbox>
            </template>
            <a-form layout="vertical" class="form-grid">
              <a-form-item label="目标分支" required>
                <a-input
                  v-model:value="form.targetBranch"
                  :maxlength="200"
                  placeholder="main"
                />
              </a-form-item>
              <a-form-item label="审查模型" :required="form.enabled">
                <a-select
                  v-model:value="form.modelConfigId"
                  allow-clear
                  placeholder="选择审查模型"
                  :loading="optionsLoading"
                  :options="modelOptions"
                />
              </a-form-item>
              <a-form-item label="通用提示词">
                <a-select
                  v-model:value="form.promptId"
                  allow-clear
                  placeholder="使用默认规则"
                  :loading="optionsLoading"
                  :options="promptOptions"
                  @change="onPromptChange"
                />
              </a-form-item>
              <a-form-item label="提示词版本" :required="!!form.promptId">
                <PromptVersionSelect
                  v-model:value="form.promptVersionId"
                  :versions="promptVersions"
                  :current-version-id="selectedPrompt?.currentVersion?.id"
                  :loading="promptLoading"
                />
                <a-alert v-if="promptError" type="error" show-icon :message="promptError" class="mt8" />
              </a-form-item>
              <a-form-item class="span-2" label="可触发 /recheck 的审核者">
                <div class="inline-control">
                  <a-input
                    v-model:value="reviewerInput"
                    :placeholder="`${currentHost.hostLabel} 登录名`"
                    @update:value="reviewerError = ''"
                    @press-enter="addReviewer"
                  />
                  <a-button
                    :loading="reviewerLookingUp"
                    :disabled="!reviewerInput.trim()"
                    @click="addReviewer"
                  >
                    添加
                  </a-button>
                </div>
                <div v-if="reviewerError" class="field-error" role="alert">{{ reviewerError }}</div>
                <div v-if="form.reviewers.length" class="reviewer-list" aria-label="已添加的审核者">
                  <span v-for="(reviewer, index) in form.reviewers" :key="reviewer.id" class="reviewer-chip">
                    <code>{{ reviewer.login }}</code>
                    <button
                      type="button"
                      class="reviewer-remove"
                      :aria-label="`移除 ${reviewer.login}`"
                      @click="removeReviewer(index)"
                    >
                      ×
                    </button>
                  </span>
                </div>
              </a-form-item>
            </a-form>
          </a-card>

          <a-card title="Webhook 与评论回写">
            <a-form layout="vertical" class="form-grid">
              <a-form-item class="span-2" label="Webhook 接收地址">
                <div class="inline-control">
                  <a-input :value="webhookEndpointUrl" readonly />
                  <a-button @click="copyEndpoint">复制</a-button>
                </div>
              </a-form-item>
              <a-form-item label="Webhook 验签密钥">
                <a-input-password
                  v-model:value="form.webhookSecret"
                  :placeholder="configView?.webhookSecretConfigured ? '已配置，留空不修改' : '留空使用服务端密钥'"
                  autocomplete="new-password"
                />
              </a-form-item>
              <a-form-item label="回写机器人账号">
                <a-input
                  v-model:value="form.botLogin"
                  :maxlength="100"
                  placeholder="code-review-bot"
                />
              </a-form-item>
              <a-form-item class="span-2" label="审查系统访问地址" :required="form.enabled">
                <a-input
                  v-model:value="form.reviewBaseUrl"
                  :maxlength="500"
                  placeholder="https://review.example.com"
                />
              </a-form-item>
            </a-form>
          </a-card>
        </div>
      </a-spin>

      <div v-else class="page-card">
        <div class="page-filters">
          <div class="page-filters-main">
            <a-input-search
              v-model:value="filterKeyword"
              class="filter-search"
              allow-clear
              placeholder="搜索 PR 编号、投递 ID、审查 ID、触发人或原因"
              @search="loadDeliveries"
            />
            <a-select
              v-model:value="filterEvent"
              allow-clear
              placeholder="全部事件"
              style="width: 190px"
              :options="eventOptions"
              @change="loadDeliveries"
            />
            <a-select
              v-model:value="filterDecision"
              allow-clear
              placeholder="全部决定"
              style="width: 150px"
              :options="decisionOptions"
              @change="loadDeliveries"
            />
            <a-select
              v-model:value="filterCommentState"
              allow-clear
              placeholder="全部回写状态"
              style="width: 150px"
              :options="commentStateOptions"
              @change="loadDeliveries"
            />
          </div>
          <div v-if="hasRecordFilters" class="page-filters-extra">
            <a-button @click="resetRecordFilters">重置</a-button>
          </div>
        </div>

        <a-table
          :data-source="deliveries"
          row-key="id"
          :loading="deliveriesLoading"
          :pagination="pagination"
          :scroll="{ x: 'max-content' }"
        >
          <a-table-column title="接收时间 / 投递 ID" key="delivery">
            <template #default="{ record }">
              <div>{{ formatTime(record.firstReceivedAt) }}</div>
              <a class="delivery-link" @click="openDetail(record.id)">
                <code>{{ record.deliveryId || record.id }}</code>
              </a>
              <span v-if="record.receiveCount > 1" class="muted small"> · 收到 {{ record.receiveCount }} 次</span>
            </template>
          </a-table-column>
          <a-table-column title="事件 / 动作" key="event" width="200">
            <template #default="{ record }">
              <code>{{ recordEvent(record) || '—' }}</code>
              <span v-if="record.eventAction" class="cell-sub">{{ record.eventAction }}</span>
            </template>
          </a-table-column>
          <a-table-column title="PR" key="pr" width="160">
            <template #default="{ record }">
              <strong v-if="record.prNumber">#{{ record.prNumber }}</strong>
              <span v-else>—</span>
              <span v-if="record.actorLogin || record.targetBranch" class="cell-sub">
                {{ [record.actorLogin, record.targetBranch].filter(Boolean).join(' · ') }}
              </span>
            </template>
          </a-table-column>
          <a-table-column title="审查决定" key="decision" width="260">
            <template #default="{ record }">
              <StatusBadge :tone="decisionTone(record.decisionState)">
                {{ decisionLabel(record.decisionState) }}
              </StatusBadge>
              <span v-if="record.decisionMessage" class="cell-sub">{{ record.decisionMessage }}</span>
              <a
                v-if="record.reviewTaskId"
                class="cell-sub task-link"
                @click="openTask(record.reviewTaskId)"
              >
                <code>{{ record.reviewTaskId }}</code> →
              </a>
            </template>
          </a-table-column>
          <a-table-column title="评论回写" key="comment" width="160">
            <template #default="{ record }">
              <StatusBadge :tone="commentTone(record.commentState)">
                {{ commentLabel(record.commentState) }}
              </StatusBadge>
              <span v-if="record.commentId" class="cell-sub">#{{ record.commentId }}</span>
            </template>
          </a-table-column>
          <a-table-column title="操作" key="actions" width="170">
            <template #default="{ record }">
              <a-button type="link" size="small" @click="openDetail(record.id)">详情</a-button>
              <a-button
                v-if="record.reviewTaskId"
                type="link"
                size="small"
                @click="openTask(record.reviewTaskId)"
              >
                查看审查 →
              </a-button>
            </template>
          </a-table-column>
          <template #emptyText>
            <EmptyGuide :title="hasRecordFilters ? '没有匹配的投递记录' : '当前项目暂无投递记录'" />
            <a-button v-if="hasRecordFilters" type="link" @click="resetRecordFilters">重置筛选</a-button>
          </template>
        </a-table>
      </div>
    </template>

    <a-drawer
      :open="detailOpen"
      title="投递详情"
      width="min(680px, 100vw)"
      destroy-on-close
      @close="closeDetail"
    >
      <a-spin :spinning="detailLoading">
        <a-alert v-if="detailError" type="error" show-icon :message="detailError" class="mb16">
          <template #action>
            <a-button size="small" @click="reloadDetail">重试</a-button>
          </template>
        </a-alert>

        <template v-else-if="activeDetail">
          <div class="detail-summary">
            <StatusBadge :tone="decisionTone(activeDetail.decisionState)">
              {{ decisionLabel(activeDetail.decisionState) }}
            </StatusBadge>
            <StatusBadge :tone="commentTone(activeDetail.commentState)">
              {{ commentLabel(activeDetail.commentState) }}
            </StatusBadge>
            <code>{{ activeDetail.deliveryId || activeDetail.id }}</code>
            <strong v-if="activeDetail.prNumber">#{{ activeDetail.prNumber }}</strong>
          </div>

          <div class="drawer-tabs" role="tablist" aria-label="记录环节">
            <button
              v-for="tab in detailTabs"
              :id="`detail-tab-${tab.key}`"
              :key="tab.key"
              type="button"
              role="tab"
              :class="{ active: detailTab === tab.key }"
              :aria-selected="detailTab === tab.key"
              @click="detailTab = tab.key"
            >
              {{ tab.label }}
            </button>
          </div>

          <div v-if="detailTab === 'receive'" role="tabpanel" aria-labelledby="detail-tab-receive">
            <a-descriptions :column="2" size="small" bordered>
              <a-descriptions-item label="宿主实例" :span="2">
                {{ hostLabel(activeDetail.hostType) }}
                <code v-if="activeDetail.hostInstanceKey" class="ml8">{{ activeDetail.hostInstanceKey }}</code>
              </a-descriptions-item>
              <a-descriptions-item :label="deliveryHeaderName(activeDetail.hostType)" :span="2">
                <code>{{ activeDetail.deliveryId || '—' }}</code>
              </a-descriptions-item>
              <a-descriptions-item :label="eventHeaderName(activeDetail.hostType)">
                <code>{{ activeDetail.eventName || '—' }}</code>
              </a-descriptions-item>
              <a-descriptions-item
                v-if="activeDetail.hostType === 'gitea' || activeDetail.eventType"
                label="X-Gitea-Event-Type"
              >
                <code>{{ activeDetail.eventType || '—' }}</code>
              </a-descriptions-item>
              <a-descriptions-item label="动作">
                <code>{{ activeDetail.eventAction || '—' }}</code>
              </a-descriptions-item>
              <a-descriptions-item label="接收次数">
                {{ activeDetail.receiveCount }}
              </a-descriptions-item>
              <a-descriptions-item label="首次接收">
                {{ formatTime(activeDetail.firstReceivedAt) }}
              </a-descriptions-item>
              <a-descriptions-item label="最近接收">
                {{ formatTime(activeDetail.lastReceivedAt) }}
              </a-descriptions-item>
              <a-descriptions-item label="仓库">
                <code>{{ activeDetail.repoFullName || activeDetail.repoId || '—' }}</code>
              </a-descriptions-item>
              <a-descriptions-item label="触发人">
                {{ activeDetail.actorLogin || activeDetail.actorUserId || '—' }}
              </a-descriptions-item>
            </a-descriptions>

            <pre v-if="activeDetail.payloadSnapshot" class="code payload-box">{{ prettyJson(activeDetail.payloadSnapshot) }}</pre>
          </div>

          <div v-else-if="detailTab === 'decision'" role="tabpanel" aria-labelledby="detail-tab-decision">
            <a-descriptions :column="2" size="small" bordered>
              <a-descriptions-item label="决定状态">
                <StatusBadge :tone="decisionTone(activeDetail.decisionState)">
                  {{ decisionLabel(activeDetail.decisionState) }}
                </StatusBadge>
              </a-descriptions-item>
              <a-descriptions-item label="判定时间">
                {{ formatTime(activeDetail.decidedAt) }}
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.triggerKind" label="触发方式">
                {{ triggerLabel(activeDetail.triggerKind) }}
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.targetBranch" label="目标分支">
                <code>{{ activeDetail.targetBranch }}</code>
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.decisionCode" label="原因代码" :span="2">
                <code>{{ activeDetail.decisionCode }}</code>
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.decisionMessage" label="原因说明" :span="2">
                {{ activeDetail.decisionMessage }}
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.businessKey" label="业务幂等键" :span="2">
                <code>{{ activeDetail.businessKey }}</code>
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.canonicalRecordId" label="关联原记录" :span="2">
                <code>{{ activeDetail.canonicalRecordId }}</code>
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.sourceHead" label="PR 版本" :span="2">
                <PullSourceVersion
                  :pull-number="activeDetail.prNumber"
                  :merge-base="activeDetail.mergeBase"
                  :source-head="activeDetail.sourceHead"
                  :target-head="activeDetail.targetHead"
                />
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.textCount != null" label="文件范围" :span="2">
                文本 {{ activeDetail.textCount }} · 二进制 {{ activeDetail.binaryCount ?? 0 }} · 不可读 {{ activeDetail.unreadableCount ?? 0 }}
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.reviewTaskId" label="审查任务" :span="2">
                <div class="inline-control">
                  <code>{{ activeDetail.reviewTaskId }}</code>
                  <a-button type="primary" size="small" @click="openTask(activeDetail.reviewTaskId)">
                    打开审查详情 →
                  </a-button>
                </div>
              </a-descriptions-item>
            </a-descriptions>
          </div>

          <div v-else role="tabpanel" aria-labelledby="detail-tab-write">
            <a-descriptions :column="2" size="small" bordered>
              <a-descriptions-item label="回写状态">
                <StatusBadge :tone="commentTone(activeDetail.commentState)">
                  {{ commentLabel(activeDetail.commentState) }}
                </StatusBadge>
              </a-descriptions-item>
              <a-descriptions-item label="发送次数">
                {{ activeDetail.commentAttempts }}
              </a-descriptions-item>
              <a-descriptions-item label="最近发送">
                {{ formatTime(activeDetail.commentLastAttemptAt) }}
              </a-descriptions-item>
              <a-descriptions-item label="HTTP 状态">
                {{ activeDetail.commentHttpStatus ?? '—' }}
              </a-descriptions-item>
              <a-descriptions-item label="回写评论 ID">
                <code v-if="activeDetail.commentId">#{{ activeDetail.commentId }}</code>
                <span v-else>—</span>
              </a-descriptions-item>
              <a-descriptions-item label="核对时间">
                {{ formatTime(activeDetail.commentCheckedAt) }}
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.commentActorId" label="发送账号 ID" :span="2">
                <code>{{ activeDetail.commentActorId }}</code>
              </a-descriptions-item>
              <a-descriptions-item v-if="activeDetail.commentError" label="最近错误" :span="2">
                {{ activeDetail.commentError }}
              </a-descriptions-item>
            </a-descriptions>

            <pre v-if="activeDetail.commentBody" class="code payload-box">{{ activeDetail.commentBody }}</pre>
          </div>
        </template>
      </a-spin>
    </a-drawer>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  modelsApi,
  prAutoReviewApi,
  projectsApi,
  promptsApi,
  type ModelView,
  type PrAutoReviewConfigView,
  type PrAutoReviewDeliveryDetailView,
  type PrAutoReviewDeliveryView,
  type PrAutoReviewReviewer,
  type ProjectView,
  type PromptSummary,
  type PromptVersion,
  type PromptView
} from '@/api/v2'
import { isPromptApplicable, readPromptSelection, unavailablePromptVersion } from '@/utils/promptSelection'
import { useListPagination } from '@/utils/useListPagination'
import ListPageLayout from '@/components/ListPageLayout.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import PromptVersionSelect from '@/components/PromptVersionSelect.vue'
import PullSourceVersion from '@/components/PullSourceVersion.vue'

interface ParsedRepoBinding {
  hostType: 'github' | 'gitea'
  hostLabel: 'GitHub' | 'Gitea'
  instance: string | null
  fullName: string | null
}

function parseRepoBinding(repoUrl?: string | null): ParsedRepoBinding {
  const raw = String(repoUrl || '').trim().replace(/\/+$/, '').replace(/\.git$/i, '')
  if (!raw) {
    return { hostType: 'github', hostLabel: 'GitHub', instance: 'github.com', fullName: null }
  }
  let scheme: string | null = null
  let host = ''
  let path = ''
  if (raw.includes('://')) {
    const idx = raw.indexOf('://')
    scheme = raw.slice(0, idx).toLowerCase()
    const rest = raw.slice(idx + 3)
    const slash = rest.indexOf('/')
    if (slash >= 0) {
      host = rest.slice(0, slash)
      path = rest.slice(slash + 1)
    }
  } else if (raw.startsWith('git@')) {
    const rest = raw.slice(4)
    const colon = rest.indexOf(':')
    if (colon >= 0) {
      host = rest.slice(0, colon)
      path = rest.slice(colon + 1)
    }
  }
  const parts = path.split('/').filter(Boolean)
  const fullName = parts.length >= 2 ? `${parts[parts.length - 2]}/${parts[parts.length - 1]}` : null
  const isGitHub = host.toLowerCase() === 'github.com'
  if (isGitHub) {
    return { hostType: 'github', hostLabel: 'GitHub', instance: 'github.com', fullName }
  }
  const prefix = parts.length > 2 ? `/${parts.slice(0, -2).join('/')}` : ''
  const instance = scheme && host ? `${scheme}://${host.toLowerCase()}${prefix}` : null
  return { hostType: 'gitea', hostLabel: 'Gitea', instance, fullName }
}

const route = useRoute()
const router = useRouter()

const projects = ref<ProjectView[]>([])
const projectsLoading = ref(false)
const projectsError = ref('')
const selectedProjectId = ref<string>(String(route?.query?.projectId || ''))
const activeView = ref<'config' | 'records'>(route?.query?.tab === 'records' ? 'records' : 'config')

const models = ref<ModelView[]>([])
const prompts = ref<PromptSummary[]>([])
const optionsLoading = ref(false)

const configView = ref<PrAutoReviewConfigView | null>(null)
const configLoading = ref(false)
const configError = ref('')
const saving = ref(false)
const saveError = ref('')
let configEpoch = 0
let promptEpoch = 0
let detailEpoch = 0
let eventsEpoch = 0
let restoringConfig = false
let disposed = false

const selectedPrompt = ref<PromptView | null>(null)
const promptVersions = ref<PromptVersion[]>([])
const promptLoading = ref(false)
const promptError = ref('')

const reviewerInput = ref('')
const reviewerLookingUp = ref(false)
const reviewerError = ref('')

const form = reactive({
  enabled: false,
  targetBranch: 'main',
  modelConfigId: '' as string | undefined,
  promptId: '' as string | undefined,
  promptVersionId: '',
  reviewBaseUrl: '',
  webhookSecret: '',
  botLogin: '',
  reviewers: [] as PrAutoReviewReviewer[]
})

const filterKeyword = ref('')
const filterEvent = ref<string | undefined>()
const filterDecision = ref<string | undefined>()
const filterCommentState = ref<string | undefined>()
const projectEvents = ref<string[]>([])

const detailOpen = ref(false)
const detailId = ref('')
const detailLoading = ref(false)
const detailError = ref('')
const activeDetail = ref<PrAutoReviewDeliveryDetailView | null>(null)
const detailTab = ref<'receive' | 'decision' | 'write'>('receive')
const detailTabs = [
  { key: 'receive', label: '接收请求' },
  { key: 'decision', label: '审查决定' },
  { key: 'write', label: '评论回写' }
] as const

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: project.name }))
)
const currentProject = computed(() =>
  projects.value.find((project) => project.id === selectedProjectId.value) || null
)
const currentHost = computed(() => parseRepoBinding(currentProject.value?.repoUrl))
const boundRepoName = computed(
  () => configView.value?.repoFullName || currentHost.value.fullName || currentProject.value?.repoUrl || ''
)
const giteaInstanceCount = computed(() => {
  const instances = new Set(
    projects.value
      .map((project) => parseRepoBinding(project.repoUrl))
      .filter((binding) => binding.hostType === 'gitea' && binding.instance)
      .map((binding) => binding.instance!)
  )
  return instances.size
})
const webhookEndpointUrl = computed(() => {
  const base = (form.reviewBaseUrl.trim() || window.location.origin || 'http://localhost:8080').replace(/\/+$/, '')
  if (currentHost.value.hostType === 'github') {
    return `${base}/webhooks/github`
  }
  if (giteaInstanceCount.value > 1 && currentHost.value.instance) {
    return `${base}/webhooks/gitea?instance=${encodeURIComponent(currentHost.value.instance)}`
  }
  return `${base}/webhooks/gitea`
})

const modelOptions = computed(() =>
  models.value.map((model) => ({ value: model.id, label: model.name }))
)
const promptOptions = computed(() =>
  prompts.value
    .filter((prompt) => isPromptApplicable(prompt.purpose, 'SPECIALIST'))
    .map((prompt) => ({ value: prompt.id, label: prompt.name }))
)

const canSaveConfig = computed(() =>
  !!selectedProjectId.value &&
  !configLoading.value &&
  !saving.value &&
  !promptLoading.value &&
  !promptError.value &&
  !!form.targetBranch.trim() &&
  (!form.promptId || !!form.promptVersionId)
)

const decisionOptions = [
  { value: 'CREATED', label: '已创建任务' },
  { value: 'SKIPPED', label: '已跳过' },
  { value: 'FAILED', label: '创建失败' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'RECEIVED', label: '已接收' }
]
const commentStateOptions = [
  { value: 'SENT', label: '已发送' },
  { value: 'UNKNOWN', label: '结果待确认' },
  { value: 'FAILED', label: '发送失败' },
  { value: 'PENDING', label: '待发送' },
  { value: 'SENDING', label: '发送中' },
  { value: 'NONE', label: '无需发送' }
]

const filterKey = () =>
  JSON.stringify([
    selectedProjectId.value,
    filterKeyword.value.trim(),
    filterEvent.value,
    filterDecision.value,
    filterCommentState.value
  ])

const deliveryList = useListPagination<PrAutoReviewDeliveryView>(
  'auto-review-deliveries',
  (params) => {
    if (!selectedProjectId.value) {
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false, total: 0 })
    }
    return prAutoReviewApi.listDeliveries(selectedProjectId.value, {
      ...params,
      q: filterKeyword.value.trim() || undefined,
      eventName: filterEvent.value || undefined,
      decisionState: filterDecision.value || undefined,
      commentState: filterCommentState.value || undefined
    })
  },
  '投递记录加载失败',
  filterKey
)
const { items: deliveries, loading: deliveriesLoading, error: deliveriesError, pagination } = deliveryList

const eventOptions = computed(() => {
  const merged = new Set<string>(projectEvents.value)
  for (const item of deliveries.value) {
    const ev = recordEvent(item)
    if (ev) merged.add(ev)
  }
  if (filterEvent.value) merged.add(filterEvent.value)
  return [...merged].sort().map((value) => ({ value, label: value }))
})

const hasRecordFilters = computed(
  () => !!(filterKeyword.value.trim() || filterEvent.value || filterDecision.value || filterCommentState.value)
)

const activeError = computed(() => {
  if (projectsError.value) return projectsError.value
  return activeView.value === 'config' ? configError.value : deliveriesError.value
})

onMounted(() => {
  void initPage()
})

onBeforeUnmount(() => {
  disposed = true
  configEpoch++
  promptEpoch++
  detailEpoch++
  eventsEpoch++
})

async function initPage() {
  projectsLoading.value = true
  projectsError.value = ''
  optionsLoading.value = true
  try {
    const [projectPage, modelPage, promptPage] = await Promise.all([
      projectsApi.list({ limit: 100 }),
      modelsApi.list({ limit: 100 }).catch(() => ({ items: [] as ModelView[] })),
      promptsApi.list({ limit: 100 }).catch(() => ({ items: [] as PromptSummary[] }))
    ])
    if (disposed) return
    projects.value = projectPage.items
    models.value = modelPage.items
    prompts.value = promptPage.items
    if (!selectedProjectId.value || !projects.value.some((item) => item.id === selectedProjectId.value)) {
      selectedProjectId.value = projects.value[0]?.id || ''
    }
    if (selectedProjectId.value) {
      await loadCurrentProjectData()
    }
  } catch (error) {
    if (!disposed) {
      projectsError.value = (error as Error).message || '项目列表加载失败'
    }
  } finally {
    if (!disposed) {
      projectsLoading.value = false
      optionsLoading.value = false
    }
  }
}

async function loadCurrentProjectData() {
  if (!selectedProjectId.value) return
  await Promise.all([
    loadConfig(selectedProjectId.value),
    loadProjectEvents(selectedProjectId.value),
    deliveryList.load()
  ])
}

async function loadConfig(projectId: string) {
  const epoch = ++configEpoch
  configLoading.value = true
  configError.value = ''
  saveError.value = ''
  reviewerError.value = ''
  reviewerInput.value = ''
  try {
    const loaded = await prAutoReviewApi.getConfig(projectId)
    if (disposed || epoch !== configEpoch) return
    applyConfigView(loaded)
  } catch (error) {
    if (!disposed && epoch === configEpoch) {
      configError.value = (error as Error).message || '自动审查配置读取失败'
    }
  } finally {
    if (!disposed && epoch === configEpoch) {
      configLoading.value = false
    }
  }
}

function applyConfigView(loaded: PrAutoReviewConfigView) {
  restoringConfig = true
  configView.value = loaded
  form.enabled = !!loaded.enabled
  form.targetBranch = loaded.targetBranch || currentProject.value?.currentBranch || 'main'
  form.modelConfigId = loaded.modelConfigId || undefined
  form.promptId = loaded.promptId || undefined
  form.promptVersionId = loaded.promptVersionId || ''
  form.reviewBaseUrl = loaded.reviewBaseUrl || window.location.origin || ''
  form.webhookSecret = ''
  form.botLogin = loaded.botLogin || ''
  form.reviewers = (loaded.reviewers || []).map((item) => ({ id: item.id, login: item.login }))
  restoringConfig = false
  if (form.promptId) {
    void loadPromptSelection(form.promptId, form.promptVersionId)
  } else {
    selectedPrompt.value = null
    promptVersions.value = []
    promptError.value = ''
  }
}

async function loadProjectEvents(projectId: string) {
  const epoch = ++eventsEpoch
  try {
    const events = await prAutoReviewApi.listEvents?.(projectId)
    if (!disposed && epoch === eventsEpoch && Array.isArray(events)) {
      projectEvents.value = events
    }
  } catch {
    if (!disposed && epoch === eventsEpoch) {
      projectEvents.value = []
    }
  }
}

async function loadPromptSelection(promptId?: string, preferredVersionId?: string) {
  const epoch = ++promptEpoch
  if (!promptId) {
    selectedPrompt.value = null
    promptVersions.value = []
    form.promptVersionId = ''
    promptError.value = ''
    promptLoading.value = false
    return
  }
  promptLoading.value = true
  promptError.value = ''
  try {
    const { prompt, versions, version } = await readPromptSelection(promptId, preferredVersionId || null, true)
    if (disposed || epoch !== promptEpoch) return
    selectedPrompt.value = prompt
    promptVersions.value = versions
    if (preferredVersionId && !version) {
      form.promptVersionId = preferredVersionId
      promptError.value = unavailablePromptVersion
    } else {
      form.promptVersionId = version?.id || prompt.currentVersion?.id || versions[0]?.id || ''
      promptError.value = form.promptVersionId ? '' : unavailablePromptVersion
    }
  } catch (error) {
    if (!disposed && epoch === promptEpoch) {
      promptError.value = (error as Error).message || '提示词版本读取失败'
    }
  } finally {
    if (!disposed && epoch === promptEpoch) {
      promptLoading.value = false
    }
  }
}

function onPromptChange(promptId?: string) {
  if (restoringConfig) return
  form.promptVersionId = ''
  void loadPromptSelection(promptId)
}

watch(
  () => form.promptVersionId,
  (versionId) => {
    if (!form.promptId || promptLoading.value) return
    const exists = promptVersions.value.some((item) => item.id === versionId)
    promptError.value = versionId && exists ? '' : unavailablePromptVersion
  }
)

async function addReviewer(): Promise<boolean> {
  const username = reviewerInput.value.trim()
  if (!username) return true
  if (/[\s/@]/.test(username)) {
    reviewerError.value = `请输入有效的 ${currentHost.value.hostLabel} 登录名`
    return false
  }
  const botName = form.botLogin.trim().toLowerCase()
  if (botName && username.toLowerCase() === botName) {
    reviewerError.value = '回写机器人账号不能触发复审'
    return false
  }
  if (form.reviewers.some((item) => item.login.toLowerCase() === username.toLowerCase())) {
    reviewerError.value = '该账号已在名单中'
    return false
  }
  if (!selectedProjectId.value) return false

  reviewerLookingUp.value = true
  reviewerError.value = ''
  try {
    const user = await prAutoReviewApi.lookupReviewer(selectedProjectId.value, username)
    if (botName && user.login.toLowerCase() === botName) {
      reviewerError.value = '回写机器人账号不能触发复审'
      return false
    }
    if (
      form.reviewers.some(
        (item) => item.id === user.id || item.login.toLowerCase() === user.login.toLowerCase()
      )
    ) {
      reviewerError.value = '该账号已在名单中'
      return false
    }
    form.reviewers.push({ id: user.id, login: user.login })
    reviewerInput.value = ''
    return true
  } catch (error) {
    reviewerError.value = (error as Error).message || '未找到此账号，或当前凭据无法查看'
    return false
  } finally {
    reviewerLookingUp.value = false
  }
}

function removeReviewer(index: number) {
  form.reviewers.splice(index, 1)
  reviewerError.value = ''
}

function validateReviewBaseUrl(raw: string): string | null {
  const trimmed = raw.trim().replace(/\/+$/, '')
  if (!trimmed) {
    return form.enabled ? '请填写审查系统访问地址' : null
  }
  try {
    const parsed = new URL(trimmed)
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.search || parsed.hash || parsed.username || parsed.password) {
      return '请输入不带查询参数、片段或账号密码的 HTTP/HTTPS 地址'
    }
    return null
  } catch {
    return '请输入合法的 HTTP 或 HTTPS 访问地址'
  }
}

async function saveConfig() {
  if (!selectedProjectId.value || saving.value) return
  saveError.value = ''
  if (reviewerInput.value.trim()) {
    const added = await addReviewer()
    if (!added) return
  }
  if (!form.targetBranch.trim()) {
    saveError.value = '请填写目标分支'
    return
  }
  if (form.enabled && !form.modelConfigId) {
    saveError.value = '启用自动审查时必须选择审查模型'
    return
  }
  if (form.promptId && !form.promptVersionId) {
    saveError.value = '选择通用提示词时必须指定具体版本'
    return
  }
  const urlError = validateReviewBaseUrl(form.reviewBaseUrl)
  if (urlError) {
    saveError.value = urlError
    return
  }

  saving.value = true
  try {
    const updated = await prAutoReviewApi.updateConfig(selectedProjectId.value, {
      enabled: form.enabled,
      targetBranch: form.targetBranch.trim(),
      modelConfigId: form.modelConfigId || null,
      promptId: form.promptId || null,
      promptVersionId: form.promptId ? form.promptVersionId : null,
      reviewBaseUrl: form.reviewBaseUrl.trim().replace(/\/+$/, '') || null,
      webhookSecret: form.webhookSecret.trim() || undefined,
      botLogin: form.botLogin.trim() || null,
      reviewers: form.reviewers.map((item) => ({ id: item.id, login: item.login })),
      repoFullName: boundRepoName.value || null
    })
    applyConfigView(updated)
    message.success('配置已保存')
  } catch (error) {
    saveError.value = (error as Error).message || '保存配置失败'
  } finally {
    saving.value = false
  }
}

async function copyEndpoint() {
  const value = webhookEndpointUrl.value
  try {
    await navigator.clipboard.writeText(value)
    message.success('已复制接收地址')
  } catch {
    message.info(value)
  }
}

function onProjectChange() {
  detailOpen.value = false
  activeDetail.value = null
  filterKeyword.value = ''
  filterEvent.value = undefined
  filterDecision.value = undefined
  filterCommentState.value = undefined
  void loadCurrentProjectData()
}

function onViewChange() {
  if (activeView.value === 'records' && selectedProjectId.value) {
    void loadProjectEvents(selectedProjectId.value)
    void deliveryList.load()
  }
}

function loadDeliveries() {
  if (!selectedProjectId.value) return Promise.resolve(false)
  return deliveryList.load()
}

function refreshDeliveries() {
  if (!selectedProjectId.value) return Promise.resolve(false)
  void loadProjectEvents(selectedProjectId.value)
  return deliveryList.refresh()
}

function resetRecordFilters() {
  filterKeyword.value = ''
  filterEvent.value = undefined
  filterDecision.value = undefined
  filterCommentState.value = undefined
  return loadDeliveries()
}

function retryActiveView() {
  if (projectsError.value) {
    return initPage()
  }
  if (activeView.value === 'config' && selectedProjectId.value) {
    return loadConfig(selectedProjectId.value)
  }
  return deliveryList.retry()
}

async function openDetail(id: string) {
  if (!selectedProjectId.value) return
  detailId.value = id
  detailTab.value = 'receive'
  detailOpen.value = true
  await reloadDetail()
}

async function reloadDetail() {
  if (!selectedProjectId.value || !detailId.value) return
  const epoch = ++detailEpoch
  detailLoading.value = true
  detailError.value = ''
  activeDetail.value = null
  try {
    const loaded = await prAutoReviewApi.deliveryDetail(selectedProjectId.value, detailId.value)
    if (!disposed && epoch === detailEpoch) {
      activeDetail.value = loaded
    }
  } catch (error) {
    if (!disposed && epoch === detailEpoch) {
      detailError.value = (error as Error).message || '投递详情读取失败'
    }
  } finally {
    if (!disposed && epoch === detailEpoch) {
      detailLoading.value = false
    }
  }
}

function closeDetail() {
  detailEpoch++
  detailOpen.value = false
}

function openTask(taskId: string) {
  router?.push({ path: `/reviews/${taskId}`, query: { from: '/projects/auto-review' } })
}

function recordEvent(record: PrAutoReviewDeliveryView): string {
  return record.eventType || record.eventName || ''
}

function hostLabel(hostType?: string | null): string {
  return String(hostType || '').toLowerCase() === 'github' ? 'GitHub' : 'Gitea'
}

function deliveryHeaderName(hostType?: string | null): string {
  return String(hostType || '').toLowerCase() === 'github' ? 'X-GitHub-Delivery' : 'X-Gitea-Delivery'
}

function eventHeaderName(hostType?: string | null): string {
  return String(hostType || '').toLowerCase() === 'github' ? 'X-GitHub-Event' : 'X-Gitea-Event'
}

function triggerLabel(triggerKind?: string | null): string {
  if (triggerKind === 'FIRST') return '首次自动审查'
  if (triggerKind === 'RECHECK') return '评论复审 (/recheck)'
  return triggerKind || '—'
}

function decisionLabel(state?: string | null): string {
  return (
    ({
      CREATED: '已创建任务',
      SKIPPED: '已跳过',
      FAILED: '创建失败',
      REJECTED: '已拒绝',
      PROCESSING: '处理中',
      RECEIVED: '已接收'
    } as Record<string, string>)[String(state || '')] ||
    state ||
    '—'
  )
}

function decisionTone(state?: string | null): 'green' | 'amber' | 'blue' | 'red' | 'default' {
  if (state === 'CREATED') return 'green'
  if (state === 'FAILED' || state === 'REJECTED') return 'red'
  if (state === 'PROCESSING' || state === 'RECEIVED') return 'blue'
  return 'default'
}

function commentLabel(state?: string | null): string {
  return (
    ({
      SENT: '已发送',
      UNKNOWN: '结果待确认',
      FAILED: '发送失败',
      PENDING: '待发送',
      SENDING: '发送中',
      NONE: '无需发送'
    } as Record<string, string>)[String(state || '')] ||
    state ||
    '—'
  )
}

function commentTone(state?: string | null): 'green' | 'amber' | 'blue' | 'red' | 'default' {
  if (state === 'SENT') return 'green'
  if (state === 'UNKNOWN') return 'amber'
  if (state === 'FAILED') return 'red'
  if (state === 'PENDING' || state === 'SENDING') return 'blue'
  return 'default'
}

function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

function prettyJson(value?: string | null): string {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}
</script>

<style scoped lang="less">
.project-select {
  width: 210px;
}

.bound-repo-code {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.inline-control {
  display: flex;
  align-items: center;
  gap: 8px;
}

.inline-control :deep(.ant-input) {
  flex: 1;
}

.field-error {
  margin-top: 6px;
  color: var(--red);
  font-size: 12px;
}

.reviewer-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.reviewer-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px 2px 8px;
  background: var(--soft);
  border: 1px solid var(--soft-border-2);
  border-radius: 8px;
}

.reviewer-chip code {
  background: transparent;
  border: 0;
  padding: 0;
  color: var(--code-ink);
}

.reviewer-remove {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  padding: 0 4px;
  font-size: 14px;
  line-height: 1.2;
}

.reviewer-remove:hover {
  color: var(--red);
}

.delivery-link,
.task-link {
  cursor: pointer;
}

.detail-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.drawer-tabs {
  display: flex;
  gap: 22px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.drawer-tabs button {
  padding: 10px 0;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.drawer-tabs button.active {
  color: var(--blue);
  border-bottom-color: var(--blue);
}

.payload-box {
  margin: 14px 0 0;
  padding: 12px 14px;
  max-height: 44vh;
  overflow: auto;
  background: var(--preview);
  border: 1px solid var(--line);
  border-radius: 10px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.mb16 {
  margin-bottom: 16px;
}

.mt8 {
  margin-top: 8px;
}

.ml8 {
  margin-left: 8px;
}

@media (max-width: 980px) {
  .config-grid {
    grid-template-columns: 1fr;
  }
}
</style>
