/**
 * 准确率展示的纯逻辑（服务验收指标 5：准确率 ≥70%，且基础静态规则与 AI 规则分别复核）。
 */

export interface AccuracyStat {
  analyzerType: number
  analyzerName: string
  /** static（基础静态规则）/ ai（AI 规则） */
  category: string
  totalIssues: number
  markedIssues: number
  falsePositives: number
  accepted: number
  overallAccuracy: number
  reviewedAccuracy: number
  reviewCoverage: number
}

/** 验收指标 5 的门槛 */
export const ACCURACY_THRESHOLD = 0.7

export type Level = 'high' | 'medium' | 'low' | 'none'

export const ANALYZER_NAMES: Record<number, string> = {
  1: 'LLM 审查',
  2: '耦合度分析',
  3: '设计模式识别',
  4: 'API 审查（SonarQube）',
  5: '变更审查（diff）'
}

export function analyzerName(type: number): string {
  return ANALYZER_NAMES[type] || '未知分析器'
}

export function categoryLabel(category: string): string {
  return category === 'static' ? '基础静态规则' : 'AI 规则'
}

/** 比率 → 百分比文案；分母为 0 时显示 '—' 而不是 0%，避免误导 */
export function percent(value: number, total?: number): string {
  if (total === 0) {
    return '—'
  }
  return `${(value * 100).toFixed(2)}%`
}

export function accuracyLevel(accuracy: number): Level {
  if (accuracy >= ACCURACY_THRESHOLD) {
    return 'high'
  }
  if (accuracy >= 0.5) {
    return 'medium'
  }
  return 'low'
}

/** 覆盖率只分三档：0 视为 none（还没开始复核） */
export function coverageLevel(coverage: number): Level {
  if (coverage <= 0) {
    return 'none'
  }
  if (coverage >= 0.8) {
    return 'high'
  }
  if (coverage >= 0.3) {
    return 'medium'
  }
  return 'low'
}

export function levelColor(level: Level): string {
  switch (level) {
    case 'high':
      return 'green'
    case 'medium':
      return 'orange'
    case 'low':
      return 'red'
    default:
      return 'default'
  }
}

/** 是否达标（用于"整体准确率"徽标） */
export function isPassing(stat: AccuracyStat): boolean {
  return stat.totalIssues > 0 && stat.overallAccuracy >= ACCURACY_THRESHOLD
}
