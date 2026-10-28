<template>
  <LaunchModalShell
    :open="true"
    :title="copyFrom ? '复制参数发起新审查' : '发起专项审查'"
    @cancel="closeLaunch"
  >
    <template #alerts>
      <a-alert
        v-if="copyFrom"
        type="info"
        show-icon
        message="已带入原任务参数。请核对当前版本、模型和范围；确认后才会创建新任务。"
      />
      <a-alert
        v-if="copyHeadMoved"
        type="warning"
        show-icon
        message="分支版本已变化，已清空原文件范围；请按新版本重新选择。"
      />
      <a-alert
        v-if="sourceMoved"
        type="warning"
        show-icon
        message="分支版本已移动，尚未创建任务。请重新核对文件范围。"
      >
        <template #action>
          <a-button size="small" @click="refreshMovedSource">刷新版本与范围</a-button>
        </template>
      </a-alert>
      <a-alert v-if="formError" type="error" show-icon :message="formError" />
    </template>

    <template #body>
      <LaunchFormSections :titles="{ goal: '基本信息', scope: '范围', extra: '要求' }">
        <template #goal>
          <div class="form-grid">
            <a-form-item class="full">
              <template #label>审查名称 <span class="muted small">（可选）</span></template>
              <a-input
                v-model:value="form.name"
                :maxlength="200"
                placeholder="例如：问题专项审查"
              />
            </a-form-item>
            <a-form-item label="项目" required>
              <a-select
                :get-popup-container="getPopupContainer"
                v-model:value="form.projectId"
                show-search
                option-filter-prop="label"
                style="width: 100%"
                placeholder="选择项目"
                :loading="optionsLoading"
                :options="projectOptions"
                @change="onProjectChange"
              />
            </a-form-item>
            <a-form-item label="模型" required>
              <a-select
                :get-popup-container="getPopupContainer"
                v-model:value="form.modelConfigId"
                style="width: 100%"
                placeholder="选择模型"
                :loading="optionsLoading"
                :options="modelOptions"
              />
            </a-form-item>
            <a-form-item class="type-switch" label="专项类型">
              <a-radio-group v-model:value="form.specialistType" button-style="solid" @change="onTypeChange">
                <a-radio-button value="ISSUE">问题审查</a-radio-button>
                <a-radio-button value="PATTERN">设计模式</a-radio-button>
                <a-radio-button value="COUPLING">耦合度</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-form-item v-if="form.specialistType === 'ISSUE'" class="source-switch" label="审查范围">
              <a-radio-group v-model:value="form.sourceKind" button-style="solid" @change="onSourceKindChange">
                <a-radio-button value="FILES">文件内容</a-radio-button>
                <a-radio-button value="DIFF">提交比较</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </div>
        </template>

        <template #scope>
          <div class="form-grid">
            <a-form-item label="分支" required>
              <a-select
                :get-popup-container="getPopupContainer"
                v-model:value="form.branch"
                style="width: 100%"
                placeholder="选择分支"
                :loading="branchesLoading"
                :options="branchOptions"
                @change="onBranchChange"
              />
            </a-form-item>
            <a-form-item v-if="form.sourceKind === 'FILES'" label="提交hash" required>
              <LaunchVersionPreview
                :sha="form.observedSha"
                :loading="headLoading"
                :loading-text="form.observedSha || '读取中…'"
                empty-text="读取中…"
                refresh-text="重新读取"
                @refresh="resolveHead"
              />
            </a-form-item>
            <a-form-item v-else label="提交" required>
              <div class="commit-picker-field">
                <a-button
                  :loading="commitsLoading && !commits.length"
                  :disabled="!form.projectId || !form.branch"
                  @click="openCommitPicker"
                >
                  {{ form.commitSha ? '更换提交' : '选择提交' }}
                </a-button>
                <div v-if="form.commitSha" class="selected-commit">
                  <code>{{ form.commitSha.slice(0, 10) }}</code>
                  <span :title="selectedCommitSubject">{{ selectedCommitSubject || (commitDetailLoading ? '正在读取提交信息…' : '无提交说明') }}</span>
                </div>
              </div>
              <p class="muted small commit-help">
                <template v-if="form.commitSha">
                  比较基线：{{ commitDetail?.sha === form.commitSha
                    ? (form.baseSha ? form.baseSha.slice(0, 7) : '根提交（无父版本）')
                    : '提交详情就绪后显示' }}
                </template>
                <template v-else>
