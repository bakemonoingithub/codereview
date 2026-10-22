<template>
  <div class="agent-launch">
    <div class="page-head">
      <div>
        <h1>发起 Agent 分析</h1>
        <p class="muted">描述范围与要求。模型只读调查固定版本，命中限制会保留记录但不发布完整结果。</p>
      </div>
      <a-button @click="router.push('/reviews')">返回审查中心</a-button>
    </div>

    <a-alert
      v-if="!agentModels.length"
      type="warning"
      show-icon
      message="没有已通过 Agent 工具协议验证的模型"
      description="请先在模型管理中执行「Agent 验证」。连通性验证不能替代工具协议验证。"
      class="mb12"
    />
    <a-alert v-if="error" type="error" show-icon :message="error" class="mb12" />

    <a-card class="launch-card">
      <a-form layout="vertical">
        <a-row :gutter="16">
          <a-col :xs="24" :md="12">
            <a-form-item label="项目" required>
              <a-select
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
                v-model:value="form.modelConfigId"
                :options="modelOptions"
                placeholder="选择模型"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :xs="24" :md="12">
            <a-form-item label="指导提示词（可选，仅 AGENT 用途）">
              <a-select
                v-model:value="form.guidancePromptId"
                allow-clear
                :options="promptOptions"
                placeholder="不选则使用内置调查方法"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-item label="参考结果 ID（可选，只能是完整结果）">
              <a-input v-model:value="form.referenceResultId" placeholder="旧结论只作线索，代码需重新读取" />
            </a-form-item>
          </a-col>
        </a-row>

        <div class="step"><span class="num">1</span>审查范围</div>
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
              v-model:value="form.branch"
              :options="branchOptions"
              :loading="branchLoading"
              style="width: 260px"
              placeholder="选择分支后加载提交"
              @change="loadCommits"
            />
            <a-button :loading="commitLoading" @click="loadCommits">刷新提交</a-button>
            <span class="muted">按选择顺序逐项读取，最多 20 个；不合并成首尾净差异</span>
          </a-space>
          <a-spin :spinning="commitLoading">
            <a-empty v-if="!commitLoading && !commits.length" description="该分支没有可读提交" />
            <div v-else class="commit-list">
              <button
                v-for="commit in commits"
                :key="commit.sha"
                type="button"
                class="commit-item"
                :class="{ selected: form.commits.includes(commit.sha) }"
                @click="toggleCommit(commit.sha)"
              >
                <span class="commit-order">{{ commitOrder(commit.sha) }}</span>
                <span class="commit-sha">{{ commit.sha.slice(0, 10) }}</span>
                <span class="commit-message">{{ commit.message }}</span>
                <span class="muted">{{ commit.date }}</span>
              </button>
            </div>
          </a-spin>
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

        <div class="step"><span class="num">2</span>审查要求 <span class="muted small">必填</span></div>
        <a-form-item label="本次要求" required>
          <a-textarea
            v-model:value="form.requirements"
            :rows="6"
            :maxlength="16000"
            show-count
            placeholder="核对重试路径的幂等约定，并说明材料缺口。"
          />
        </a-form-item>

        <a-space>
          <a-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
            开始 Agent 分析
          </a-button>
          <a-button @click="router.push('/reviews')">取消</a-button>
          <span class="muted">提交后任务在后台执行，可在审查中心查看进度。</span>
        </a-space>
      </a-form>
    </a-card>

    <ScopePickerModal
      v-model:open="scopeOpen"
      :project-id="form.projectId"
      mode="FILES"
      :sha="form.observedSha"
      :branch="form.branch"
      :selected="form.paths"
      @confirm="onScopeConfirm"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import ScopePickerModal from '@/components/ScopePickerModal.vue'
import {
  agentTasksApi,
  modelsApi,
  projectsApi,
  promptsApi,
  repositoryApi,
  type CommitInfo,
  type ModelView,
  type ProjectView,
  type PromptSummary
} from '@/api/v2'

const router = useRouter()

const projectOptions = ref<Array<{ label: string; value: string }>>([])
const projects = ref<ProjectView[]>([])
const agentModels = ref<ModelView[]>([])
const prompts = ref<PromptSummary[]>([])
const branches = ref<string[]>([])
const commits = ref<CommitInfo[]>([])
const headLoading = ref(false)
const branchLoading = ref(false)
const commitLoading = ref(false)
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

const canSubmit = computed(() => {
  if (!form.projectId || !form.modelConfigId || !form.requirements.trim()) return false
  if (form.kind === 'TEXT') return Boolean(form.description.trim() && form.observedSha)
  if (form.kind === 'FILES') return form.paths.length > 0 && Boolean(form.observedSha)
  return form.commits.length > 0
})

onMounted(async () => {
  try {
    const [projectPage, modelPage, promptPage] = await Promise.all([
      projectsApi.list({ pageNum: 1, pageSize: 100 }),
      modelsApi.list({ pageNum: 1, pageSize: 100 }),
      promptsApi.list({ pageNum: 1, pageSize: 100 })
    ])
    projects.value = projectPage.items
    projectOptions.value = projectPage.items.map((project) => ({
      label: project.name,
      value: project.id
    }))
    agentModels.value = modelPage.items.filter(
      (model) => model.agentValidationStatus === 'PASSED'
    )
    prompts.value = promptPage.items.filter((prompt) => prompt.purpose === 'AGENT')
    if (!form.projectId && projects.value.length) {
      await selectProject(projects.value[0].id)
    }
    if (!form.modelConfigId && agentModels.value.length) {
      form.modelConfigId = agentModels.value[0].id
    }
  } catch (e) {
    error.value = `加载基础数据失败：${(e as Error).message}`
  }
})

async function onProjectChange(value: string) {
  await selectProject(value)
}

async function selectProject(projectId: string) {
  form.projectId = projectId
  form.paths = []
  form.commits = []
  form.observedSha = ''
  commits.value = []
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

async function loadCommits() {
  if (!form.projectId || !form.branch) return
  commitLoading.value = true
  try {
    const page = await repositoryApi.commits(form.projectId, form.branch, 1, 50)
    commits.value = page.commits
    form.commits = form.commits.filter((sha) => page.commits.some((commit) => commit.sha === sha))
  } catch (e) {
    error.value = `读取提交失败：${(e as Error).message}`
  } finally {
    commitLoading.value = false
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
.page-head p {
  margin: 8px 0 0;
  font-size: 15px;
}
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
.commit-list {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  max-height: 360px;
  overflow: auto;
}
.commit-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fff;
  box-shadow: none;
  text-align: left;
  cursor: pointer;
}
.commit-item:hover {
  background: var(--bg);
}
.commit-item.selected {
  background: var(--soft);
  border-color: var(--soft-border);
  color: var(--blue);
}
.commit-order {
  width: 34px;
  font-weight: 700;
  color: var(--blue);
}
.commit-sha {
  font-family: var(--font-mono);
  color: #334155;
}
.commit-message {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
