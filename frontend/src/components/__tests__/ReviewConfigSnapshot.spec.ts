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
 * 2. **凡是被渲染出来的**下拉框/复选框/审查按钮都不可用；
 * 3. 左栏必须展示**审查范围**，且**不能勾选**。
 *
 * 这里刻意让 SplitPane / ReviewResult / a-tree 保持真实渲染（只 stub 其余 AntDV），
 * 否则查不到 SplitPane 插槽里的内容，也断言不到"树上真的没有复选框"。
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
      // 真实树：要断言"渲染出了哪些节点""有没有复选框"
      'a-tree': false,
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

  it('下拉框与复选框全部禁用（分支 / 策略 / 多文件合并）', () => {
    const wrapper = mountSnapshot()
    const selects = wrapper.findAll('a-select-stub')
    expect(selects.length).toBe(2)
    for (const select of selects) {
      expect(select.attributes('disabled')).toBeDefined()
    }
    const checkboxes = wrapper.findAll('a-checkbox-stub')
    expect(checkboxes.length).toBeGreaterThan(0)
    for (const checkbox of checkboxes) {
      expect(checkbox.attributes('disabled')).toBeDefined()
    }
  })

  it('「开始审查」置灰；展开/收起可用（纯浏览，不改任何数据）', () => {
    const wrapper = mountSnapshot()
    // 只看本快照渲染的按钮：SplitPane 自带的"全屏"布局按钮属于外壳
    const buttons = wrapper.findAll('a-button-stub').filter((b) => !b.html().includes('fullscreen'))

    const startAudit = buttons.find((b) => b.text() === '开始审查')
    expect(startAudit, '应保留置灰的「开始审查」以体现"同一套界面被锁住"').toBeTruthy()
    expect(startAudit!.attributes('disabled')).toBeDefined()

    for (const label of ['展开全部', '收起全部']) {
      const button = buttons.find((b) => b.text() === label)
      expect(button, `应有「${label}」`).toBeTruthy()
      expect(button!.attributes('disabled'), `「${label}」应可用`).toBeUndefined()
    }
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

describe('ReviewConfigSnapshot 左栏审查范围', () => {
  const withScope = {
    ...record,
    scopeJson: JSON.stringify(['src/a/A.java', 'src/b/B.java'])
  }

  it('按 scopeJson 渲染范围树与文件计数', () => {
    const wrapper = mountSnapshot({ record: withScope })

    expect(wrapper.text()).toContain('审查范围：2 个文件')
    // 目录与叶子都应出现（默认全展开）
    expect(wrapper.text()).toContain('src')
    expect(wrapper.text()).toContain('A.java')
    expect(wrapper.text()).toContain('B.java')
  })

  it('范围树**不可勾选**：树本身 checkable=false 且没有任何复选框', () => {
    const wrapper = mountSnapshot({ record: withScope })

    const tree = wrapper.findComponent({ name: 'ATree' })
    expect(tree.exists()).toBe(true)
    expect(tree.props('checkable')).toBe(false)
    // 关键 DOM 断言：树上不该出现勾选框
    expect(wrapper.findAll('.ant-tree-checkbox').length).toBe(0)
  })

  it('scopeJson 缺失时回退到审查单元路径（老记录也能看到范围）', () => {
    // 默认 record 没有 scopeJson，但 resultJson 里有 units[].path
    const wrapper = mountSnapshot()

    expect(wrapper.text()).toContain('审查范围：1 个文件')
    expect(wrapper.text()).toContain('A.java')
  })

  it('既没有 scopeJson 也没有单元路径时给空态，而不是空白一片', () => {
    const wrapper = mountSnapshot({
      record: { ...record, scopeJson: undefined, resultJson: JSON.stringify({ summary: '无单元' }) }
    })

    // a-empty 的 description 是 prop（stub 渲染成元素属性），不是插槽文本
    const empty = wrapper.find('a-empty-stub')
    expect(empty.exists()).toBe(true)
    expect(empty.attributes('description')).toBe('该记录未记录审查范围')
    expect(wrapper.findComponent({ name: 'ATree' }).exists()).toBe(false)
    expect(wrapper.text()).not.toContain('审查范围：')
  })

  it('收起全部后叶子节点收起（按钮真的生效，不是摆设）', async () => {
    const wrapper = mountSnapshot({ record: withScope })
    expect(wrapper.text()).toContain('A.java')

    const collapse = wrapper
      .findAll('a-button-stub')
      .find((b) => b.text() === '收起全部')!
    await collapse.trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).not.toContain('A.java')
    expect(wrapper.text()).toContain('审查范围：2 个文件')
  })

  it('去掉了结构/提交两个卡片页签（内容一致，留着只会让人以为能切出别的东西）', () => {
    const wrapper = mountSnapshot({ record: withScope })

    expect(wrapper.text()).not.toContain('结构视图')
    expect(wrapper.text()).not.toContain('提交视图')
  })
})
