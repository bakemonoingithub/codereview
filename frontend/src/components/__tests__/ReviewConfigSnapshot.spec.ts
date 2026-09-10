// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ReviewConfigSnapshot from '@/components/ReviewConfigSnapshot.vue'
import ReviewResult from '@/components/ReviewResult.vue'
import { antStubs } from '@/testUtils/antStubs'
import type { ReviewRecord } from '@/api/review'

/**
 * 「审查记录-查看」只读视图的硬约束：
 * 1. 顶部通栏红字提示必须在；
 * 2. **凡是被渲染出来的**下拉框/复选框/审查按钮都不可用。
 *
 * 这里刻意让 SplitPane 与 ReviewResult 保持真实渲染（只 stub AntDV），
 * 否则查不到 SplitPane 插槽里的结果区，"只读是否真的透传下去"就断言不到。
 */
const record: ReviewRecord = {
  id: '1001',
  projectId: 'p1',
  strategyId: '5',
  branch: 'master',
  commitSha: 'abcdef1234567890',
  status: 4,
  progress: 100,
  createdAt: '2026-09-10 08:00:00',
  resultJson: JSON.stringify({ units: [{ path: 'A.java' }], commit: { sha: 'abcdef1' } })
}

const options = {
  global: {
    stubs: {
      ...antStubs,
      DiffReviewResult: true,
      CouplingResult: true,
      PatternResult: true,
      RawResult: true
    }
  }
}

function mountSnapshot(props: Record<string, unknown> = {}) {
  return mount(ReviewConfigSnapshot, {
    props: {
      record,
      projectId: 'p1',
      strategies: [{ value: '5', label: '变更审查（diff）' }],
      ...props
    },
    ...options
  })
}

describe('ReviewConfigSnapshot 只读快照', () => {
  it('顶部给出通栏红字提示，指向「代码审查」页签', () => {
    const wrapper = mountSnapshot()
    const banner = wrapper.find('.readonly-banner')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('仅供查看')
    expect(banner.text()).toContain('代码审查')
  })

  it('下拉框与复选框全部禁用（分支 / 两个策略 / 多文件合并）', () => {
    const wrapper = mountSnapshot()
    expect(wrapper.findAll('a-select-stub').length).toBeGreaterThan(0)
    expect(wrapper.findAll('a-checkbox-stub').length).toBeGreaterThan(0)
    for (const select of wrapper.findAll('a-select-stub')) {
      expect(select.attributes('disabled')).toBeDefined()
    }
    for (const checkbox of wrapper.findAll('a-checkbox-stub')) {
      expect(checkbox.attributes('disabled')).toBeDefined()
    }
  })

  it('审查相关按钮一个都不许可点（开始审查/展开/收起）', () => {
    const wrapper = mountSnapshot()
    // 只看本快照渲染的按钮：SplitPane 自带的"全屏"布局按钮属于外壳，不在本次置灰范围内
    const buttons = wrapper.findAll('a-button-stub').filter((b) => !b.html().includes('fullscreen'))
    // 左栏含"展开全部/收起全部/开始审查 ×2"共至少 4 颗
    expect(buttons.length).toBeGreaterThanOrEqual(4)
    for (const button of buttons) {
      expect(button.attributes('disabled')).toBeDefined()
    }
    expect(wrapper.text()).not.toContain('关闭')
  })

  it('结果区继承只读：没有重审入口，readonly 已透传', () => {
    const wrapper = mountSnapshot()
    expect(wrapper.text()).not.toContain('重审失败单元')
    expect(wrapper.findComponent(ReviewResult).props('readonly')).toBe(true)
  })

  it('结果区继续按记录内容渲染（只读不等于空白）', () => {
    const wrapper = mountSnapshot()
    expect(wrapper.findComponent(ReviewResult).props('record')).toEqual(record)
    expect(wrapper.find('.readonly-banner').text()).toContain('快照')
  })

  it('标记加载状态透传给结果区', () => {
    const wrapper = mountSnapshot({ marksLoading: true, marks: [{ id: 'm1' }] })
    expect(wrapper.findComponent(ReviewResult).props('marks')).toEqual([{ id: 'm1' }])
    expect(wrapper.findComponent(ReviewResult).props('readonly')).toBe(true)
  })

  it('策略名来自传入策略表，用于对照列表里的策略 id', () => {
    const wrapper = mountSnapshot()
    expect(wrapper.props('strategies')).toEqual([{ value: '5', label: '变更审查（diff）' }])
    expect(wrapper.text()).not.toContain('重审失败单元')
  })
})
