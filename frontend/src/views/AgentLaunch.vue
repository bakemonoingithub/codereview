<template>
  <a-modal
    :open="open"
    :title="copyFrom ? '复制参数发起新 Agent 分析' : '发起 Agent 分析'"
    width="min(1040px, calc(100vw - 32px))"
    :footer="null"
    :destroy-on-close="false"
    wrap-class-name="agent-launch-modal"
    @cancel="close"
  >
  <div class="agent-launch">
    <div class="page-head">
      <div>
        <p class="muted">描述范围与要求。模型只读调查固定版本，命中限制会保留记录但不发布完整结果。</p>
      </div>
    </div>

    <a-alert
      v-if="!agentModels.length"
      type="warning"
      show-icon
      message="没有已通过 Agent 工具协议验证的模型"
      description="请先在模型管理中执行「Agent 验证」。连通性验证不能替代工具协议验证。"
      class="mb12"
    />
    <a-alert
      v-if="copyFrom"
      type="info"
      show-icon
      message="已带入原任务参数。请核对当前分支版本、范围和模型；确认提交后才会创建新任务。"
      class="mb12"
    />
    <a-alert v-if="error" type="error" show-icon :message="error" class="mb12" />

    <a-form layout="vertical">
        <a-card title="1. 审查目标" class="launch-card mb16">
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
            <a-form-item label="模型（仅已通过 Agent 工具协议验证）" required>
              <a-select
                :get-popup-container="getPopupContainer"
                v-model:value="form.modelConfigId"
                :options="modelOptions"
                placeholder="选择模型"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :xs="24" :md="12">
            <a-form-item label="参考结果 ID（可选，只能是完整结果）">
              <a-input v-model:value="form.referenceResultId" placeholder="旧结论只作线索，代码需重新读取" />
            </a-form-item>
          </a-col>
        </a-row>

        </a-card>

        <a-card title="2. 审查范围" class="launch-card mb16">
        <a-row :gutter="16">
          <a-col :xs="24" :md="10">
            <a-form-item label="任务名称（可选）">
              <a-input v-model:value="form.name" :maxlength="200" placeholder="例如：支付超时后的重试链路" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="14">
            <a-form-item label="范围类型" required>
              <a-radio-group v-model:value="form.kind" button-style="solid" @change="onKindChange">
                <a-radio-button value="TEXT">文字描述</a-radio-button>
                <a-radio-button value="FILES">文件／目录</a-radio-button>
                <a-radio-button value="COMMITS">提交比较</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item v-if="form.kind !== 'COMMITS'" label="分支" required>
          <a-select
                :get-popup-container="getPopupContainer"
            v-model:value="form.branch"
            :options="branchOptions"
            :loading="branchLoading"
            style="max-width: 360px"
            placeholder="选择分支"
            @change="loadHead"
          />
          <span class="muted head-hint">
            head：<code>{{ headLoading ? '读取中…' : form.observedSha || '—' }}</code>
          </span>
        </a-form-item>

        <a-form-item v-if="form.kind === 'TEXT'" label="范围描述" required>
          <a-textarea
            v-model:value="form.description"
            :rows="3"
            :maxlength="2000"
            placeholder="说明要调查的链路、入口或问题范围"
          />
        </a-form-item>

        <a-form-item v-if="form.kind === 'FILES'" label="文件与目录" required>
          <a-space wrap>
            <a-button :disabled="!form.observedSha" @click="scopeOpen = true">选择文件／目录</a-button>
            <span class="muted">已选 {{ form.paths.length }} 项，最多 100 项</span>
          </a-space>
          <div v-if="form.paths.length" class="selected-paths">
            <a-tag v-for="path in form.paths" :key="path" closable @close="removePath(path)">
              {{ path }}
            </a-tag>
          </div>
        </a-form-item>

        <a-form-item v-if="form.kind === 'COMMITS'" label="提交比较" required>
          <a-space wrap>
            <a-select
                :get-popup-container="getPopupContainer"
              v-model:value="form.branch"
              :options="branchOptions"
              :loading="branchLoading"
              style="width: 260px"
              placeholder="选择分支后加载提交"
              @change="onBranchChange"
            />
            <a-button :disabled="!form.branch" @click="openCommitPicker">
              选择提交（已选 {{ form.commits.length }}/20）
            </a-button>
            <span class="muted">按选择顺序逐项读取，最多 20 个；不合并成首尾净差异</span>
          </a-space>
          <div v-if="form.commits.length" class="selected-paths">
            <a-tag
              v-for="(sha, index) in form.commits"
              :key="sha"
              closable
              @close="toggleCommit(sha)"
            >
              C{{ index + 1 }} · {{ sha.slice(0, 10) }}
            </a-tag>
          </div>
        </a-form-item>

        </a-card>

        <a-card title="3. 补充要求" class="launch-card mb16">
        <a-row :gutter="16">
          <a-col :xs="24" :md="12">
            <a-form-item label="指导提示词（可选，仅 AGENT 用途）">
              <a-select :get-popup-container="getPopupContainer" v-model:value="form.guidancePromptId" allow-clear :options="promptOptions" placeholder="不选则使用内置调查方法" />
            </a-form-item>
          </a-col>
        </a-row>
        <PromptInfoPreview
          v-if="form.guidancePromptId"
          class="mb16"
          title="指导提示词"
          :prompt="selectedGuidancePrompt"
          selected
          :loading="guidancePromptLoading"
          :error="guidancePromptError"
          @retry="loadGuidancePrompt"
        />
        <a-form-item label="本次要求" required>
          <a-textarea
            v-model:value="form.requirements"
            :rows="6"
            :maxlength="16000"
            show-count
            placeholder="核对重试路径的幂等约定，并说明材料缺口。"
          />
        </a-form-item>

        </a-card>
        <div class="submit-bar">
          <span class="muted small">提交后任务在后台执行，可在审查中心查看进度。</span>
          <span class="spacer" />
          <a-button @click="close">取消</a-button>
          <a-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">开始 Agent 分析</a-button>
        </div>
      </a-form>

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
  </div>
  </a-modal>
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
  type PromptView
} from '@/api/v2'

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
const guidancePromptLoading = ref(false)
const guidancePromptError = ref('')
let guidancePromptEpoch = 0
const branches = ref<string[]>([])
const commits = ref<CommitInfo[]>([])
const headLoading = ref(false)
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
  referenceResultId: '',
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
watch(() => form.guidancePromptId, () => { void loadGuidancePrompt() }, { flush: 'sync' })

