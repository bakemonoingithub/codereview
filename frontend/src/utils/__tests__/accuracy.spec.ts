import { describe, expect, it } from 'vitest'
import {
  ACCURACY_THRESHOLD,
  accuracyLevel,
  analyzerName,
  categoryLabel,
  coverageLevel,
  isPassing,
  levelColor,
  percent,
  type AccuracyStat
} from '../accuracy'

function stat(overrides: Partial<AccuracyStat> = {}): AccuracyStat {
  return {
    analyzerType: 5,
    analyzerName: '变更审查（diff）',
    category: 'ai',
    totalIssues: 10,
    markedIssues: 4,
    falsePositives: 1,
    accepted: 3,
    overallAccuracy: 0.9,
    reviewedAccuracy: 0.75,
    reviewCoverage: 0.4,
    ...overrides
  }
}

describe('展示名', () => {
  it('按分析器类型给中文名', () => {
    expect(analyzerName(1)).toBe('LLM 审查')
    expect(analyzerName(4)).toBe('API 审查（SonarQube）')
    expect(analyzerName(5)).toBe('变更审查（diff）')
    expect(analyzerName(99)).toBe('未知分析器')
  })

  it('区分基础静态规则与 AI 规则（指标 5 要求分别复核）', () => {
    expect(categoryLabel('static')).toBe('基础静态规则')
    expect(categoryLabel('ai')).toBe('AI 规则')
  })
})

describe('百分比格式化', () => {
  it('保留两位小数', () => {
    expect(percent(0.6667)).toBe('66.67%')
    expect(percent(1)).toBe('100.00%')
    expect(percent(0)).toBe('0.00%')
  })

  it('分母为 0 时显示 — 而不是 0%，避免误导', () => {
    expect(percent(0, 0)).toBe('—')
  })
})

describe('准确率分档（门槛取验收指标 5 的 70%）', () => {
  it('门槛值为 0.7', () => {
    expect(ACCURACY_THRESHOLD).toBe(0.7)
  })

  it('>=70% 为高，50%~70% 为中，<50% 为低', () => {
    expect(accuracyLevel(0.7)).toBe('high')
    expect(accuracyLevel(0.95)).toBe('high')
    expect(accuracyLevel(0.69)).toBe('medium')
    expect(accuracyLevel(0.5)).toBe('medium')
    expect(accuracyLevel(0.49)).toBe('low')
  })

  it('覆盖率 0 单独归为 none（尚未开始复核）', () => {
    expect(coverageLevel(0)).toBe('none')
    expect(coverageLevel(0.1)).toBe('low')
    expect(coverageLevel(0.3)).toBe('medium')
    expect(coverageLevel(0.8)).toBe('high')
  })

  it('分档映射到颜色', () => {
    expect(levelColor('high')).toBe('green')
    expect(levelColor('medium')).toBe('orange')
    expect(levelColor('low')).toBe('red')
    expect(levelColor('none')).toBe('default')
  })
})

describe('是否达标', () => {
  it('无 issue 不算达标', () => {
    expect(isPassing(stat({ totalIssues: 0, overallAccuracy: 1 }))).toBe(false)
  })

  it('达到 70% 才算达标', () => {
    expect(isPassing(stat({ overallAccuracy: 0.7 }))).toBe(true)
    expect(isPassing(stat({ overallAccuracy: 0.6999 }))).toBe(false)
  })
})
