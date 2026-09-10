import { describe, expect, it } from 'vitest'
import {
  baseShaLabel,
  canRetry,
  formatTime,
  inferResultType,
  isRunning,
  parseResultJson,
  recordStatusAlert,
  recordStatusColor,
  recordStatusText,
  scopePaths,
  shortSha,
  strategyLabel
} from '../reviewResult'

describe('resultJson 解析', () => {
  it('空值/空串/非法 JSON 一律退化为空对象（不能让整页白屏）', () => {
    expect(parseResultJson(undefined)).toEqual({})
    expect(parseResultJson(null)).toEqual({})
    expect(parseResultJson('')).toEqual({})
    expect(parseResultJson('{ 坏掉的 json')).toEqual({})
  })

  it('数组与标量不算结果对象', () => {
    expect(parseResultJson('[1,2,3]')).toEqual({})
    expect(parseResultJson('42')).toEqual({})
    expect(parseResultJson('"str"')).toEqual({})
  })

  it('正常对象原样返回', () => {
    expect(parseResultJson('{"units":[]}')).toEqual({ units: [] })
  })
})

describe('结果类型推断（结果区按它分派渲染组件）', () => {
  it('空结果归为 none', () => {
    expect(inferResultType({})).toBe('none')
    expect(inferResultType(null)).toBe('none')
    expect(inferResultType(undefined)).toBe('none')
  })

  it('units + commit 为 diff 审查，只有 units 为 llm 审查', () => {
    expect(inferResultType({ units: [], commit: { sha: 'abc' } })).toBe('diff-review')
    expect(inferResultType({ units: [] })).toBe('llm-review')
  })

  it('nodes+edges 为耦合，patterns 为设计模式', () => {
    expect(inferResultType({ nodes: [], edges: [] })).toBe('coupling')
    expect(inferResultType({ patterns: [] })).toBe('design-pattern')
    // 只有 nodes 没有 edges 不足以判定为耦合分析
    expect(inferResultType({ nodes: [] })).toBe('none')
  })

  it('api-review 走显式 type 标记', () => {
    expect(inferResultType({ type: 'api-review', issues: [] })).toBe('api-review')
  })

  it('裸文本与非空 error 都退化为 raw 展示', () => {
    expect(inferResultType({ raw: '纯文本' })).toBe('raw')
    expect(inferResultType({ error: '分析器炸了' })).toBe('raw')
    // 空 raw 不构成可展示内容
    expect(inferResultType({ raw: '' })).toBe('none')
  })

  it('优先级：units 先于 patterns / raw 判定', () => {
    expect(inferResultType({ units: [], patterns: [], raw: 'x' })).toBe('llm-review')
  })
})

describe('记录状态映射', () => {
  it('0-4 对应中文名与标签色', () => {
    expect([0, 1, 2, 3, 4].map(recordStatusText)).toEqual([
      '排队',
      '执行中',
      '成功',
      '失败',
      '部分成功'
    ])
    expect([0, 1, 2, 3, 4].map(recordStatusColor)).toEqual([
      'default',
      'processing',
      'green',
      'red',
      'orange'
    ])
  })

  it('越界状态不抛错', () => {
    expect(recordStatusText(9)).toBe('未知')
    expect(recordStatusColor(9)).toBe('default')
  })

  it('status < 2 为执行中，3/4 才可重审', () => {
    expect([0, 1].every(isRunning)).toBe(true)
    expect([2, 3, 4].some(isRunning)).toBe(false)
    expect([0, 1, 2].some(canRetry)).toBe(false)
    expect(canRetry(3)).toBe(true)
    expect(canRetry(4)).toBe(true)
  })

  it('状态提示条只在终态给出文案', () => {
    expect(recordStatusAlert(2)).toEqual({ type: 'success', text: '审查成功' })
    expect(recordStatusAlert(3).type).toBe('error')
    expect(recordStatusAlert(4).type).toBe('warning')
    expect(recordStatusAlert(1).text).toBe('')
  })
})

describe('元信息格式化', () => {
  it('sha 截断为 7 位，缺失显示破折号', () => {
    expect(shortSha('0123456789abcdef')).toBe('0123456')
    expect(shortSha('')).toBe('—')
    expect(shortSha(undefined)).toBe('—')
  })

  it('策略优先显示名字并附 id，便于对照列表里的 id 列', () => {
    expect(strategyLabel('12', '变更审查')).toBe('变更审查 (12)')
    expect(strategyLabel('12')).toBe('12')
    expect(strategyLabel('')).toBe('—')
  })

  it('比较基线标注 merge 提交', () => {
    expect(baseShaLabel({ baseSha: 'abcdef1234' })).toBe('abcdef1')
    expect(baseShaLabel({ baseSha: 'abcdef1234', merge: true })).toBe('abcdef1（merge 提交）')
    expect(baseShaLabel(null)).toBe('—')
  })

  it('时间缺失显示破折号', () => {
    expect(formatTime('2026-09-10 08:00:00')).toBe('2026-09-10 08:00:00')
    expect(formatTime(null)).toBe('—')
  })
})

describe('审查范围解析', () => {
  it('优先用 scopeJson，非法时退回单元路径', () => {
    expect(scopePaths({ scopeJson: '["a.java","b.java"]' } as any, {})).toEqual(['a.java', 'b.java'])
    expect(scopePaths({ scopeJson: '{坏' } as any, { units: [{ path: 'c.java' }] })).toEqual(['c.java'])
  })

  it('单元路径去重，非字符串项被过滤', () => {
    const result = { units: [{ path: 'a.java' }, { path: 'a.java' }, { path: 42 }, {}] }
    expect(scopePaths({} as any, result)).toEqual(['a.java'])
  })

  it('两者都没有时返回空数组', () => {
    expect(scopePaths({} as any, {})).toEqual([])
    expect(scopePaths(null, null)).toEqual([])
  })
})
