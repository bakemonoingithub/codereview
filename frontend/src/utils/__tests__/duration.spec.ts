import { describe, expect, it } from 'vitest'
import { formatDuration, formatElapsed, formatSeconds, hasFinished, parseTimestamp } from '../duration'

describe('时间戳解析', () => {
  it('能解析后端的空格分隔格式（Safari 对它的支持没有保证，故统一转成 ISO 的 T）', () => {
    expect(parseTimestamp('2026-09-10 08:00:00')).toBe(Date.parse('2026-09-10T08:00:00'))
  })

  it('已带 T / 带时区的 ISO 原样解析', () => {
    expect(parseTimestamp('2026-09-10T08:00:00')).toBe(Date.parse('2026-09-10T08:00:00'))
    expect(parseTimestamp('2026-09-10T08:00:00Z')).toBe(Date.parse('2026-09-10T08:00:00Z'))
  })

  it('空值与非法值都返回 null，而不是 NaN 或 Invalid Date 污染下游', () => {
    expect(parseTimestamp(undefined)).toBeNull()
    expect(parseTimestamp(null)).toBeNull()
    expect(parseTimestamp('')).toBeNull()
    expect(parseTimestamp('不是时间')).toBeNull()
  })
})

describe('秒数格式化', () => {
  it('小于 1 分钟显示秒', () => {
    expect(formatSeconds(0)).toBe('0 秒')
    expect(formatSeconds(45)).toBe('45 秒')
    expect(formatSeconds(59)).toBe('59 秒')
  })

  it('分钟级：整分钟不带秒', () => {
    expect(formatSeconds(60)).toBe('1 分')
    expect(formatSeconds(754)).toBe('12 分 34 秒')
    expect(formatSeconds(900)).toBe('15 分')
  })

  it('小时级：指标 8 要自证"< 1 小时"，必须显示出小时', () => {
    expect(formatSeconds(3600)).toBe('1 小时')
    expect(formatSeconds(3660)).toBe('1 小时 1 分')
    expect(formatSeconds(7500)).toBe('2 小时 5 分')
  })

  it('负数按 0 处理（时钟回拨不应显示负耗时）', () => {
    expect(formatSeconds(-10)).toBe('0 秒')
  })
})

describe('已完成耗时', () => {
  it('正常计算差值', () => {
    expect(formatDuration('2026-09-10 08:00:00', '2026-09-10 08:12:34')).toBe('12 分 34 秒')
    expect(formatDuration('2026-09-10 08:00:00', '2026-09-10 09:00:00')).toBe('1 小时')
  })

  it('缺任一时间戳都显示破折号，不用 now 顶替', () => {
    // 关键：不能把"还在跑"的记录显示成一个貌似已完成的耗时
    expect(formatDuration('2026-09-10 08:00:00', null)).toBe('—')
    expect(formatDuration(null, '2026-09-10 08:00:00')).toBe('—')
    expect(formatDuration(undefined, undefined)).toBe('—')
  })

  it('时间戳非法时显示破折号', () => {
    expect(formatDuration('坏值', '2026-09-10 08:00:00')).toBe('—')
  })

  it('结束早于开始时按 0 处理', () => {
    expect(formatDuration('2026-09-10 09:00:00', '2026-09-10 08:00:00')).toBe('0 秒')
  })
})

describe('进行中已耗时', () => {
  it('按传入的 now 计算，便于测试与手动刷新', () => {
    const now = Date.parse('2026-09-10T08:05:00')
    expect(formatElapsed('2026-09-10 08:00:00', now)).toBe('5 分')
  })

  it('缺开始时间显示破折号', () => {
    expect(formatElapsed(null)).toBe('—')
  })
})

describe('是否已完成', () => {
  it('有结束时间才算完成', () => {
    expect(hasFinished('2026-09-10 08:00:00')).toBe(true)
    expect(hasFinished(null)).toBe(false)
    expect(hasFinished('坏值')).toBe(false)
  })
})
