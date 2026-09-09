import request from '@/utils/request'

export interface Strategy {
  id: string
  name: string
  analyzerType: number
  paramsJson?: string
  createdAt: string
}

export function listStrategies(params: { pageNum?: number; pageSize?: number } = {}) {
  return request.get('/strategies', { params }) as Promise<any>
}

export function createStrategy(data: { name: string; modelConfigId: string }) {
  return request.post('/strategies', data) as Promise<any>
}
