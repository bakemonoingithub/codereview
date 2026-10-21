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

export const projectsApi = {
  list: (params: { q?: string; cursor?: string; limit?: number } = {}) =>
    v2.get<Page<ProjectView>>('/projects', { params }).then((r) => r.data),
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