async function loadGuidancePrompt() {
  const id = form.guidancePromptId
  const epoch = ++guidancePromptEpoch
  selectedGuidancePrompt.value = null
  guidancePromptError.value = ''
  guidancePromptLoading.value = false
  if (!id) return

  guidancePromptLoading.value = true
  try {
    const prompt = await promptsApi.detail(id)
    if (epoch === guidancePromptEpoch) selectedGuidancePrompt.value = prompt
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
  if (form.kind === 'TEXT') return Boolean(form.description.trim() && form.observedSha)
  if (form.kind === 'FILES') return form.paths.length > 0 && Boolean(form.observedSha)
  return form.commits.length > 0
})

function closeDropdownOnScroll(event: Event) {
  const target = event.target
  if (!(target instanceof Element) || !target.closest('.agent-launch-modal .ant-modal-body')) return
  const active = document.activeElement
  if (active instanceof HTMLElement && active.closest('.ant-select')) active.blur()
}

onMounted(() => window.addEventListener('scroll', closeDropdownOnScroll, true))
onBeforeUnmount(() => {
  window.removeEventListener('scroll', closeDropdownOnScroll, true)
  guidancePromptEpoch++
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
    form.guidancePromptId = copy.guidancePromptId
    form.referenceResultId = copy.referenceResultId || ''
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
  if (!form.projectId || !form.branch) {
    form.observedSha = ''
    return
  }
  headLoading.value = true
  try {
    form.observedSha = await repositoryApi.head(form.projectId, form.branch)
  } catch (e) {
    form.observedSha = ''
    error.value = `读取分支 HEAD 失败：${(e as Error).message}`
  } finally {
    headLoading.value = false
  }
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
  form.paths = entries.map((entry) => entry.path).slice(0, 100)
}

function removePath(path: string) {
  form.paths = form.paths.filter((item) => item !== path)
}

function commitOrder(sha: string): string {
  const index = form.commits.indexOf(sha)
  return index >= 0 ? `C${index + 1}` : ''
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
    referenceResultId: form.referenceResultId.trim() || undefined,
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
.agent-launch {
  padding: 0 0 24px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 32px;
}
.page-head h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.4;
}
.page-head { margin-bottom: 20px; }
.page-head p {
  margin: 8px 0 0;
  font-size: 15px;
}
.mb16 { margin-bottom: 16px; }
.submit-bar { display: flex; align-items: center; gap: 10px; padding: 14px 0 2px; }
.submit-bar .spacer { flex: 1; }
.step {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 22px 0 14px;
  font-weight: 600;
}
.step .num {
  display: grid;
  place-items: center;
  width: 23px;
  height: 23px;
  border-radius: 50%;
  background: var(--blue);
  color: #fff;
  font-size: 12px;
}
.muted {
  color: var(--muted);
}
.mb12 {
  margin-bottom: 12px;
}
.head-hint {
  margin-left: 12px;
}
.selected-paths {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>

<style lang="less">
.agent-launch-modal .ant-modal {
  top: 24px;
  padding-bottom: 24px;
}
.agent-launch-modal .ant-modal-body {
  max-height: calc(100vh - 120px);
  overflow: auto;
}
</style>
