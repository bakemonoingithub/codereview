<template>
  <div>
    <div class="page-head">
      <div>
        <a-breadcrumb>
          <a-breadcrumb-item><router-link to="/reviews">审查中心</router-link></a-breadcrumb-item>
          <a-breadcrumb-item>{{ copyFrom ? '复制参数' : '发起审查' }}</a-breadcrumb-item>
        </a-breadcrumb>
        <h2>{{ copyFrom ? '复制参数发起新审查' : '发起专项审查' }}</h2>
        <p class="muted">按文件独立分析，完成后在同一任务中阅读结果。</p>
      </div>
      <a-button @click="router.push('/reviews')">返回审查中心</a-button>
    </div>

    <a-alert
      v-if="copyFrom"
      type="info"
      show-icon
      message="已复制历史参数。请重新确认当前版本和模型配置；原任务输入与结果保持不变。"
      class="mb16"
    />
    <a-alert
      v-if="copyHeadMoved"
      type="warning"
      show-icon
      message="分支版本已变化，已清空原文件范围；请按新版本重新选择。"
      class="mb16"
    />
    <a-alert
      v-if="sourceMoved"
      type="warning"
      show-icon
      message="分支版本已移动，尚未创建任务。请重新核对文件范围。"
      class="mb16"
    >
      <template #action>
        <a-button size="small" @click="refreshMovedSource">刷新版本与范围</a-button>
      </template>
    </a-alert>
    <a-alert v-if="formError" type="error" show-icon :message="formError" class="mb16" />

    <a-card title="1. 审查目标" class="mb16">
      <div class="form-grid">
        <div class="field">
          <label>项目</label>
          <a-select
            v-model:value="form.projectId"
            style="width: 100%"
            placeholder="选择项目"
            :loading="optionsLoading"
            :options="projectOptions"
            @change="onProjectChange"
          />
        </div>
        <div class="field">
          <label>专项类型</label>
          <a-radio-group v-model:value="form.specialistType" button-style="solid" @change="onTypeChange">
            <a-radio-button value="ISSUE">问题审查</a-radio-button>
            <a-radio-button value="PATTERN">设计模式</a-radio-button>
            <a-radio-button value="COUPLING">耦合度</a-radio-button>
          </a-radio-group>
        </div>
        <div v-if="form.specialistType === 'ISSUE'" class="field full">
          <label>审查范围</label>
          <a-radio-group v-model:value="form.sourceKind" @change="onSourceKindChange">
            <a-radio value="FILES">文件内容</a-radio>
            <a-radio value="DIFF">提交差异</a-radio>
          </a-radio-group>
          <p class="muted small">提交差异归在问题审查下；其他专项只接受文件内容。</p>
        </div>
      </div>
    </a-card>

    <a-card title="2. 范围与版本" class="mb16">
      <div class="form-grid">
        <div class="field">
          <label>分支</label>
          <a-input v-model:value="form.branch" placeholder="分支名" @change="onBranchChange" />
        </div>
        <div v-if="form.sourceKind === 'FILES'" class="field">
          <label>固定版本</label>
          <div class="version-row">
            <code>{{ form.observedSha || '读取中…' }}</code>
            <a-button size="small" :loading="headLoading" @click="resolveHead">重新读取</a-button>
          </div>
          <p class="muted small">发起时重新核对分支 HEAD；确认后移动的分支会被拒绝。</p>
        </div>
        <div v-else class="field">
          <label>审查提交</label>
          <a-select
            v-model:value="form.commitSha"
            style="width: 100%"
            placeholder="选择提交"
            :loading="commitsLoading"
            :options="commitOptions"
            @change="onCommitChange"
          />
          <p class="muted small">
            比较基线：{{ form.baseSha ? form.baseSha.slice(0, 7) : '根提交（无父版本）' }}
          </p>
        </div>
        <div class="field full">
          <label>目标文件</label>
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
        </div>
      </div>
    </a-card>

    <a-card title="3. 补充要求" class="mb16">
      <div class="form-grid">
        <div class="field">
          <label>审查策略（可选，包含模型与通用规则）</label>
          <a-select
            v-model:value="form.strategyId"
            allow-clear
            style="width: 100%"
            placeholder="不选策略时手动选择模型"
            :loading="optionsLoading"
            :options="strategyOptions"
            @change="onStrategyChange"
          />
        </div>
        <div class="field">
          <label>模型</label>
          <a-select
            v-model:value="form.modelConfigId"
            style="width: 100%"
            placeholder="选择模型"
            :disabled="!!selectedStrategy"
            :loading="optionsLoading"
            :options="modelOptions"
          />
          <p v-if="selectedStrategy" class="muted small">选择策略时由策略带入模型，不能再单独指定。</p>
        </div>
      </div>

      <div v-if="form.targets.length" class="notes">
        <div v-for="(target, index) in form.targets" :key="target.path" class="file-note">
          <div class="note-head">
            <strong>{{ target.path }}</strong>
            <span class="muted small">文件补充说明 · 可选</span>
          </div>
          <a-textarea
            v-model:value="form.targets[index].note"
            :rows="2"
            :maxlength="4000"
            placeholder="填写此文件的背景、约束或关注点"
          />
        </div>
      </div>

      <label class="switch-line">
        <a-checkbox v-model:checked="form.smartSplit" />
        <span>
          <strong>智能拆分文件</strong>
          <span class="muted small">
            默认关闭。未开启时整文件提交，上下文超限直接失败；开启后才允许容量不足时拆分，并保留原始位置。
          </span>
        </span>
      </label>
    </a-card>

    <div class="submit-bar">
      <span class="muted small">将固定版本和本次输入保存在新任务中；不会自动带入未发送材料。</span>
      <span class="spacer" />
      <a-button @click="router.push('/reviews')">取消</a-button>
      <a-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
        开始审查
      </a-button>
    </div>

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
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import ScopePickerModal from '@/components/ScopePickerModal.vue'
import {
  modelsApi,
  projectsApi,
  repositoryApi,
  strategiesApi,
  tasksApi,
  type CommitDetail,
  type CommitInfo,
  type ProjectView,
  type StrategyView
} from '@/api/v2'

