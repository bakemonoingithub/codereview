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
  /**
   * 失败原因（后端 `review_record.error_message`，有界列）。
   *
   * 失败时 `resultJson` 可能为空、也可能仍是**上一次成功**的结果（失败不覆盖它），
   * 所以"为什么失败"只能看这个字段，不能从结果 JSON 的 summary 里猜。
   */
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

/**
 * 审查记录**列表行**（对应后端 `ReviewRecordRow`）。
 *
 * 刻意**不继承** {@link ReviewRecord}：列表接口不返回 `resultJson` / `scopeJson`
 * （前者单条可达 MB 级，后者只有详情用得上），而 `strategySnapshotJson` 内含模型明文
 * apiKey、后端已彻底排除。用独立类型是为了让"想在列表行上读 resultJson"变成编译错误，
 * 而不是运行时拿到 undefined 后静默渲染空白。
 *
 * 需要完整结果请用 {@link getReview} 按 id 取详情。
 */
export interface ReviewRecordRow {
  id: string
  projectId: string
  strategyId?: string
  /** 列表查询顺带 join 出来的策略名，策略已删时为空 */
  strategyName?: string
  branch: string
  commitSha?: string
  status: number
  progress: number
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

/**
 * 分页响应的最小形状（后端为 MyBatis-Plus `Page` 的序列化结果：
 * `{records,total,current,size}`，另附 `pages` 等派生字段，前端不用）。
 */
export interface Page<T> {
  records: T[]
  total: number
  current: number
  size: number
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

export function listReviews(
  projectId: string,
  params: { pageNum?: number; pageSize?: number; statusMin?: number } = {}
) {
  return request.get(`/projects/${projectId}/reviews`, { params }) as Promise<Page<ReviewRecordRow>>
}

export function parseResult(json?: string): any {
  if (!json) return {}
  try {
    return JSON.parse(json)
  } catch {
    return {}
  }
}