<!--                  提交历史分页加载；选择器会明确提示是否还有更早提交。-->
                </template>
              </p>
              <a-alert v-if="commitDetailError" type="error" show-icon :message="commitDetailError" class="commit-help">
                <template #action>
                  <a-button size="small" :loading="commitDetailLoading" @click="loadCommitDetail(form.commitSha)">重试</a-button>
                </template>
              </a-alert>
            </a-form-item>
            <a-form-item class="full" label="目标" required>
              <div class="target-actions">
                <a-button type="primary" ghost :disabled="!scopeReady" @click="scopeOpen = true">
                  {{ form.targets.length ? '调整文件范围' : '选择文件范围' }}
                </a-button>
                <span class="muted">已选 {{ form.targets.length }} 个文件</span>
              </div>
              <p v-if="!scopeReady" class="muted small">
                请先选择项目，并等待固定版本或提交读取完成。
              </p>
              <div v-if="form.targets.length" class="target-list">
                <div v-for="(target, index) in form.targets" :key="target.path" class="target-row">
                  <div class="target-main">
                    <code>{{ target.path }}</code>
                    <span v-if="target.oldPath" class="muted small">（原路径 {{ target.oldPath }}）</span>
                  </div>
                  <a-button size="small" danger type="text" @click="removeTarget(index)">移除</a-button>
                </div>
              </div>
            </a-form-item>
          </div>
        </template>

        <template #extra>
          <div class="form-grid">
            <a-form-item>
              <template #label>通用规则 <span class="muted small">（可选）</span></template>
              <a-select
                :get-popup-container="getPopupContainer"
                v-model:value="form.promptId"
                allow-clear
                style="width: 100%"
                placeholder="不使用通用规则"
                :loading="optionsLoading"
                :options="promptOptions"
              />
            </a-form-item>
            <a-form-item v-if="form.promptId" label="规则版本" required>
              <PromptVersionSelect
                v-model:value="form.promptVersionId"
                :versions="promptVersions"
                :current-version-id="selectedPrompt?.currentVersion?.id"
                :loading="promptLoading"
              />
              <a-alert v-if="promptError" type="error" show-icon :message="promptError" class="mt8">
                <template #action>
                  <a-button size="small" :loading="promptLoading" @click="loadPromptSelection(form.promptId, form.promptVersionId)">重试</a-button>
                </template>
              </a-alert>
            </a-form-item>
            <div v-if="form.promptId" class="full">
              <PromptInfoPreview
                class="mt8"
                title="所选版本正文"
                :prompt="selectedPrompt"
                :version="selectedPromptVersion"
                :selected="true"
                :loading="promptLoading"
                :error="''"
              />
            </div>
          </div>

          <div v-if="form.targets.length" class="notes">
            <div v-for="(target, index) in form.targets" :key="target.path" class="file-note">
              <div class="note-head">
                <strong>{{ target.path }}</strong>
                <span>本次要求 <span class="muted small">（可选）</span></span>
              </div>
              <a-textarea
                v-model:value="form.targets[index].note"
                :maxlength="4000"
                show-count
                :auto-size="{ minRows: 2, maxRows: 4 }"
                placeholder="填写此文件的背景、约束或关注点"
              />
            </div>
          </div>

          <label class="switch-line">
            <a-checkbox v-model:checked="form.smartSplit" />
            <span>
              <strong>智能拆分文件</strong>
              <span class="muted small">
                默认关闭。未开启时整文件提交，上下文超限直接失败；开启后允许容量不足时拆分。
              </span>
            </span>
          </label>
        </template>
      </LaunchFormSections>
    </template>

    <template #footer>
      <a-button @click="closeLaunch">取消</a-button>
      <a-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
        开始审查
      </a-button>
    </template>

    <template #overlay>
      <CommitPickerModal
        v-model:open="commitPickerOpen"
        :branch="form.branch"
        :commits="commits"
        :selected-sha="form.commitSha"
        :selected-commit="commitDetail"
        :has-more="commitsHasMore"
        :loading="commitsLoading"
        :loading-more="commitsLoadingMore"
        :error="commitsError"
        :located-commit="locatedCommit"
        :lookup-loading="commitLookupLoading"
        :lookup-error="commitLookupError"
        @confirm="confirmCommit"
        @load-more="loadMoreCommits"
        @refresh="loadCommits"
        @retry="retryCommits"
        @lookup="locateCommit"
        @clear-lookup="clearCommitLookup"
      />
      <ScopePickerModal
        v-model:open="scopeOpen"
        :project-id="form.projectId"
        :mode="form.sourceKind"
        :sha="form.observedSha"
        :commit-sha="form.commitSha"
        :branch="form.branch"
        :selected="form.targets.map((target) => target.path)"
        @confirm="onScopeConfirm"
      />
    </template>
  </LaunchModalShell>
