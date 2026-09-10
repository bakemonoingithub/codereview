import request from '@/utils/request'

export interface ModelConfig {
  id: string
  name: string
  baseUrl?: string
  modelName?: string
  status?: number
  hasToken?: boolean
  createdAt: string
}

export function listModels(params: { pageNum?: number; pageSize?: number } = {}) {
  return request.get('/models', { params }) as Promise<any>
}

export function createModel(data: { name: string; baseUrl?: string; token?: string; modelName?: string }) {
  return request.post('/models', data) as Promise<any>
}

export function updateModel(id: string, data: { name?: string; baseUrl?: string; token?: string; modelName?: string; clearToken?: boolean }) {
  return request.put(`/models/${id}`, data) as Promise<any>
}

export function deleteModel(id: string) {
  return request.delete(`/models/${id}`) as Promise<any>
}

export function verifyModel(id: string) {
  return request.post(`/models/${id}/verify`) as Promise<any>
}
