import { runtimeConfig, businessApiBase } from '@/runtime/config'
import axios from 'axios'
import { toReadableError } from '@/utils/request'

/**
 * v2 接口客户端。
 *
 * <p>v2 不套旧接口的 `{code,data}` 信封：成功直接返回业务对象，失败返回
 * `{code,message,retryable,requestId,details}`。因此这里单独建实例，
 * 不复用旧 `request` 的响应拦截器，避免把 v2 的正文当成旧信封解包。
 */
const v2 = axios.create({
  baseURL: businessApiBase(),
  timeout: runtimeConfig().requestTimeoutMillis,
  withCredentials: true
})

v2.interceptors.response.use(
  (response) => response,
  (error) => {
    const body = error?.response?.data
    const message = body?.message || toReadableError(error)
    const wrapped = new Error(message) as Error & { code?: string; details?: unknown; retryable?: boolean }
    wrapped.code = body?.code
    wrapped.details = body?.details
    wrapped.retryable = body?.retryable
    return Promise.reject(wrapped)
  }
)

export interface ListPaginationParams {
  pageNum?: number
  pageSize?: number
}

export interface Page<T> {
  total?: number | null
  pageNum?: number | null
  pageSize?: number | null
  items: T[]
  nextCursor: string | null
  hasMore: boolean
}

export interface FeedbackView {
  subjectKind: 'ISSUE' | 'RESULT'
  subjectId: string
  assessment: 'ACCURATE' | 'INACCURATE' | 'UNKNOWN' | null
  comment: string | null
  revision: number
  updatedAt: string | null
}

export const feedbackApi = {
  get: (subjectKind: FeedbackView['subjectKind'], subjectId: string) =>
    v2.get<FeedbackView>(`/feedback/${subjectKind}/${subjectId}`).then((r) => r.data),
  save: (subjectKind: FeedbackView['subjectKind'], subjectId: string, body: {
    assessment: FeedbackView['assessment']
    comment: string
    expectedRevision: number
  }) => v2.put<FeedbackView>(`/feedback/${subjectKind}/${subjectId}`, body).then((r) => r.data)
}

export interface ProjectView {
  id: string
  name: string
  repoUrl: string
  currentBranch: string
  credentialConfigured: boolean
  revision: number
  createdAt: string
  updatedAt: string
}

export interface ModelView {
  id: string
  name: string
  baseUrl: string
  modelName: string
  credentialConfigured: boolean
  contextWindow: number | null
  maxOutput: number | null
  tokenizer: string | null
  protocol: string
  validationStatus: 'NONE' | 'EXPIRED' | 'PASSED' | 'FAILED'
  /** 原生工具协议验证（AGENT_TOOL_LOOP_V1）；与连通性状态分开。 */
  agentValidationStatus: 'NONE' | 'EXPIRED' | 'PASSED' | 'FAILED'
  revision: number
  createdAt: string
  updatedAt: string
}

export interface ModelValidation {
  result: 'PASSED' | 'FAILED'
  configRevision: number
  scope: string
  detail: string
  createdAt: string
}

export interface PromptSummary {
  id: string
  name: string
  purpose: string
  tags: string[]
  revision: number
  currentVersionNo?: number | null
  updatedAt: string
}

export interface PromptVersion {
  id: string
  versionNo: number
  content: string
  createdAt: string
}

export interface PromptView {
  id: string
  name: string
  description: string | null
  tags: string[]
  purpose: string
  currentVersion: PromptVersion | null
  revision: number
  createdAt: string
  updatedAt: string
}

export interface PromptDiffRow {
  type: string
  text: string
  oldLine: number | null
  newLine: number | null
}

export interface RepositoryPath {
  path: string
  type: string
  reviewable: boolean
}

export interface PathList {
  entries: RepositoryPath[]
  sha: string
  truncated: boolean
}

export interface FileSnapshot {
  sha: string
  path: string
  content: string
  contentHash: string
  byteSize: number
  lineCount: number
}

export interface DiffSide {
  content: string | null
  status: 'PRESENT' | 'MISSING' | 'UNREADABLE'
  reason: string | null
}

export interface DiffSides {
  headSha: string
  baseSha: string | null
  path: string
  oldPath: string | null
  status: string
  patch: string | null
  before: DiffSide
  after: DiffSide
}