</template>

<script setup lang="ts">
import { isPromptApplicable, readPromptSelection, unavailablePromptVersion } from '@/utils/promptSelection'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import CommitPickerModal from '@/components/CommitPickerModal.vue'
import ScopePickerModal from '@/components/ScopePickerModal.vue'
import PromptInfoPreview from '@/components/PromptInfoPreview.vue'
import PromptVersionSelect from '@/components/PromptVersionSelect.vue'
import {
  modelsApi,
  projectsApi,
  promptsApi,
  repositoryApi,
  tasksApi,
  type CommitDetail,
  type CommitInfo,
  type ModelView,
  type ProjectView,
  type PromptSummary,
  type PromptVersion,
  type PromptView
} from '@/api/v2'

type SpecialistType = 'ISSUE' | 'PATTERN' | 'COUPLING'
type SourceKind = 'FILES' | 'DIFF'

interface TargetRow {
  path: string
  oldPath: string | null
  note: string
}

const route = useRoute()
const getPopupContainer = (trigger: HTMLElement) => trigger.closest('.ant-modal-body') as HTMLElement || document.body
const router = useRouter()
const copyFrom = computed(() => String(route.query.copyFrom || ''))
const projects = ref<ProjectView[]>([])
const models = ref<ModelView[]>([])
const prompts = ref<PromptSummary[]>([])
const selectedPrompt = ref<PromptView | null>(null)
const selectedPromptVersion = ref<PromptVersion | null>(null)
const promptVersions = ref<PromptVersion[]>([])
const promptLoading = ref(false)
const promptError = ref('')
let promptEpoch = 0
let lastObservedSha = ''
let headEpoch = 0
let commitsEpoch = 0
let commitLookupEpoch = 0
let detailEpoch = 0
let branchEpoch = 0
let submission: { fingerprint: string; key: string } | null = null
const optionsLoading = ref(false)
const branches = ref<string[]>([])
const branchesLoading = ref(false)
const headLoading = ref(false)
const commitsLoading = ref(false)
const commitsLoadingMore = ref(false)
const commits = ref<CommitInfo[]>([])
const commitsPage = ref(0)
const commitsHasMore = ref(false)
const commitsError = ref('')
const failedCommitPage = ref<{ page: number; replace: boolean } | null>(null)
const commitDetail = ref<CommitDetail | null>(null)
const commitDetailLoading = ref(false)
const commitDetailError = ref('')
const commitPickerOpen = ref(false)
const locatedCommit = ref<CommitInfo | null>(null)
const commitLookupLoading = ref(false)
const commitLookupError = ref('')
const scopeOpen = ref(false)
const submitting = ref(false)
const formError = ref('')
const sourceMoved = ref(false)
const copyHeadMoved = ref(false)
const restoring = ref(false)

