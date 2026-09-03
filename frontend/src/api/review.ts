import request from '@/utils/request'

export interface ReviewRecord {
  id: string
  projectId: string
  branch: string
  commitSha?: string
  status: number
  progress: number
  resultJson?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export function triggerReview(projectId: string, data: { branch: string; scope: string[] }) {
  return request.post(`/projects/${projectId}/reviews/trigger`, data) as Promise<any>
}

export function getReview(id: string) {
  return request.get(`/reviews/${id}`) as Promise<ReviewRecord>
}

export function listReviews(projectId: string, params: { pageNum?: number; pageSize?: number } = {}) {
  return request.get(`/projects/${projectId}/reviews`, { params }) as Promise<any>
}