export interface CommitInfo {
  sha: string
  message: string
  author: string
  date: string
}

export interface CommitPage {
  commits: CommitInfo[]
  hasMore: boolean
}

export interface ChangedFile {
  path: string
  previousPath: string | null
  status: string
  additions: number | null
  deletions: number | null
  changes: number | null
  patch: string | null
  reviewable: boolean
}

export interface CommitDetail {
  sha: string
  parents: string[]
  message: string
  author: string
  date: string
  additions: number | null
  deletions: number | null
  totalChanges: number | null
  truncated: boolean
  files: ChangedFile[]
}

export const repositoryApi = {
  branches: (projectId: string) =>
    v2.get<string[]>(`/projects/${projectId}/branches`).then((r) => r.data),
  head: (projectId: string, branch: string) =>
    v2
      .get<{ sha: string }>(`/projects/${projectId}/head`, { params: { branch } })
      .then((r) => r.data.sha),
  commits: (projectId: string, branch: string, page = 1, perPage = 30) =>
    v2
      .get<CommitPage>(`/projects/${projectId}/commits`, { params: { branch, page, perPage } })
      .then((r) => r.data),
  commit: (projectId: string, sha: string) =>
    v2.get<CommitDetail>(`/projects/${projectId}/commit`, { params: { sha } }).then((r) => r.data),
  paths: (projectId: string, sha: string) =>
    v2.get<PathList>(`/projects/${projectId}/paths`, { params: { sha } }).then((r) => r.data),
  file: (projectId: string, sha: string, path: string) =>
    v2
      .get<FileSnapshot>(`/projects/${projectId}/files`, { params: { sha, path } })
      .then((r) => r.data),
  diff: (projectId: string, sha: string, path: string) =>
    v2
      .get<DiffSides>(`/projects/${projectId}/diff`, { params: { sha, path } })
      .then((r) => r.data)
}

export interface TaskTargetInput {
  path: string
  oldPath?: string | null
  note?: string | null
}

export interface AgentTaskScopeInput {
  kind: 'TEXT' | 'FILES' | 'COMMITS'
  branch?: string
  observedSha?: string
  paths?: string[]
  commits?: string[]
  description?: string
}

export interface AgentTaskCreateInput {
  projectId: string
  name?: string
  modelConfigId: string
  guidancePromptId?: string
  guidancePromptVersionId?: string
  scope: AgentTaskScopeInput
  requirements: string
  referenceResultId?: string
}

export interface AgentTaskCreated {
  id: string
  state: string
  route: string
  detailUrl: string
}

export interface AgentTaskCopyInput extends AgentTaskCreateInput {
  reobserveHead: boolean
}

export interface AgentProgress {
  taskId: string
  state: string
  stopReason: string | null
  turnNo: number
  toolAttempts: number
  networkAttempts: number
  currentAction: string | null
  covered: Record<string, unknown>
  limits: Record<string, unknown>
  updatedAt: string | null
  elapsedSeconds?: number | null
  outputUsage?: {
    totalOutputTokens: number | null
    peakOutputTokens: number | null
    reportedCalls: number
    unreportedCalls: number
  }
}

// 后端全局把 Long 序列化为字符串；仅转换用量字段，任务 ID 保留原样。
type AgentProgressResponse = Omit<AgentProgress, 'elapsedSeconds' | 'outputUsage'> & {
  elapsedSeconds?: number | string | null
  outputUsage?: {
    totalOutputTokens: number | string | null
    peakOutputTokens: number | string | null
    reportedCalls: number
    unreportedCalls: number
  } | null
}

function usageCount(value: unknown): number | null {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return null
  const count = Number(value)
  return Number.isSafeInteger(count) && count >= 0 ? count : null
}

export interface AgentToolCallSummary {
  id: string
  turnNo: number
  ordinal: number
  toolName: string
  status: string
  startedAt: string | null
  finishedAt: string | null
}

export interface AgentToolCallDetail extends AgentToolCallSummary {
  providerToolCallId: string
  arguments: string | null
  envelope: Record<string, unknown>
  materialIds: string[]
}

export interface AgentCitation {
  id: string
  materialId: string
  path: string
  side: string | null
  sha: string | null
  startLine: number
  endLine: number
}

