import request from '@/utils/request'

export interface ReportItem {
  id: string
  projectId: string
  name: string
  contentMarkdown?: string
  status: number
  progress: number
  createdAt: string
}

export function generateReport(projectId: string, data: { name?: string; modelConfigId: string; recordIds: string[]; promptId?: string }) {
  return request.post(`/projects/${projectId}/reports`, data) as Promise<any>
}

export function listReports(projectId: string, params: { pageNum?: number; pageSize?: number } = {}) {
  return request.get(`/projects/${projectId}/reports`, { params }) as Promise<any>
}

export function getReport(id: string) {
  return request.get(`/reports/${id}`) as Promise<ReportItem>
}
