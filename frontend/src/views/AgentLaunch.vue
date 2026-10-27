<template>
  <LaunchModalShell
    :open="open"
    :mask-closable="true"
    :title="copyFrom ? '复制参数发起新 Agent 分析' : '发起 Agent 分析'"
    @cancel="close"
  >
    <template #alerts>
      <a-alert
        v-if="!agentModels.length"
        type="warning"
        show-icon
        message="没有已通过 Agent 工具协议验证的模型"
        description="请先在模型管理中执行「Agent 验证」。连通性验证不能替代工具协议验证。"
      />
      <a-alert
        v-if="copyFrom"
        type="info"
        show-icon
        message="已带入原任务参数。请核对当前分支版本、范围和模型；确认后才会创建新任务。"
      />
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-alert v-if="headError" type="error" show-icon :message="headError" />
      <a-alert v-if="headMoved" type="warning" show-icon message="分支或版本已变化，已清空原文件范围；请重新选择。" />
    </template>

    <template #body>
      <LaunchFormSections :titles="{ goal: '基本信息', scope: '范围', extra: '要求' }">
        <template #goal>
          <a-form-item>
            <template #label>任务名称 <span class="muted small">（可选）</span></template>
            <a-input v-model:value="form.name" :maxlength="200" placeholder="例如：支付超时后的重试链路" />
          </a-form-item>
          <a-row :gutter="16">
            <a-col :xs="24" :md="12">
              <a-form-item label="项目" required>
                <a-select
                  :get-popup-container="getPopupContainer"
                  v-model:value="form.projectId"
                  show-search
                  option-filter-prop="label"
                  :options="projectOptions"
                  placeholder="选择项目"
                  @change="onProjectChange"
                />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-item label="模型" required>
                <a-select
                  :get-popup-container="getPopupContainer"
                  v-model:value="form.modelConfigId"
                  :options="modelOptions"
                  placeholder="选择模型"
                />
              </a-form-item>
            </a-col>
          </a-row>
        </template>

        <template #scope>
          <a-form-item label="范围类型" required>
            <a-radio-group v-model:value="form.kind" button-style="solid" @change="onKindChange">
              <a-radio-button value="TEXT">文字描述</a-radio-button>
              <a-radio-button value="FILES">文件/目录</a-radio-button>
              <a-radio-button value="COMMITS">提交比较</a-radio-button>
            </a-radio-group>
          </a-form-item>

          <a-row v-if="form.kind !== 'COMMITS'" :gutter="16">
            <a-col :xs="24" :md="12">
              <a-form-item label="分支" required>
                <a-select
                  :get-popup-container="getPopupContainer"
                  v-model:value="form.branch"
                  :options="branchOptions"
                  :loading="branchLoading"
                  style="width: 100%"
                  placeholder="选择分支"
                  @change="onHeadBranchChange"
                />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-item label="提交hash" required>
                <LaunchVersionPreview
                  :sha="form.observedSha"
                  :loading="headLoading"
                  :loading-text="form.observedSha || '读取中…'"
                  empty-text="读取中…"
                  refresh-text="重新读取"
                  @refresh="loadHead"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item v-if="form.kind === 'TEXT'" label="范围描述" required>
            <a-textarea
              v-model:value="form.description"
              :rows="3"
              :maxlength="2000"
              placeholder="说明要调查的链路、入口或问题范围"
            />
          </a-form-item>

          <a-form-item v-if="form.kind === 'FILES'" label="目标" required>
            <a-space wrap>
              <a-button :disabled="!form.observedSha" @click="scopeOpen = true">选择文件/目录</a-button>
              <span class="muted">已选 {{ form.paths.length }} 项，最多 100 项</span>
            </a-space>
            <div v-if="form.paths.length" class="target-list">
              <div v-for="path in form.paths" :key="path" class="target-row">
                <div class="target-main"><code>{{ path }}</code></div>
                <a-button size="small" danger type="text" @click="removePath(path)">移除</a-button>
              </div>
            </div>
          </a-form-item>

          <a-row v-if="form.kind === 'COMMITS'" :gutter="16">
            <a-col :xs="24" :md="12">
              <a-form-item label="分支" required>
                <a-select
                  :get-popup-container="getPopupContainer"
                  v-model:value="form.branch"
                  :options="branchOptions"
                  :loading="branchLoading"
                  style="width: 100%"
                  placeholder="选择分支"
                  @change="onBranchChange"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item v-if="form.kind === 'COMMITS'" label="目标" required>
            <a-space wrap>
              <a-button :disabled="!form.branch" @click="openCommitPicker">选择提交</a-button>
              <span class="muted">已选 {{ form.commits.length }} 项，最多 20 项</span>