export interface AgentResultView {
  id: string
  schemaVersion: string
  completeness: string
  createdAt: string
  body: {
    title: string
    summary: string
    answerStatus: string
    markdown: string
    citations: AgentCitation[]
    unresolved: string[]
  }
  provenance: Record<string, unknown>
}

export const agentTasksApi = {
  create: (body: AgentTaskCreateInput, idempotencyKey: string) =>
    v2
      .post<AgentTaskCreated>('/agent-tasks', body, {
        headers: { 'Idempotency-Key': idempotencyKey }
      })
      .then((r) => r.data),
  copyInput: (id: string) =>
    v2.get<AgentTaskCopyInput>(`/agent-tasks/${id}/copy-input`).then((r) => r.data),
  progress: (id: string) =>
    v2.get<AgentProgressResponse>(`/agent-tasks/${id}/progress`).then(({ data }): AgentProgress => ({
      ...data,
      elapsedSeconds: usageCount(data.elapsedSeconds),
      outputUsage: data.outputUsage ? {
        ...data.outputUsage,
        totalOutputTokens: usageCount(data.outputUsage.totalOutputTokens),
        peakOutputTokens: usageCount(data.outputUsage.peakOutputTokens)
      } : undefined
    })),
  toolCalls: (id: string, params: { pageNum?: number; pageSize?: number } = {}) =>
    v2.get<Page<AgentToolCallSummary>>(`/agent-tasks/${id}/tool-calls`, { params }).then((r) => r.data),
  toolCall: (id: string, callId: string) =>
    v2
      .get<AgentToolCallDetail>(`/agent-tasks/${id}/tool-calls/${callId}`)
      .then((r) => r.data),
  result: (id: string) => v2.get<AgentResultView>(`/agent-tasks/${id}/result`).then((r) => r.data)
}

export interface TaskCreateInput {  projectId: string
  route?: string
  specialistType: 'ISSUE' | 'PATTERN' | 'COUPLING'
  source: {
    kind: 'FILES' | 'DIFF'
    branch?: string
    observedSha?: string
    commitSha?: string
    baseSha?: string
  }
  targets: TaskTargetInput[]
  modelConfigId: string
  promptId?: string
  promptVersionId?: string
  smartSplit?: boolean
  name?: string
}

export interface TaskTargetView {
  id: string
  ordinal: number
  path: string
  oldPath: string | null
  baseSha: string | null
  headSha: string
  note: string | null
  state: string
  errorCode: string | null
  revision: number
  createdAt: string
  updatedAt: string
}

export interface TaskView {
  id: string
  name: string | null
  projectId: string
  route: string
  specialistType: 'ISSUE' | 'PATTERN' | 'COUPLING'
  state: string
  stopReason: string | null
  sourceKind: 'FILES' | 'DIFF'
  branch: string | null
  headSha: string | null
  baseSha: string | null
  smartSplit: boolean
  revision: number
  createdAt: string
  updatedAt: string
  startedAt: string | null
  finishedAt: string | null
  deadlineAt: string | null
  cancelRequestedAt: string | null
  targets: TaskTargetView[]
  config: {
    modelConfigId: string | null
    modelName: string | null
    modelRevision: number | null
    promptId: string | null
    promptName: string | null
    promptVersionId: string | null
    promptVersionNo: number | null
    promptContent: string | null
  }
}

export interface TaskSummary {
  id: string
  name: string | null
  projectId: string
  route: string
  specialistType: 'ISSUE' | 'PATTERN' | 'COUPLING'
  state: string
  stopReason: string | null
  createdAt: string
  deadlineAt: string | null
  targetCount: number
  succeededCount: number
  failedCount: number
  runningCount: number
  queuedCount: number
}

export interface FeedbackCounts {
  total: number
  accurate: number
  inaccurate: number
  unknown: number
  unassessed: number
  /** 分母为 0 时为 null；界面必须显示“暂无明确判定”，不能显示 0% 或 100%。 */
  accuracyPercent: number | null
}

export interface TaskStatistics {
  taskId: string
  specialistType: 'ISSUE' | 'PATTERN' | 'COUPLING'
  issues: FeedbackCounts
  results: FeedbackCounts
}