const form = reactive({
  name: '',
  projectId: '',
  specialistType: 'ISSUE' as SpecialistType,
  sourceKind: 'FILES' as SourceKind,
  branch: '',
  observedSha: '',
  commitSha: '',
  baseSha: '',
  targets: [] as TargetRow[],
  modelConfigId: '',
  promptId: '',
  promptVersionId: '',
  smartSplit: false
})

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: `${project.name} · ${project.repoUrl}` }))
)
const branchOptions = computed(() => branches.value.map((item) => ({ value: item, label: item })))
const modelOptions = computed(() => models.value.map((model) => ({ value: model.id, label: model.name })))
const promptOptions = computed(() => prompts.value
  .filter((prompt) => isPromptApplicable(prompt.purpose, 'SPECIALIST'))
  .map((prompt) => ({ value: prompt.id, label: prompt.name }))
)
const selectedCommitSubject = computed(() => {
  const selected = commits.value.find((commit) => commit.sha === form.commitSha)
  const message = selected?.message || (commitDetail.value?.sha === form.commitSha ? commitDetail.value.message : '')
  return String(message || '').split('\n')[0]
})
const scopeReady = computed(() =>
  !!form.projectId && !restoring.value && (form.sourceKind === 'FILES'
    ? !!form.observedSha && !headLoading.value
    : !!form.commitSha && commitDetail.value?.sha === form.commitSha)
)
const canSubmit = computed(
  () =>
    scopeReady.value && !optionsLoading.value && !sourceMoved.value &&
    (!form.promptId || (!promptLoading.value && !promptError.value &&
      !!form.promptVersionId && !!selectedPromptVersion.value)) &&
    form.targets.length > 0 &&
    !!form.modelConfigId &&
    (form.sourceKind === 'FILES' ? !!form.observedSha : !!form.commitSha) &&
    !submitting.value
)

watch(() => form.promptId, (promptId) => {
  if (restoring.value) return
  void loadPromptSelection(promptId)
}, { flush: 'sync' })

watch(() => form.promptVersionId, (versionId) => {
  selectedPromptVersion.value = promptVersions.value.find((version) => version.id === versionId) || null
  if (form.promptId && versionId && !selectedPromptVersion.value && !promptLoading.value) {
    promptError.value = unavailablePromptVersion
  } else if (selectedPromptVersion.value) {
    promptError.value = ''
  }
}, { flush: 'sync' })

function closeDropdownOnScroll(event: Event) {
  const target = event.target
  if (!(target instanceof Element) || !target.closest('.launch-modal .ant-modal-body')) return
  const active = document.activeElement
  if (active instanceof HTMLElement && active.closest('.ant-select')) active.blur()
}

onMounted(() => window.addEventListener('scroll', closeDropdownOnScroll, true))
onBeforeUnmount(() => {
  window.removeEventListener('scroll', closeDropdownOnScroll, true)
  commitsEpoch++
  commitLookupEpoch++
  detailEpoch++
  promptEpoch++
})

watch(commitPickerOpen, (open, wasOpen) => {
  clearCommitLookup()
  if (!open && wasOpen) {
    commitsEpoch++
    commitsLoading.value = false
    commitsLoadingMore.value = false
  }
}, { flush: 'sync' })

onMounted(async () => {
  await loadOptions()
  if (copyFrom.value) {
    await loadCopy()
  } else if (await applyBrowserPrefill()) {
    // 代码浏览已带入固定版本与目标
  } else if (projects.value.length) {
    form.projectId = projects.value[0].id
  }
})

async function applyBrowserPrefill(): Promise<boolean> {
  if (route.query.from !== 'browser') return false
  let raw: string | null = null
  try { raw = sessionStorage.getItem('review-launch-prefill') } catch { return false }
  if (!raw) {
    return false
  }
  try {
    sessionStorage.removeItem('review-launch-prefill')
    const prefill = JSON.parse(raw) as {
      projectId: string
      branch: string
      sourceKind: 'FILES' | 'DIFF'
      observedSha?: string
      commitSha?: string
      targets: { path: string; oldPath: string | null }[]
    }
    if (!projects.value.some(project => project.id === prefill.projectId)
      || !['FILES', 'DIFF'].includes(prefill.sourceKind) || !Array.isArray(prefill.targets)) return false
    restoring.value = true
    form.projectId = prefill.projectId
    form.branch = prefill.branch
    form.specialistType = 'ISSUE'
    form.sourceKind = prefill.sourceKind
    form.observedSha = prefill.observedSha || ''
    lastObservedSha = form.observedSha
    form.commitSha = prefill.commitSha || ''
    form.targets = (prefill.targets || []).map((target) => ({
      path: target.path,
      oldPath: target.oldPath,
      note: ''
    }))
    if (prefill.sourceKind === 'DIFF' && prefill.commitSha) {
      await loadCommitDetail(prefill.commitSha)
    }
    await refreshBranchOptions()
    await nextTick()
    restoring.value = false
    return true
  } catch {
    restoring.value = false
    return false
  }
}

