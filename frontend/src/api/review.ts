import request from '@/utils/request'

export interface ReviewRecord {
  id: string
  projectId: string
  strategyId?: string
  branch: string
  commitSha?: string
  scopeJson?: string
  status: number
  progress: number
  resultJson?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export interface ReviewIssue {
  severity: string
  category?: string
  line?: number
  title: string
  description?: string
  suggestion?: string
}

export interface ReviewUnit {
  path: string
  unit: { kind: string; name: string; lines: string }
  status: 'success' | 'failed'
  issues?: ReviewIssue[]
  summary?: string
  error?: string
}

export interface ReviewResult {
  units: ReviewUnit[]
  summary?: string
}

export function triggerReview(
  projectId: string,
  data: { branch: string; strategyId: string; scope: string[]; mergeFiles?: boolean; commitSha?: string }
) {
  return request.post(`/projects/${projectId}/reviews/trigger`, data) as Promise<any>
}

// ---------------------------------------------------------------------------
// issue 标记（准确率复核，服务验收指标 5）
// ---------------------------------------------------------------------------

/** 0 未标记（撤销）/ 1 误报 / 2 已采纳 */
export const MARK_NONE = 0
export const MARK_FALSE_POSITIVE = 1
export const MARK_ACCEPTED = 2

export interface IssueMark {
  id: string
  recordId: string
  unitPath: string
  issueIndex: number
  markValue: number
}

export function markIssue(recordId: string, data: { unitPath: string; issueIndex: number; markValue: number }) {
  return request.post(`/reviews/${recordId}/marks`, data) as Promise<IssueMark>
}

export function unmarkIssue(recordId: string, unitPath: string, issueIndex: number) {
  return request.delete(`/reviews/${recordId}/marks`, { params: { unitPath, issueIndex } }) as Promise<void>
}

export function listMarks(recordId: string) {
  return request.get(`/reviews/${recordId}/marks`) as Promise<IssueMark[]>
}

export function retryReview(id: string) {
  return request.post(`/reviews/${id}/retry`) as Promise<any>
}

export function getReview(id: string) {
  return request.get(`/reviews/${id}`) as Promise<ReviewRecord>
}

export function listReviews(projectId: string, params: { pageNum?: number; pageSize?: number } = {}) {
  return request.get(`/projects/${projectId}/reviews`, { params }) as Promise<any>
}

export function parseResult(json?: string): any {
  if (!json) return {}
  try {
    return JSON.parse(json)
  } catch {
    return {}
  }
}
