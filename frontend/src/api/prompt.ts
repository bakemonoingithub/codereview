import request from '@/utils/request'

export interface PromptItem {
  id: string
  name: string
  description?: string
  tags?: string
  currentVersionId?: string
  createdAt: string
}

export interface PromptDetail {
  id: string
  name: string
  description?: string
  tags: string[]
  currentVersionId?: string
  currentVersionNo?: number
  currentContent?: string
  versions: { id: string; versionNo: number; createdAt: string }[]
}

export interface DiffLine {
  type: string
  text: string
  oldLine?: number
  newLine?: number
}

export function listPrompts(params: { pageNum?: number; pageSize?: number; keyword?: string } = {}) {
  return request.get('/prompts', { params }) as Promise<any>
}

export function createPrompt(data: { name: string; description?: string; tags?: string[]; content: string }) {
  return request.post('/prompts', data) as Promise<any>
}

export function getPrompt(id: string) {
  return request.get(`/prompts/${id}`) as Promise<PromptDetail>
}

export function updatePrompt(id: string, data: { name?: string; description?: string; tags?: string[] }) {
  return request.put(`/prompts/${id}`, data) as Promise<any>
}

export function updatePromptContent(id: string, data: { content: string; createNewVersion: boolean }) {
  return request.put(`/prompts/${id}/content`, data) as Promise<any>
}

export function deletePrompt(id: string) {
  return request.delete(`/prompts/${id}`) as Promise<any>
}

export function diffPrompt(id: string, from: string, to: string) {
  return request.get(`/prompts/${id}/versions/diff`, { params: { from, to } }) as Promise<DiffLine[]>
}