// 来源身份一变化立即失效；异步监听器随后读取新身份。
watch(() => [form.projectId, form.branch, form.sourceKind], () => {
  headEpoch++
  commitsEpoch++
  clearCommitLookup()
  detailEpoch++
  if (restoring.value) return
  form.observedSha = ''
  lastObservedSha = ''
  form.commitSha = ''
  form.baseSha = ''
  form.targets = []
  commitDetail.value = null
  commitDetailLoading.value = false
  commitDetailError.value = ''
  commits.value = []
  commitsPage.value = 0
  commitsHasMore.value = false
  commitsError.value = ''
  failedCommitPage.value = null
  commitsLoading.value = false
  commitsLoadingMore.value = false
  commitPickerOpen.value = false
  scopeOpen.value = false
}, { flush: 'sync' })

watch(
  () => form.projectId,
  async (projectId) => {
    if (restoring.value || !projectId) {
      return
    }
    const project = projects.value.find((item) => item.id === projectId)
    if (!project) return
    form.targets = []
    form.commitSha = ''
    form.baseSha = ''
    commits.value = []
    commitsPage.value = 0
    commitsHasMore.value = false
    commitsError.value = ''
    failedCommitPage.value = null
    commitDetail.value = null
    await syncBranches(project.currentBranch || '')
  }
)

watch(
  () => form.specialistType,
  (type) => {
    if (restoring.value) {
      return
    }
    if (type !== 'ISSUE' && form.sourceKind === 'DIFF') {
      form.sourceKind = 'FILES'
      form.targets = []
      form.commitSha = ''
    }
  }
)

watch(
  () => form.sourceKind,
  async (kind) => {
    if (restoring.value) {
      return
    }
    form.targets = []
    form.commitSha = ''
    form.baseSha = ''
    commitDetail.value = null
    if (kind === 'FILES') {
      await resolveHead()
    } else {
      await loadCommits()
    }
  }
)

async function loadOptions() {
  optionsLoading.value = true
  try {
    const [projectPage, modelPage, allPrompts] = await Promise.all([
      projectsApi.list({ limit: 100 }),
      modelsApi.list({ limit: 100 }),
      loadAllPrompts()
    ])
    projects.value = projectPage.items
    models.value = modelPage.items
    prompts.value = allPrompts
  } catch (failure) {
    formError.value = (failure as Error).message || '配置加载失败，请稍后重试'
  } finally {
    optionsLoading.value = false
  }
}

async function loadAllPrompts(): Promise<PromptSummary[]> {
  const items = new Map<string, PromptSummary>()
  const cursors = new Set<string>()
  let cursor: string | undefined
  do {
    const page = await promptsApi.list({ limit: 100, ...(cursor ? { cursor } : {}) })
    for (const item of page.items) items.set(item.id, item)
    if (!page.hasMore) return [...items.values()]
    if (!page.nextCursor || cursors.has(page.nextCursor)) {
      throw new Error('通用规则分页异常，请重新加载')
    }
    cursor = page.nextCursor
    cursors.add(cursor)
  } while (true)
}

async function loadCopy() {
  if (!copyFrom.value) {
    return
  }
  restoring.value = true
  try {
    const [copy, original] = await Promise.all([
      tasksApi.copyInput(copyFrom.value),
      tasksApi.detail(copyFrom.value)
    ])
    form.projectId = copy.projectId
    form.specialistType = copy.specialistType
    form.sourceKind = copy.sourceKind
    form.branch = copy.branch || form.branch
    const copiedDefault = `审查任务 ${copyFrom.value}`
    form.name = copy.name && copy.name !== copiedDefault ? copy.name : ''
    form.commitSha = copy.commitSha || ''
    form.baseSha = copy.baseSha || ''
    form.targets = copy.targets.map((target) => ({
      path: target.path,
      oldPath: target.oldPath,
      note: target.note || ''
    }))
    form.modelConfigId = copy.modelConfigId || ''
    form.promptId = copy.promptId || ''
    form.promptVersionId = copy.promptVersionId || ''
    form.smartSplit = copy.smartSplit
    await loadPromptSelection(copy.promptId || '', copy.promptVersionId)
    await refreshBranchOptions()
    if (copy.sourceKind === 'FILES' && copy.reobserveHead) {
      await resolveHead()
      if (original.headSha && form.observedSha && form.observedSha !== original.headSha) {
        form.targets = []
        copyHeadMoved.value = true
      }
    } else if (copy.sourceKind === 'DIFF') {
      await loadCommitDetail(copy.commitSha || '')
    }
  } catch (failure) {
    formError.value = (failure as Error).message || '复制历史参数失败'
  } finally {
    await nextTick()
    restoring.value = false
  }
}

