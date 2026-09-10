import type { ReviewRecord } from '@/api/review'

/**
 * 审查结果的**纯逻辑**：类型推断、JSON 解析、记录元信息格式化。
 *
 * 从 `views/ProjectDetail.vue` 抽出来，原因是"代码审查"tab 与"审查记录-查看"弹窗
 * 必须对同一条记录得出**完全一致**的结论 —— 内联在组件里迟早会漂移，且无法单测。
 * 本文件不依赖任何组件/DOM。
 */

/** 结果类型标签（与 resultJson 的实际形状一一对应） */
export type ResultTypeAlias =
  | 'diff-review'
  | 'llm-review'
  | 'coupling'
  | 'design-pattern'
  | 'api-review'
  | 'raw'
  | 'none'

export type JsonObject = Record<string, unknown>

const STATUS_LABELS = ['排队', '执行中', '成功', '失败', '部分成功']
const STATUS_COLORS = ['default', 'processing', 'green', 'red', 'orange']

/**
 * 安全解析 resultJson：非对象（数组/标量/非法 JSON）一律退化为 `{}`。
 * 列表接口可能返回 `null`，触发中的记录可能是空串。
 */
export function parseResultJson(json?: string | null): JsonObject {
  if (!json) {
    return {}
  }
  try {
    const parsed = JSON.parse(json)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as JsonObject) : {}
  } catch {
    return {}
  }
}

/**
 * 由 resultJson 推断结果类型。
 *
 * 判定顺序即优先级，不可随意调整：
 * 1. `units` 数组 —— diff-review 与 llm-review 共用该结构，靠有无 `commit` 区分
 *    （diff 审查必然锚定到一次提交）；
 * 2. `nodes` + `edges` —— 耦合分析；`patterns` —— 设计模式；
 * 3. 其余按显式 `type` 标记、裸文本、错误兜底。
 */
export function inferResultType(result: unknown): ResultTypeAlias {
  const r = (result || {}) as JsonObject
  if (Array.isArray(r.units)) {
    return r.commit ? 'diff-review' : 'llm-review'
  }
  if (Array.isArray(r.nodes) && Array.isArray(r.edges)) {
    return 'coupling'
  }
  if (Array.isArray(r.patterns)) {
    return 'design-pattern'
  }
  if (r.type === 'api-review') {
    return 'api-review'
  }
  if (typeof r.raw === 'string' && r.raw) {
    return 'raw'
  }
  if (r.error) {
    return 'raw'
  }
  return 'none'
}

/** 记录状态中文名（越界值统一显示"未知"，不抛错） */
export function recordStatusText(status: number): string {
  return STATUS_LABELS[status] || '未知'
}

/** 记录状态对应的 a-tag 颜色 */
export function recordStatusColor(status: number): string {
  return STATUS_COLORS[status] || 'default'
}

/** 状态提示条（仅 status >= 2 有意义） */
export function recordStatusAlert(status: number): { type: string; text: string } {
  if (status === 2) {
    return { type: 'success', text: '审查成功' }
  }
  if (status === 3) {
    return { type: 'error', text: '审查失败' }
  }
  if (status === 4) {
    return { type: 'warning', text: '部分成功（部分单元失败，可重审）' }
  }
  return { type: 'info', text: '' }
}

/** 执行中/排队中的记录还没有可看的结果 */
export function isRunning(status: number): boolean {
  return status < 2
}

/** 只有失败或部分成功才值得重审 —— 只读弹窗里据此决定是否显示重审入口 */
export function canRetry(status: number): boolean {
  return status === 3 || status === 4
}

export function shortSha(sha?: string | null): string {
  return sha ? sha.slice(0, 7) : '—'
}

/** 列表里策略只回传 id，能查到名字就显示"名字 (id)" */
export function strategyLabel(strategyId?: string | null, name?: string | null): string {
  if (!strategyId) {
    return '—'
  }
  return name ? `${name} (${strategyId})` : strategyId
}

export function formatTime(value?: string | null): string {
  return value || '—'
}

/** 比较基线：merge 提交要显式标注，否则容易误读 diff 范围 */
export function baseShaLabel(commit?: JsonObject | null): string {
  const base = commit && typeof commit.baseSha === 'string' ? commit.baseSha : ''
  return shortSha(base) + (commit && commit.merge ? '（merge 提交）' : '')
}

/**
 * 审查范围：优先用持久化的 scopeJson（列表接口带回来），
 * 缺失时退化为 trace 结果里各单元路径去重。
 */
export function scopePaths(record?: ReviewRecord | null, result?: JsonObject | null): string[] {
  const raw = record?.scopeJson
  if (raw) {
    try {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) {
        return parsed.filter((p): p is string => typeof p === 'string')
      }
    } catch {
      /* scopeJson 不合法时继续走兜底 */
    }
  }
  const units = (result || {}).units
  if (!Array.isArray(units)) {
    return []
  }
  const paths = units.map((u) => (u as { path?: unknown }).path).filter((p): p is string => typeof p === 'string')
  return Array.from(new Set(paths))
}