type SpecialistType = 'ISSUE' | 'PATTERN' | 'COUPLING'
type SourceKind = 'FILES' | 'DIFF'

interface TargetRow {
  path: string
  oldPath: string | null
  note: string
}

const route = useRoute()
const router = useRouter()
const copyFrom = computed(() => String(route.query.copyFrom || ''))
const projects = ref<ProjectView[]>([])
const strategies = ref<StrategyView[]>([])
const models = ref<{ id: string; name: string }[]>([])
const optionsLoading = ref(false)
const headLoading = ref(false)
const commitsLoading = ref(false)
const commits = ref<CommitInfo[]>([])
const commitDetail = ref<CommitDetail | null>(null)
const scopeOpen = ref(false)
const submitting = ref(false)
const formError = ref('')
const sourceMoved = ref(false)
const copyHeadMoved = ref(false)
const restoring = ref(false)

const form = reactive({
  projectId: '',
  specialistType: 'ISSUE' as SpecialistType,
  sourceKind: 'FILES' as SourceKind,
  branch: '',
  observedSha: '',
  commitSha: '',
  baseSha: '',
  targets: [] as TargetRow[],
  strategyId: '',
  modelConfigId: '',
  smartSplit: false
})

const projectOptions = computed(() =>
  projects.value.map((project) => ({ value: project.id, label: `${project.name} · ${project.repoUrl}` }))
)
const strategyOptions = computed(() =>
  strategies.value
    .filter((strategy) => strategy.specialistType === form.specialistType)
    .map((strategy) => ({ value: strategy.id, label: strategy.name }))
)
const modelOptions = computed(() => models.value.map((model) => ({ value: model.id, label: model.name })))
const selectedStrategy = computed(() =>
  strategies.value.find((strategy) => strategy.id === form.strategyId) || null
)
const commitOptions = computed(() =>
  commits.value.map((commit) => ({
    value: commit.sha,
    label: `${commit.sha.slice(0, 7)} · ${(commit.message || '').split('\n')[0]}`
  }))
)
const scopeReady = computed(() =>
  !!form.projectId && (form.sourceKind === 'FILES' ? !!form.observedSha : !!form.commitSha)
)
const canSubmit = computed(
  () =>
    !!form.projectId &&
    form.targets.length > 0 &&
    (!!form.strategyId || !!form.modelConfigId) &&
    (form.sourceKind === 'FILES' ? !!form.observedSha : !!form.commitSha) &&
    !submitting.value
)

const strategyModel = computed(() => selectedStrategy.value?.modelConfigId || '')

onMounted(async () => {
  await loadOptions()
  if (copyFrom.value) {
    await loadCopy()
  } else if (projects.value.length) {
    form.projectId = projects.value[0].id
    form.branch = projects.value[0].currentBranch || 'main'
    await resolveHead()
  }
})