async function onProjectChange() {
  // watcher 负责重新读取版本和清空范围
}

function closeLaunch() {
  router.push('/reviews')
}

async function refreshBranchOptions() {
  if (!form.projectId) return
  const epoch = ++branchEpoch
  const projectId = form.projectId
  branchesLoading.value = true
  try {
    const loaded = await repositoryApi.branches(projectId)
    if (epoch !== branchEpoch || projectId !== form.projectId) return
    branches.value = Array.isArray(loaded) ? loaded : []
  } catch (failure) {
    if (epoch !== branchEpoch || projectId !== form.projectId) return
    if (!form.branch) formError.value = (failure as Error).message || '分支列表读取失败'
  } finally {
    if (epoch === branchEpoch) branchesLoading.value = false
  }
}

async function syncBranches(preferred: string) {
  if (!form.projectId) return
  const epoch = ++branchEpoch
  const projectId = form.projectId
  branchesLoading.value = true
  try {
    const loaded = await repositoryApi.branches(projectId)
    if (epoch !== branchEpoch || projectId !== form.projectId || restoring.value) return
    branches.value = Array.isArray(loaded) ? loaded : []
    const next = preferred && branches.value.includes(preferred) ? preferred : (branches.value[0] || '')
    if (!next) {
      form.branch = ''
      formError.value = '该项目没有可选择的分支'
      return
    }
    formError.value = ''
    form.branch = next
    if (form.sourceKind === 'FILES') await resolveHead()
    else await loadCommits()
  } catch (failure) {
    if (epoch !== branchEpoch || projectId !== form.projectId || restoring.value) return
    branches.value = []
    form.branch = ''
    formError.value = (failure as Error).message || '分支列表读取失败'
  } finally {
    if (epoch === branchEpoch) branchesLoading.value = false
  }
}

async function onTypeChange() {
  // watcher 负责清理不适用范围
}

async function onSourceKindChange() {
  // watcher 负责重新读取版本和清空范围
}

async function onBranchChange() {
  form.targets = []
  if (form.sourceKind === 'FILES') {
    await resolveHead()
  } else {
    await loadCommits()
  }
}

async function resolveHead() {
  if (!form.projectId || !form.branch) {
    form.observedSha = ''
    return
  }
  const epoch = ++headEpoch
  const previousSha = form.observedSha || lastObservedSha
  const projectId = form.projectId
  const branch = form.branch
  headLoading.value = true
  form.observedSha = ''
  formError.value = ''
  try {
    const sha = await repositoryApi.head(projectId, branch)
    if (epoch !== headEpoch) return
    if (previousSha && previousSha !== sha) {
      form.targets = []
      copyHeadMoved.value = true
    }
    form.observedSha = sha
    lastObservedSha = sha
  } catch (failure) {
    if (epoch !== headEpoch) return
    form.observedSha = ''
    formError.value = (failure as Error).message || '分支版本读取失败'
  } finally {
    if (epoch === headEpoch) headLoading.value = false
  }
}

async function loadCommitPage(pageNumber: number, replace: boolean): Promise<boolean> {
  if (!form.projectId || !form.branch || commitsLoading.value || commitsLoadingMore.value) {
    return false
  }
  const epoch = ++commitsEpoch
  const projectId = form.projectId
  const branch = form.branch
  if (replace) commitsLoading.value = true
  else commitsLoadingMore.value = true
  commitsError.value = ''
  try {
    const page = await repositoryApi.commits(projectId, branch, pageNumber, 30)
    if (epoch !== commitsEpoch || projectId !== form.projectId || branch !== form.branch) return false
    const merged = replace ? page.commits : [...commits.value, ...page.commits]
    commits.value = Array.from(new Map(merged.map((commit) => [commit.sha, commit])).values())
    commitsPage.value = pageNumber
    commitsHasMore.value = Boolean(page.hasMore)
    failedCommitPage.value = null
    return true
  } catch (failure) {
    if (epoch !== commitsEpoch || projectId !== form.projectId || branch !== form.branch) return false
    commitsError.value = (failure as Error).message || '提交列表读取失败'
    failedCommitPage.value = { page: pageNumber, replace }
    return false
  } finally {
    if (epoch === commitsEpoch) {
      if (replace) commitsLoading.value = false
      else commitsLoadingMore.value = false
    }
  }
}

