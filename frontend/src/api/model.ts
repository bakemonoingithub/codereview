import request from '@/utils/request'

export interface ModelConfig {
  id: string
  name: string
  baseUrl?: string
  modelName?: string
  status?: number
  createdAt: string
}

export function listModels(params: { pageNum?: number; pageSize?: number } = {}) {
  return request.get('/models', { params }) as Promise<any>
}

export function createModel(data: { name: string; baseUrl?: string; token?: string; modelName?: string }) {
  return request.post('/models', data) as Promise<any>
}