watch(
  () => form.projectId,
  async (projectId) => {
    if (restoring.value) {
      return
    }
    const project = projects.value.find((item) => item.id === projectId)
    if (project) {
      form.branch = project.currentBranch || 'main'
      form.targets = []
      form.commitSha = ''
      form.baseSha = ''
      commits.value = []
      commitDetail.value = null
      if (form.sourceKind === 'FILES') {
        await resolveHead()
      } else {
        await loadCommits()
      }
    }
  }
)

watch(
  () => form.specialistType,
  (type) => {
    if (restoring.value) {
      return
    }
    if (form.strategyId) {
      const strategy = strategies.value.find((item) => item.id === form.strategyId)
      if (!strategy || strategy.specialistType !== type) {
        form.strategyId = ''
      }
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
    const [projectPage, strategyPage, modelPage] = await Promise.all([
      projectsApi.list({ limit: 100 }),
      strategiesApi.list({ limit: 100 }),
      modelsApi.list({ limit: 100 })
    ])
    projects.value = projectPage.items
    strategies.value = strategyPage.items
    models.value = modelPage.items.map((model) => ({ id: model.id, name: model.name }))
  } catch (failure) {
    formError.value = (failure as Error).message || '配置加载失败，请稍后重试'
  } finally {
    optionsLoading.value = false
  }
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
    form.commitSha = copy.commitSha || ''
    form.baseSha = copy.baseSha || ''
    form.targets = copy.targets.map((target) => ({
      path: target.path,
      oldPath: target.oldPath,
      note: target.note || ''
    }))
    form.strategyId = copy.strategyId || ''
    form.modelConfigId = copy.modelConfigId || ''
    form.smartSplit = copy.smartSplit
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
  headLoading.value = true
  formError.value = ''
  try {
    form.observedSha = await repositoryApi.head(form.projectId, form.branch)
  } catch (failure) {
    form.observedSha = ''
    formError.value = (failure as Error).message || '分支版本读取失败'
  } finally {
    headLoading.value = false
  }
}

async function loadCommits() {
  if (!form.projectId || !form.branch) {
    return
  }
  commitsLoading.value = true
  try {
    const page = await repositoryApi.commits(form.projectId, form.branch, 1, 30)
    commits.value = page.commits
  } catch (failure) {
    formError.value = (failure as Error).message || '提交列表读取失败'
  } finally {
    commitsLoading.value = false
  }
}

async function onCommitChange() {
  form.targets = []
  await loadCommitDetail(form.commitSha)
}

async function loadCommitDetail(sha: string) {
  if (!form.projectId || !sha) {
    return
  }
  try {
    commitDetail.value = await repositoryApi.commit(form.projectId, sha)
    form.baseSha = commitDetail.value.parents?.[0] || ''
  } catch (failure) {
    formError.value = (failure as Error).message || '提交详情读取失败'
  }
}

function onStrategyChange() {
  if (strategyModel.value) {
    form.modelConfigId = strategyModel.value
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
    formError.value = '请补全项目、目标文件、策略或模型以及固定版本'
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
    const created = await tasksApi.create(
      {
        projectId: form.projectId,
        specialistType: form.specialistType,
        source,
        targets: form.targets.map((target) => ({
          path: target.path,
          oldPath: target.oldPath,
          note: target.note || undefined
        })),
        strategyId: form.strategyId || undefined,
        modelConfigId: form.strategyId ? undefined : form.modelConfigId || undefined,
        smartSplit: form.smartSplit
      },
      idempotencyKey()
    )
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
.page-head {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-start;
  margin-bottom: 18px;
}
.page-head h2 {
  margin: 8px 0 4px;
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
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 24px;
}
.field.full {
  grid-column: 1 / -1;
}
.field label {
  display: block;
  font-size: 12px;
  font-weight: 550;
  margin-bottom: 6px;
}
.version-row,
.target-actions,
.submit-bar,
.note-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.target-list {
  margin-top: 10px;
  border: 1px solid #e3e9f0;
  border-radius: 6px;
  max-height: 240px;
  overflow: auto;
}
.target-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid #eef1f6;
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
  border: 1px solid #e3e9f0;
  border-radius: 7px;
  padding: 10px 12px;
}
.note-head {
  justify-content: space-between;
  margin-bottom: 8px;
}
.switch-line {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-top: 16px;
  padding: 12px 14px;
  background: #f8fafd;
  border: 1px solid #e3e9f0;
  border-radius: 7px;
  cursor: pointer;
}
.switch-line strong {
  display: block;
  font-size: 13px;
}
.submit-bar {
  position: sticky;
  bottom: 0;
  background: #fff;
  border-top: 1px solid #e3e9f0;
  padding: 12px 0;
}
.spacer {
  flex: 1;
}
</style>
