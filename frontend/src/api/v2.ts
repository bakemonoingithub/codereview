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
  baseURL: import.meta.env.VITE_API_BASE_URL_V2 || '/api/v2',
  timeout: 30000,
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

export interface Page<T> {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
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

export interface StrategyView {
  id: string
  name: string
  specialistType: 'ISSUE' | 'PATTERN' | 'COUPLING'
  modelConfigId: string | null
  promptId: string | null
  revision: number
  createdAt: string
  updatedAt: string
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

export interface TaskCreateInput {
  projectId: string
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
  strategyId?: string
  modelConfigId?: string
  smartSplit?: boolean
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
    strategyId: string | null
    strategyName: string | null
    strategyRevision: number | null
    promptId: string | null
    promptVersionNo: number | null
  }
}

export interface TaskSummary {
  id: string
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

export interface TaskCopyInput {
  projectId: string
  route: string
  specialistType: 'ISSUE' | 'PATTERN' | 'COUPLING'
  sourceKind: 'FILES' | 'DIFF'
  branch: string | null
  commitSha: string | null
  baseSha: string | null
  targets: { path: string; oldPath: string | null; note: string | null }[]
  strategyId: string | null
  modelConfigId: string | null
  smartSplit: boolean
  reobserveHead: boolean
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
  list: (params: {
    projectId?: string
    type?: string
    state?: string
    q?: string
    cursor?: string
    limit?: number
  } = {}) => v2.get<Page<TaskSummary>>('/review-tasks', { params }).then((r) => r.data),
  detail: (id: string) => v2.get<TaskView>(`/review-tasks/${id}`).then((r) => r.data),
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
  list: (params: { q?: string; cursor?: string; limit?: number } = {}) =>
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
    v2.put<ProjectView>(`/projects/${id}`, body).then((r) => r.data)
}

export const modelsApi = {
  list: (params: { q?: string; cursor?: string; limit?: number } = {}) =>
    v2.get<Page<ModelView>>('/models', { params }).then((r) => r.data),
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
  validate: (id: string) => v2.post<ModelValidation>(`/models/${id}/validate`).then((r) => r.data)
}

export const promptsApi = {
  list: (params: { q?: string; cursor?: string; limit?: number } = {}) =>
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
  addVersion: (id: string, body: { content: string; expectedRevision: number }) =>
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

export const strategiesApi = {
  list: ({ specialistType, ...params }: { q?: string; specialistType?: string; cursor?: string; limit?: number } = {}) =>
    v2.get<Page<StrategyView>>('/strategies', { params: { ...params, type: specialistType } }).then((r) => r.data),
  create: (body: {
    name: string
    specialistType: string
    modelConfigId?: string
    promptId?: string
  }) => v2.post<StrategyView>('/strategies', body).then((r) => r.data),
  update: (
    id: string,
    body: {
      name: string
      specialistType: string
      modelConfigId?: string
      promptId?: string
      expectedRevision: number
    }
  ) => v2.put<StrategyView>(`/strategies/${id}`, body).then((r) => r.data),
  remove: (id: string) => v2.delete(`/strategies/${id}`).then((r) => r.data)
}