async function loadCommits() {
  await loadCommitPage(1, true)
}

async function loadMoreCommits() {
  if (!commitsHasMore.value || commitsError.value) return
  await loadCommitPage(commitsPage.value + 1, false)
}

async function retryCommits() {
  const failed = failedCommitPage.value
  if (failed) {
    await loadCommitPage(failed.page, failed.replace)
    return
  }
  await loadCommitPage(commitsPage.value ? commitsPage.value + 1 : 1, commitsPage.value === 0)
}

function openCommitPicker() {
  commitPickerOpen.value = true
  if (!commits.value.length && !commitsLoading.value && !commitsError.value) {
    void loadCommits()
  }
}

async function confirmCommit(sha: string) {
  commitPickerOpen.value = false
  if (form.commitSha === sha && commitDetail.value?.sha === sha) return
  form.commitSha = sha
  await onCommitChange()
}

function clearCommitLookup() {
  commitLookupEpoch++
  locatedCommit.value = null
  commitLookupLoading.value = false
  commitLookupError.value = ''
}

async function locateCommit(sha: string) {
  if (!form.projectId || !commitPickerOpen.value || !/^[a-f0-9]{40}$/i.test(sha)) return
  const epoch = ++commitLookupEpoch
  const projectId = form.projectId
  locatedCommit.value = null
  commitLookupError.value = ''
  commitLookupLoading.value = true
  try {
    const detail = await repositoryApi.commit(projectId, sha)
    if (epoch !== commitLookupEpoch || projectId !== form.projectId || !commitPickerOpen.value) return
    locatedCommit.value = { sha: detail.sha, message: detail.message, author: detail.author, date: detail.date }
  } catch (failure) {
    if (epoch !== commitLookupEpoch || projectId !== form.projectId || !commitPickerOpen.value) return
    commitLookupError.value = (failure as Error).message || '无法定位提交，请核对完整 SHA'
  } finally {
    if (epoch === commitLookupEpoch) commitLookupLoading.value = false
  }
}

async function onCommitChange() {
  form.targets = []
  await loadCommitDetail(form.commitSha)
}

async function loadCommitDetail(sha: string) {
  const epoch = ++detailEpoch
  commitDetail.value = null
  commitDetailError.value = ''
  form.baseSha = ''
  if (!form.projectId || !sha) {
    commitDetailLoading.value = false
    return
  }
  commitDetailLoading.value = true
  try {
    const detail = await repositoryApi.commit(form.projectId, sha)
    if (epoch !== detailEpoch || form.commitSha !== sha) return
    commitDetail.value = detail
    form.baseSha = commitDetail.value.parents?.[0] || ''
  } catch (failure) {
    if (epoch !== detailEpoch) return
    commitDetailError.value = (failure as Error).message || '提交详情读取失败'
  } finally {
    if (epoch === detailEpoch) commitDetailLoading.value = false
  }
}

async function loadPromptSelection(promptId: string, preferredVersionId?: string | null) {
  const epoch = ++promptEpoch
  promptError.value = ''
  selectedPrompt.value = null
  selectedPromptVersion.value = null
  promptVersions.value = []
  form.promptVersionId = preferredVersionId || ''
  if (!promptId) {
    promptLoading.value = false
    return
  }

  promptLoading.value = true
  try {
    const selection = await readPromptSelection(promptId, preferredVersionId || undefined, true)
    if (epoch !== promptEpoch) return
    if (!isPromptApplicable(selection.prompt.purpose, 'SPECIALIST')) {
      throw new Error('所选提示词须为专项审查或通用用途，请重新选择')
    }
    selectedPrompt.value = selection.prompt
    promptVersions.value = selection.versions
    if (selection.version) {
      form.promptVersionId = selection.version.id
      selectedPromptVersion.value = selection.version
    } else if (preferredVersionId) {
      form.promptVersionId = preferredVersionId
      promptError.value = unavailablePromptVersion
    } else {
      promptError.value = '该通用规则没有可用的正文版本，请先到提示词管理补充版本'
    }
  } catch (failure) {
    if (epoch !== promptEpoch) return
    promptError.value = (failure as Error).message || '读取通用规则失败，请重试'
  } finally {
    if (epoch === promptEpoch) promptLoading.value = false
  }
}