export interface TaskCopyInput {  projectId: string
  route: string
  specialistType: 'ISSUE' | 'PATTERN' | 'COUPLING'
  sourceKind: 'FILES' | 'DIFF'
  branch: string | null
  commitSha: string | null
  baseSha: string | null
  targets: { path: string; oldPath: string | null; note: string | null }[]
  modelConfigId: string | null
  promptId: string | null
  promptVersionId: string | null
  smartSplit: boolean
  reobserveHead: boolean
  name: string | null
}

export interface IssueView {
  id: string
  ordinal: number
  category: string
  severity: string
  title: string
  reason: string | null
  suggestion: string | null
  locations: Array<{
    materialId?: string
    path: string
    sha?: string
    side: string
    startLine: number
    endLine: number
  }>
  sourceUnits: string[]
}

export interface ResultView {
  id: string
  targetId: string
  kind: 'ISSUE' | 'PATTERN' | 'COUPLING'
  schemaVersion: string
  completeness: 'COMPLETE' | 'INCOMPLETE'
  createdAt: string
  body: {
    summary?: string
    issues?: Array<{
      category: string
      severity: string
      title: string
      locations: Array<{ materialId: string; startLine: number; endLine: number }>
      reason: string
      suggestion: string
      evidence?: Array<{ materialId: string; startLine: number; endLine: number }>
      changeRelation?: string | null
    }>
    patterns?: Array<{
      name: string
      family: string
      role: string
      explanation: string
      evidence: Array<{ materialId: string; startLine: number; endLine: number }>
    }>
    observations?: Array<{
      title: string
      explanation: string
      dependencyIds: string[]
      evidence: Array<{ materialId: string; startLine: number; endLine: number }>
      suggestion?: string | null
    }>
    limitations?: string[]
  }
  issues: IssueView[]
  provenance: {
    versions?: { headSha?: string; baseSha?: string; path?: string; oldPath?: string }
    materials?: Array<{ id: string; path: string; sha: string; side: string; role: string }>
    units?: Array<{ id: string; ordinal: number; ranges: Array<{ startLine: number; endLine: number }> }>
    calls?: Array<{ unitId: string; callId: string }>
    couplingFacts?: {
      resolvedOutgoingCount: number
      unresolvedTypeCount: number
      coverage: number
      sha: string
      side: string
      edges: Array<{ id: string; sourceType: string; targetType: string; targetPath: string; relation: string; line: number }>
      limitations: string[]
    }
    materialGaps?: Array<{ code: string; subject: string; detail: string }>
  }
}

export interface MaterialView {
  id: string
  path: string
  sha: string
  side: string
  startLine: number
  endLine: number
  role: string
  disposition: string
  completeness: string
  diagnostic: string | null
  contentHash: string
  byteSize: number
  sent: boolean
}

export interface MaterialDetail {
  metadata: MaterialView
  content: string
  complete: boolean
}