<!--              <span class="muted">按选择顺序逐项读取，最多 20 个；不合并成首尾净差异</span>-->
            </a-space>
            <div v-if="form.commits.length" class="target-list">
              <div v-for="(sha, index) in form.commits" :key="sha" class="target-row">
                <div class="target-main"><code>C{{ index + 1 }} · {{ sha.slice(0, 10) }}</code></div>
                <a-button size="small" danger type="text" @click="toggleCommit(sha)">移除</a-button>
              </div>
            </div>
          </a-form-item>
        </template>

        <template #extra>
          <a-row :gutter="16">
            <a-col :xs="24" :md="12">
              <a-form-item>
                <template #label>通用规则 <span class="muted small">（可选）</span></template>
                <a-select :get-popup-container="getPopupContainer" v-model:value="form.guidancePromptId" allow-clear :options="promptOptions" />
              </a-form-item>
            </a-col>
            <a-col v-if="form.guidancePromptId" :xs="24" :md="12">
              <a-form-item label="提示词版本" required>
                <PromptVersionSelect
                  v-model:value="form.guidancePromptVersionId"
                  :versions="guidancePromptVersions"
                  :current-version-id="selectedGuidancePrompt?.currentVersion?.id"
                  :loading="guidancePromptLoading"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <PromptInfoPreview
            v-if="form.guidancePromptId"
            class="mb16"
            title="所选版本正文"
            :prompt="selectedGuidancePrompt"
            :version="selectedGuidanceVersion"
            selected
            :loading="guidancePromptLoading"
            :error="guidancePromptError"
            @retry="loadGuidancePrompt()"
          />
<!--          <p v-if="form.guidancePromptId" class="muted">任务创建时保存所选版本的正文；覆盖版本会影响之后创建的任务。</p>-->
          <a-form-item label="本次要求" required>
            <a-textarea
              v-model:value="form.requirements"
              :maxlength="4000"
              show-count
              :auto-size="{ minRows: 4, maxRows: 8 }"
              placeholder="核对重试路径的幂等约定，并说明材料缺口。"
            />
          </a-form-item>
        </template>
      </LaunchFormSections>
    </template>

    <template #footer>
      <a-button @click="close">取消</a-button>
      <a-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">开始 Agent 分析</a-button>
    </template>

    <template #overlay>
      <ScopePickerModal
        v-model:open="scopeOpen"
        :project-id="form.projectId"
        mode="FILES"
        :sha="form.observedSha"
        :branch="form.branch"
        :selected="form.paths"
        @confirm="onScopeConfirm"
      />
      <CommitPickerModal
        v-model:open="commitPickerOpen"
        :branch="form.branch"
        :commits="commits"
        multiple
        :selected-shas="form.commits"
        :max-selections="20"
        :has-more="commitsHasMore"
        :loading="commitLoading"
        :loading-more="commitLoadingMore"
        :error="commitsError"
        :located-commit="locatedCommit"
        :lookup-loading="commitLookupLoading"
        :lookup-error="commitLookupError"
        @confirm-many="confirmCommits"
        @load-more="loadMoreCommits"
        @refresh="refreshCommits"
        @retry="refreshCommits"
        @lookup="locateCommit"
        @clear-lookup="clearCommitLookup"
      />
    </template>
  </LaunchModalShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import CommitPickerModal from '@/components/CommitPickerModal.vue'
import ScopePickerModal from '@/components/ScopePickerModal.vue'
import PromptInfoPreview from '@/components/PromptInfoPreview.vue'
import {
  agentTasksApi,
  modelsApi,
  projectsApi,
  promptsApi,
  repositoryApi,
  type CommitInfo,
  type ModelView,
  type ProjectView,
  type PromptSummary,
  type PromptVersion,
  type PromptView
} from '@/api/v2'

import PromptVersionSelect from '@/components/PromptVersionSelect.vue'
import { readPromptSelection, unavailablePromptVersion } from '@/utils/promptSelection'

withDefaults(defineProps<{ open?: boolean }>(), { open: true })
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const router = useRouter()
const route = useRoute()
const getPopupContainer = (trigger: HTMLElement) => trigger.closest('.ant-modal-body') as HTMLElement || document.body
const copyFrom = computed(() => String(route.query.copyFrom || ''))

