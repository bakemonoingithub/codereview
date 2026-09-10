import request from '@/utils/request'

export interface Strategy {
  id: string
  name: string
  analyzerType: number
  paramsJson?: string
  createdAt: string
}

export function listStrategies(params: { pageNum?: number; pageSize?: number; keyword?: string; analyzerType?: number } = {}) {
  return request.get('/strategies', { params }) as Promise<any>
}

export function createStrategy(data: { name: string; analyzerType: number; params: Record<string, any> }) {
  return request.post('/strategies', data) as Promise<any>
}

export function updateStrategy(id: string, data: { name?: string; analyzerType?: number; params?: Record<string, any> }) {
  return request.put(`/strategies/${id}`, data) as Promise<any>
}

export function deleteStrategy(id: string) {
  return request.delete(`/strategies/${id}`) as Promise<any>
}

export const ANALYZER_TYPES = [
  { value: 1, label: 'llm-review' },
  { value: 2, label: 'coupling' },
  { value: 3, label: 'design-pattern' },
  { value: 4, label: 'api-review' },
  { value: 5, label: 'diff-review' }
]

/** diff 审查专用：只需在选择该分析器时展示 */
export const DIFF_REVIEW_TYPE = 5

export function analyzerLabel(t: number): string {
  return ANALYZER_TYPES.find((a) => a.value === t)?.label || `类型${t}`
}
