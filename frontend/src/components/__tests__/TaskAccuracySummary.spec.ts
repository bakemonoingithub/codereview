// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskAccuracySummary from '@/components/TaskAccuracySummary.vue'
import type { TaskStatistics } from '@/api/v2'

const zero = { total: 0, accurate: 0, inaccurate: 0, unknown: 0, unassessed: 0, accuracyPercent: null }

function stats(type: TaskStatistics['specialistType'], issues: Partial<TaskStatistics['issues']> = {},
               results: Partial<TaskStatistics['results']> = {}): TaskStatistics {
  return {
    taskId: 't1', specialistType: type,
    issues: { ...zero, ...issues }, results: { ...zero, ...results }
  }
}

describe('任务级准确率摘要', () => {
  it('新条目统计优先于历史整份统计，模式与耦合不混算', () => {
    for (const type of ['PATTERN', 'COUPLING'] as const) {
      const data = stats(type, {}, { total: 1, accurate: 1, accuracyPercent: 100 })
      data.patterns = { ...zero, total: 2, accurate: 1, inaccurate: 1, accuracyPercent: 50 }
      data.couplings = { ...zero, total: 3, unknown: 2, unassessed: 1 }
      const wrapper = mount(TaskAccuracySummary, { props: { specialistType: type, stats: data } })
      expect(wrapper.text()).toContain(type === 'PATTERN' ? '模式候选判定准确率' : '依赖解读判定准确率')
      expect(wrapper.text()).toContain(type === 'PATTERN' ? '50%' : '暂无明确判定')
      expect(wrapper.text()).not.toContain('100%')
      wrapper.unmount()
    }
  })

  it('问题审查展示问题级比例与四类计数', () => {
    const wrapper = mount(TaskAccuracySummary, {
      props: { specialistType: 'ISSUE', stats: stats('ISSUE', { total: 3, accurate: 2, inaccurate: 1, accuracyPercent: 67 }) }
    })
    expect(wrapper.text()).toContain('本任务问题判定准确率')
    expect(wrapper.text()).toContain('67%')
    expect(wrapper.text()).toContain('准确 2 · 不准确 1 · 无法判断 0 · 未反馈 0')
    expect(wrapper.text()).toContain('仅统计本任务已完成结果中的问题')
    wrapper.unmount()
  })

  it('分母为零时显示暂无明确判定而不是 0% 或 100%', () => {
    const wrapper = mount(TaskAccuracySummary, {
      props: { specialistType: 'ISSUE', stats: stats('ISSUE', { total: 2, unknown: 1, unassessed: 1 }) }
    })
    expect(wrapper.text()).toContain('— · 暂无明确判定')
    expect(wrapper.text()).not.toContain('0%')
    expect(wrapper.text()).not.toContain('100%')
    wrapper.unmount()
  })

  it('模式与耦合使用结果级计数，不混入问题级', () => {
    const pattern = mount(TaskAccuracySummary, {
      props: { specialistType: 'PATTERN', stats: stats('PATTERN', { total: 9, accurate: 9 }, { total: 1, accurate: 1, accuracyPercent: 100 }) }
    })
    expect(pattern.text()).toContain('本任务模式结果判定准确率')
    expect(pattern.text()).toContain('100%')
    expect(pattern.text()).toContain('仅统计本任务已完整发布的模式／耦合结果')
    pattern.unmount()

    const coupling = mount(TaskAccuracySummary, {
      props: { specialistType: 'COUPLING', stats: stats('COUPLING', {}, { total: 1, inaccurate: 1, accuracyPercent: 0 }) }
    })
    expect(coupling.text()).toContain('本任务耦合结果判定准确率')
    expect(coupling.text()).toContain('0%')
    coupling.unmount()
  })
})