const projectOptions = ref<Array<{ label: string; value: string }>>([])
const projects = ref<ProjectView[]>([])
const agentModels = ref<ModelView[]>([])
const prompts = ref<PromptSummary[]>([])
const selectedGuidancePrompt = ref<PromptView | null>(null)
const guidancePromptVersions = ref<PromptVersion[]>([])
const selectedGuidanceVersion = computed(() => guidancePromptVersions.value.find(version => version.id === form.guidancePromptVersionId) || null)
const guidancePromptLoading = ref(false)
const guidancePromptError = ref('')
let guidancePromptEpoch = 0
const branches = ref<string[]>([])
const commits = ref<CommitInfo[]>([])
const headLoading = ref(false)
const headError = ref('')
const headMoved = ref(false)
let headEpoch = 0
let lastObservedSha = ''
let lastHeadProject = ''
let lastHeadBranch = ''
const branchLoading = ref(false)
const commitLoading = ref(false)
const commitLoadingMore = ref(false)
const commitsHasMore = ref(false)
const commitsError = ref('')
const commitPickerOpen = ref(false)
const locatedCommit = ref<CommitInfo | null>(null)
const commitLookupLoading = ref(false)
const commitLookupError = ref('')
let commitsPage = 0
let commitsEpoch = 0
let commitLookupEpoch = 0
const submitting = ref(false)
const scopeOpen = ref(false)
const error = ref('')

const form = reactive({
  projectId: '',
  name: '',
  modelConfigId: '',
  guidancePromptId: undefined as string | undefined,
  guidancePromptVersionId: undefined as string | undefined,
  kind: 'FILES' as 'TEXT' | 'FILES' | 'COMMITS',
  branch: '' as string,
  observedSha: '',
  paths: [] as string[],
  commits: [] as string[],
  description: '',
  requirements: ''
})

const modelOptions = computed(() =>
  agentModels.value.map((model) => ({
    label: `${model.name} · ${model.modelName}`,
    value: model.id
  }))
)
const promptOptions = computed(() =>
  prompts.value.map((prompt) => ({ label: prompt.name, value: prompt.id }))
)
const branchOptions = computed(() => branches.value.map((branch) => ({ label: branch, value: branch })))
watch(() => form.guidancePromptId, () => {
  form.guidancePromptVersionId = undefined
  void loadGuidancePrompt()
}, { flush: 'sync' })
watch(() => form.guidancePromptVersionId, () => {
  if (selectedGuidanceVersion.value?.content?.trim()) guidancePromptError.value = ''
}, { flush: 'sync' })

async function loadGuidancePrompt() {
  const id = form.guidancePromptId
  const epoch = ++guidancePromptEpoch
  selectedGuidancePrompt.value = null
  guidancePromptVersions.value = []
  guidancePromptError.value = ''
  guidancePromptLoading.value = false
  if (!id) return

  guidancePromptLoading.value = true
  try {
    const { prompt, versions, version } = await readPromptSelection(id, form.guidancePromptVersionId, true)
    if (epoch === guidancePromptEpoch) {
      selectedGuidancePrompt.value = prompt
      guidancePromptVersions.value = versions
      if (!version) {
        guidancePromptError.value = unavailablePromptVersion
        return
      }
      form.guidancePromptVersionId = version.id
    }
  } catch (failure) {
    if (epoch === guidancePromptEpoch) {
      guidancePromptError.value = (failure as Error)?.message || '读取指导提示词失败，请重试。'
    }
  } finally {
    if (epoch === guidancePromptEpoch) guidancePromptLoading.value = false
  }
}

const canSubmit = computed(() => {
  if (!form.projectId || !form.modelConfigId || !form.requirements.trim()) return false
  if (form.guidancePromptId && (guidancePromptLoading.value || guidancePromptError.value || !selectedGuidanceVersion.value)) return false
  if (form.kind === 'TEXT') return Boolean(form.description.trim() && form.observedSha)
  if (form.kind === 'FILES') return form.paths.length > 0 && Boolean(form.observedSha)
  return form.commits.length > 0
})

function closeDropdownOnScroll(event: Event) {
  const target = event.target
  if (!(target instanceof Element) || !target.closest('.launch-modal .ant-modal-body')) return
  const active = document.activeElement
  if (active instanceof HTMLElement && active.closest('.ant-select')) active.blur()
}

onMounted(() => window.addEventListener('scroll', closeDropdownOnScroll, true))
onBeforeUnmount(() => {
  window.removeEventListener('scroll', closeDropdownOnScroll, true)
  guidancePromptEpoch++
  headEpoch++
})