function onScopeConfirm(selection: { path: string; oldPath: string | null }[]) {
  const previous = new Map(form.targets.map((target) => [target.path, target]))
  form.targets = selection.map((item) => {
    const existing = previous.get(item.path)
    return {
      path: item.path,
      oldPath: item.oldPath,
      note: existing?.note || ''
    }
  })
}

function removeTarget(index: number) {
  form.targets.splice(index, 1)
}

async function refreshMovedSource() {
  sourceMoved.value = false
  form.targets = []
  await resolveHead()
}

async function submit() {
  if (!canSubmit.value) {
    formError.value = '请补全项目、目标文件、模型及固定版本；所选通用规则须有可用版本'
    return
  }
  submitting.value = true
  formError.value = ''
  sourceMoved.value = false
  try {
    const source =
      form.sourceKind === 'FILES'
        ? { kind: 'FILES' as const, branch: form.branch, observedSha: form.observedSha }
        : {
            kind: 'DIFF' as const,
            branch: form.branch,
            commitSha: form.commitSha,
            baseSha: form.baseSha || undefined
          }
    const name = form.name.trim()
    const body = {
      projectId: form.projectId,
      specialistType: form.specialistType,
      ...(name ? { name } : {}),
      source,
      targets: form.targets.map((target) => ({
        path: target.path, oldPath: target.oldPath, note: target.note || undefined
      })),
      modelConfigId: form.modelConfigId,
      ...(form.promptId ? { promptId: form.promptId, promptVersionId: form.promptVersionId } : {}),
      smartSplit: form.smartSplit
    }
    const fingerprint = JSON.stringify(body)
    if (!submission || submission.fingerprint !== fingerprint) {
      submission = { fingerprint, key: idempotencyKey() }
    }
    const created = await tasksApi.create(body, submission.key)
    message.success('任务已受理')
    router.push({ path: '/reviews', query: { created: created.id } })
  } catch (failure) {
    const error = failure as Error & { code?: string }
    if (error.code === 'SOURCE_MOVED') {
      sourceMoved.value = true
      formError.value = ''
    } else {
      formError.value = error.message || '提交失败'
    }
  } finally {
    submitting.value = false
  }
}

function idempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `task-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
</script>

<style scoped lang="less">
.form-grid :deep(.ant-form-item) {
  margin-bottom: 0;
}
.type-switch,
.source-switch,
.full {
  grid-column: 1 / -1;
}
.type-switch :deep(.ant-radio-group),
.source-switch :deep(.ant-radio-group) {
  display: flex;
  flex-flow: row nowrap;
  width: 100%;
}
.type-switch :deep(.ant-radio-button-wrapper),
.source-switch :deep(.ant-radio-button-wrapper) {
  flex: 1 1 0;
  text-align: center;
}
/* 两个选项各占一栏；整组取专项类型两栏宽，与上方三等分栏对齐。 */
.source-switch :deep(.ant-radio-group) {
  width: calc(200% / 3);
}
.muted {
  color: var(--muted);
}
.small {
  font-size: 12px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 24px;
}
.target-actions,
.note-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.commit-picker-field {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
}
.selected-commit {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}
.selected-commit code {
  flex: 0 0 auto;
}
.selected-commit span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.commit-help {
  margin: 7px 0 0;
}
.target-list {
  margin-top: 10px;
  border: 1px solid var(--line);
  border-radius: 14px;
  max-height: 240px;
  overflow: auto;
}
.target-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid #edf1f6;
}
.target-row:last-child {
  border-bottom: 0;
}
.target-main {
  min-width: 0;
  overflow-wrap: anywhere;
}
.notes {
  margin-top: 16px;
  display: grid;
  gap: 10px;
}
.file-note {
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 12px 14px;
}
.note-head {
  justify-content: space-between;
  margin-bottom: 8px;
}
.switch-line {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  margin-top: 18px;
  padding: 13px 15px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 14px;
  cursor: pointer;
}
.switch-line strong {
  display: block;
  font-size: 13px;
}
</style>
