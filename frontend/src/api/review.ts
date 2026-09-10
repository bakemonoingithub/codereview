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

export function triggerReview(projectId: string, data: { branch: string; strategyId: string; scope: string[]; mergeFiles?: boolean }) {
  return request.post(`/projects/${projectId}/reviews/trigger`, data) as Promise<any>
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