onMounted(async () => {
  try {
    const [projectItems, modelItems, promptItems] = await Promise.all([
      loadAllPages((pageNum) => projectsApi.list({ pageNum, pageSize: 50 })),
      loadAllPages((pageNum) => modelsApi.list({ pageNum, pageSize: 50 })),
      loadAllPages((pageNum) => promptsApi.list({ pageNum, pageSize: 50 }))
    ])
    projects.value = projectItems
    projectOptions.value = projectItems.map((project) => ({
      label: project.name,
      value: project.id
    }))
    agentModels.value = modelItems.filter(
      (model) => model.agentValidationStatus === 'PASSED'
    )
    prompts.value = promptItems.filter((prompt) => prompt.purpose === 'AGENT')
    if (!form.projectId && projects.value.length) {
      await selectProject(projects.value[0].id)
    }
    if (!form.modelConfigId && agentModels.value.length) {
      form.modelConfigId = agentModels.value[0].id
    }
    if (copyFrom.value) {
      await loadCopyInput()
    }
  } catch (e) {
    error.value = `加载基础数据失败：${(e as Error).message}`
  }
})

async function loadCopyInput() {
  try {
    const copy = await agentTasksApi.copyInput(copyFrom.value)
    form.projectId = copy.projectId
    form.name = copy.name || ''
    form.modelConfigId = copy.modelConfigId
    form.guidancePromptId = copy.guidancePromptId || undefined
    form.guidancePromptVersionId = copy.guidancePromptVersionId || undefined
    if (form.guidancePromptId) await loadGuidancePrompt()
    form.kind = copy.scope.kind
    form.description = copy.scope.description || ''
    form.requirements = copy.requirements

    await selectProject(copy.projectId)
    if (copy.scope.branch && branches.value.includes(copy.scope.branch)) {
      form.branch = copy.scope.branch
      if (copy.scope.kind === 'COMMITS') await loadCommits()
      else await loadHead()
    }
    form.paths = [...(copy.scope.paths || [])]
    form.commits = [...(copy.scope.commits || [])]
  } catch (e) {
    error.value = `读取历史参数失败：${(e as Error).message}`
  }
}

async function onProjectChange(value: string) {
  await selectProject(value)
}

async function loadAllPages<T>(fetchPage: (pageNum: number) => Promise<{ items: T[]; hasMore: boolean }>): Promise<T[]> {
  const items: T[] = []
  let pageNum = 1
  while (true) {
    const page = await fetchPage(pageNum++)
    items.push(...page.items)
    if (!page.hasMore) return items
  }
}

function close() {
  emit('update:open', false)
  if (route.path === '/agents/new') router.push('/reviews')
}

function onBranchChange() {
  commitsEpoch++
  form.commits = []
  commits.value = []
  commitsPage = 0
  commitsHasMore.value = false
  commitsError.value = ''
  commitLoading.value = false
  commitLoadingMore.value = false
  clearCommitLookup()
  if (form.branch && form.kind === 'COMMITS') void loadCommits()
}

async function selectProject(projectId: string) {
  headEpoch++
  headLoading.value = false
  headError.value = ''
  headMoved.value = false
  lastObservedSha = ''
  lastHeadProject = ''
  lastHeadBranch = ''
  scopeOpen.value = false
  form.projectId = projectId
  form.paths = []
  form.commits = []
  form.observedSha = ''
  commits.value = []
  commitsPage = 0
  commitsHasMore.value = false
  commitsError.value = ''
  commitsEpoch++
  commitLoading.value = false
  commitLoadingMore.value = false
  clearCommitLookup()
  const project = projects.value.find((item) => item.id === projectId)
  try {
    branchLoading.value = true
    branches.value = await repositoryApi.branches(projectId)
    const preferred = project?.currentBranch
    form.branch = preferred && branches.value.includes(preferred)
      ? preferred
      : branches.value[0] || ''
    if (form.branch) {
      await loadHead()
      if (form.kind === 'COMMITS') {
        await loadCommits()
      }
    }
  } catch (e) {
    error.value = `读取分支失败：${(e as Error).message}`
  } finally {
    branchLoading.value = false
  }
}

async function loadHead() {
  const epoch = ++headEpoch
  const projectId = form.projectId
  const branch = form.branch
  const sameContext = projectId === lastHeadProject && branch === lastHeadBranch
  const previousSha = sameContext ? (form.observedSha || lastObservedSha) : ''
  if (!sameContext) {
    lastObservedSha = ''
    lastHeadProject = projectId
    lastHeadBranch = branch
  }
  scopeOpen.value = false
  form.observedSha = ''
  headError.value = ''
  if (!form.projectId || !form.branch) {
    headLoading.value = false
    return
  }
  headLoading.value = true
  try {
    const sha = await repositoryApi.head(projectId, branch)
    if (epoch !== headEpoch || projectId !== form.projectId || branch !== form.branch) return
    if (previousSha && previousSha !== sha && form.paths.length) {
      form.paths = []
      headMoved.value = true
    }
    form.observedSha = sha
    lastObservedSha = sha
  } catch (e) {
    if (epoch !== headEpoch || projectId !== form.projectId || branch !== form.branch) return
    headError.value = `读取分支版本失败：${(e as Error).message}`
  } finally {
    if (epoch === headEpoch) headLoading.value = false
  }
}

