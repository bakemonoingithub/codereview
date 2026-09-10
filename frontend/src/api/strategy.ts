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

export function createStrategy(data: { name: string; analyzerType: number; modelConfigId: string; threshold?: number }) {
  return request.post('/strategies', data) as Promise<any>
}

export const ANALYZER_TYPES = [
  { value: 1, label: 'llm-review' },
  { value: 2, label: 'coupling' },
  { value: 3, label: 'design-pattern' }
]

export function analyzerLabel(t: number): string {
  return ANALYZER_TYPES.find((a) => a.value === t)?.label || `类型${t}`
}