export interface CallSummary {
  generation?: number | null
  unitOrdinal?: number | null
  id: string
  targetId: string | null
  unitId: string | null
  purpose: string
  attempt: number
  state: string
  errorCode: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface CallDetailFull {
  id: string
  targetId: string | null
  unitId: string | null
  purpose: string
  attempt: number
  state: string
  errorCode: string | null
  errorMessage: string | null
  requestBody: string | null
  responseBody: string | null
  usageJson: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export const tasksApi = {
  create: (body: TaskCreateInput, idempotencyKey: string) =>
    v2
      .post<TaskView>('/review-tasks', body, { headers: { 'Idempotency-Key': idempotencyKey } })
      .then((r) => r.data),
  list: (params: ListPaginationParams & {
    projectId?: string
    type?: string
    route?: string
    state?: string
    q?: string
    cursor?: string
    limit?: number
  } = {}) => v2.get<Page<TaskSummary>>('/review-tasks', { params }).then((r) => r.data),
  detail: (id: string) => v2.get<TaskView>(`/review-tasks/${id}`).then((r) => r.data),
  statistics: (id: string) =>
    v2.get<TaskStatistics>(`/review-tasks/${id}/statistics`).then((r) => r.data),
  copyInput: (id: string) => v2.get<TaskCopyInput>(`/review-tasks/${id}/copy-input`).then((r) => r.data),
  cancel: (id: string, expectedRevision: number) =>
    v2.post<TaskView>(`/review-tasks/${id}/cancel`, { expectedRevision }).then((r) => r.data),
  result: (taskId: string, targetId: string) =>
    v2.get<ResultView>(`/review-tasks/${taskId}/targets/${targetId}/result`).then((r) => r.data),
  materials: (taskId: string, targetId: string) =>
    v2
      .get<MaterialView[]>(`/review-tasks/${taskId}/targets/${targetId}/materials`)
      .then((r) => r.data),
  material: (taskId: string, materialId: string) =>
    v2
      .get<MaterialDetail>(`/review-tasks/${taskId}/materials/${materialId}`)
      .then((r) => r.data),
  call: (taskId: string, callId: string) =>
    v2.get<CallDetailFull>(`/review-tasks/${taskId}/calls/${callId}`).then((r) => r.data),
  calls: (
    taskId: string,
    params: { targetId?: string; unitId?: string; cursor?: string; limit?: number } = {}
  ) => v2.get<Page<CallSummary>>(`/review-tasks/${taskId}/calls`, { params }).then((r) => r.data)
}

export const projectsApi = {
  list: (params: ListPaginationParams & { q?: string; state?: string; cursor?: string; limit?: number } = {}) =>
    v2.get<Page<ProjectView>>('/projects', { params }).then((r) => r.data),
  detail: (id: string) => v2.get<ProjectView>(`/projects/${id}`).then((r) => r.data),
  create: (body: {
    name: string
    repoUrl: string
    credential?: string
    credentialType?: number
    currentBranch?: string
  }) => v2.post<ProjectView>('/projects', body).then((r) => r.data),
  update: (id: string, body: { name: string; credential?: string; expectedRevision: number }) =>
    v2.put<ProjectView>(`/projects/${id}`, body).then((r) => r.data),
  remove: (id: string) => v2.delete(`/projects/${id}`).then((r) => r.data)
}

export const modelsApi = {
  list: (params: ListPaginationParams & { q?: string; cursor?: string; limit?: number } = {}) =>
    v2.get<Page<ModelView>>('/models', { params }).then((r) => r.data),
  detail: (id: string) => v2.get<ModelView>(`/models/${id}`).then((r) => r.data),
  create: (body: {
    name: string
    baseUrl: string
    modelName: string
    credential?: string
    contextWindow?: number | null
    maxOutput?: number | null
    tokenizer?: string
    protocol?: string
  }) => v2.post<ModelView>('/models', body).then((r) => r.data),
  update: (
    id: string,
    body: {
      name: string
      baseUrl: string
      modelName: string
      credential?: string
      contextWindow?: number | null
      maxOutput?: number | null
      tokenizer?: string
      protocol?: string
      expectedRevision: number
    }
  ) => v2.put<ModelView>(`/models/${id}`, body).then((r) => r.data),
  remove: (id: string) => v2.delete(`/models/${id}`).then((r) => r.data),
  validate: (id: string) => v2.post<ModelValidation>(`/models/${id}/validate`).then((r) => r.data),
  /** 原生工具协议验证：真实两轮调用，结果绑定当前配置修订。 */
  validateAgent: (id: string) =>
    v2.post<ModelValidation>(`/models/${id}/validate-agent`).then((r) => r.data)
}

export const promptsApi = {
  list: (params: ListPaginationParams & { q?: string; cursor?: string; limit?: number } = {}) =>
    v2.get<Page<PromptSummary>>('/prompts', { params }).then((r) => r.data),
  detail: (id: string) => v2.get<PromptView>(`/prompts/${id}`).then((r) => r.data),
  create: (body: {
    name: string
    description?: string
    tags?: string[]
    purpose?: string
    content: string
  }) => v2.post<PromptView>('/prompts', body).then((r) => r.data),
  update: (
    id: string,
    body: { name: string; description?: string; tags?: string[]; purpose?: string; expectedRevision: number }
  ) => v2.put<PromptView>(`/prompts/${id}`, body).then((r) => r.data),
  addVersion: (id: string, body: {
    content: string
    expectedRevision: number
    createNewVersion?: boolean
  }) =>
    v2.post<PromptView>(`/prompts/${id}/versions`, body).then((r) => r.data),
  versions: (id: string) => v2.get<PromptVersion[]>(`/prompts/${id}/versions`).then((r) => r.data),
  diff: (id: string, fromVersionId: string, toVersionId: string) =>
    v2
      .get<PromptDiffRow[]>(`/prompts/${id}/versions/diff`, {
        params: { fromVersionId, toVersionId }
      })
      .then((r) => r.data),
  remove: (id: string) => v2.delete(`/prompts/${id}`).then((r) => r.data)
}

export interface ReportCandidate {
  resultId: string
  taskId: string
  taskName?: string | null
  targetId: string | null
  kind: 'ISSUE' | 'PATTERN' | 'COUPLING' | 'AGENT'
  projectId: string
  projectName: string | null
  taskRoute: string
  taskState: string
  sourceKind: 'FILES' | 'DIFF'
  path: string | null
  oldPath: string | null
  headSha: string | null
  baseSha: string | null
  branch: string | null
  resultCreatedAt: string
  issueCount: number
  /** Agent 结果的回答状态；材料不足仍可选择，但报告必须保留限制。 */
  answerStatus?: string | null
  unresolvedCount?: number
  feedback: {
    assessment: 'ACCURATE' | 'INACCURATE' | 'UNKNOWN' | null
    comment: string | null
    revision: number
    updatedAt: string | null
  } | null
}

export interface ReportSourceSummary {
  ordinal: number
  resultId: string
  kind: ReportCandidate['kind']
  projectId: string
  projectName: string | null
  taskId: string
  taskRoute: string
  path: string | null
  headSha: string | null
  baseSha: string | null
  summary: string | null
  issueCount: number
  accurate: number
  inaccurate: number
  unknown: number
  unassessed: number
}

export interface ReportModelView {
  id: string
  name: string
  modelName: string
  contextWindow: number | null
  maxOutput: number | null
  tokenizer: string | null
  protocol: string
  revision: number
}

export interface ReportPromptView {
  id: string
  name: string
  versionId: string
  versionNo: number
  content: string
}

export interface ReportCallSummary {
  id: string
  purpose: string
  attempt: number
  state: string
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface ReportSummary {
  id: string
  title: string
  state: string
  errorCode: string | null
  errorMessage: string | null
  modelName: string | null
  sourceCount: number
  taskCount: number
  versionCount: number
  kindCounts: Record<string, number>
  createdAt: string
  updatedAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface ReportDetail extends ReportSummary {
  body: string | null
  model: ReportModelView | null
  prompt: ReportPromptView | null
  instruction: string | null
  sources: ReportSourceSummary[]
  calls: ReportCallSummary[]
}

export interface ReportSourceSnapshot {
  ordinal: number
  resultId: string
  snapshot: any
}

export const reportsApi = {
  candidates: (params: {
    projectId?: string
    taskId?: string
    kind?: string
    cursor?: string
    limit?: number
  } = {}) => v2.get<Page<ReportCandidate>>('/reports/candidates', { params }).then((r) => r.data),
  freezeInputs: (body: { title: string; resultIds: string[] }) =>
    v2.post<{ reportId: string; title: string; state: string; createdAt: string; sourceCount: number }>(
      '/reports/inputs', body
    ).then((r) => r.data),
  inputs: (id: string) => v2.get(`/reports/${id}/inputs`).then((r) => r.data),
  list: (params: ListPaginationParams & { q?: string; cursor?: string; limit?: number } = {}) =>
    v2.get<Page<ReportSummary>>('/reports', { params }).then((r) => r.data),
  start: (id: string, body: {
    modelConfigId: string
    instruction?: string
    promptId?: string
    promptVersionId?: string
  }) =>
    v2.post<ReportDetail>(`/reports/${id}/generate`, body).then((r) => r.data),
  detail: (id: string) => v2.get<ReportDetail>(`/reports/${id}`).then((r) => r.data),
  source: (id: string, ordinal: number) =>
    v2.get<ReportSourceSnapshot>(`/reports/${id}/sources/${ordinal}`).then((r) => r.data),
  calls: (id: string) => v2.get<ReportCallSummary[]>(`/reports/${id}/calls`).then((r) => r.data),
  download: (id: string) =>
    v2.get(`/reports/${id}/download`, { responseType: 'blob' }).then((r) => r.data as Blob)
}

export const modelCallsApi = {
  detail: (id: string) =>
    v2.get<CallDetailFull>(`/model-calls/${id}/full`).then((r) => r.data)
}