function onHeadBranchChange() {
  if (form.paths.length) {
    form.paths = []
    headMoved.value = true
  }
  void loadHead()
}

async function loadCommits(reset = true) {
  if (!form.projectId || !form.branch) return
  if (reset) {
    commitsPage = 1
    commitLoading.value = true
  } else {
    commitLoadingMore.value = true
  }
  const epoch = ++commitsEpoch
  const projectId = form.projectId
  const branch = form.branch
  try {
    const pageNumber = reset ? 1 : commitsPage + 1
    const page = await repositoryApi.commits(projectId, branch, pageNumber, 30)
    if (epoch !== commitsEpoch || projectId !== form.projectId || branch !== form.branch) return
    commits.value = reset ? page.commits : [...commits.value, ...page.commits]
    commitsPage = pageNumber
    commitsHasMore.value = page.hasMore
    commitsError.value = ''
  } catch (e) {
    if (epoch === commitsEpoch) commitsError.value = (e as Error).message || '读取提交失败'
  } finally {
    if (epoch === commitsEpoch) {
      commitLoading.value = false
      commitLoadingMore.value = false
    }
  }
}

async function openCommitPicker() {
  commitPickerOpen.value = true
  if (!commits.value.length) await loadCommits()
}

function confirmCommits(shas: string[]) {
  commitPickerOpen.value = false
  form.commits = [...shas].slice(0, 20)
}

async function loadMoreCommits() {
  if (commitLoading.value || commitLoadingMore.value || !commitsHasMore.value) return
  await loadCommits(false)
}

async function refreshCommits() {
  await loadCommits()
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

function onKindChange() {
  error.value = ''
  if (form.kind === 'COMMITS') {
    headEpoch++
    headLoading.value = false
    headError.value = ''
    headMoved.value = false
    form.observedSha = ''
    lastObservedSha = ''
    scopeOpen.value = false
    form.paths = []
    if (!commits.value.length) {
      void loadCommits()
    }
  } else {
    form.commits = []
    if (!form.observedSha && form.branch) {
      void loadHead()
    }
  }
}

function onScopeConfirm(entries: Array<{ path: string }>) {
  if (headLoading.value || !form.observedSha) return
  form.paths = entries.map((entry) => entry.path).slice(0, 100)
  headMoved.value = false
}

function removePath(path: string) {
  form.paths = form.paths.filter((item) => item !== path)
}

function toggleCommit(sha: string) {
  const index = form.commits.indexOf(sha)
  if (index >= 0) {
    form.commits.splice(index, 1)
    return
  }
  if (form.commits.length >= 20) {
    message.warning('最多选择 20 个提交')
    return
  }
  form.commits.push(sha)
}

async function submit() {
  if (!canSubmit.value) {
    message.warning('请先补齐必填项')
    return
  }
  submitting.value = true
  error.value = ''
  const body = {
    projectId: form.projectId,
    name: form.name.trim() || undefined,
    modelConfigId: form.modelConfigId,
    guidancePromptId: form.guidancePromptId || undefined,
    guidancePromptVersionId: form.guidancePromptId ? form.guidancePromptVersionId : undefined,
    requirements: form.requirements.trim(),
    scope: {
      kind: form.kind,
      branch: form.kind === 'COMMITS' ? undefined : form.branch,
      observedSha: form.kind === 'COMMITS' ? undefined : form.observedSha,
      paths: form.kind === 'FILES' ? form.paths : undefined,
      commits: form.kind === 'COMMITS' ? form.commits : undefined,
      description: form.kind === 'TEXT' ? form.description.trim() : undefined
    }
  }
  try {
    const created = await agentTasksApi.create(body, crypto.randomUUID())
    message.success('Agent 调查已受理')
    router.push({ path: `/reviews/${created.id}`, query: { from: '/agents/new' } })
  } catch (e) {
    error.value = `发起失败：${(e as Error).message}`
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.mb16 { margin-bottom: 16px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
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
.target-row:last-child { border-bottom: 0; }
.target-main {
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
