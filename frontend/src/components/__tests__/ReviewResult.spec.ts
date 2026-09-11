// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ReviewResult from '@/components/ReviewResult.vue'
import { antStubs } from '@/testUtils/antStubs'
import { markIssue } from '@/api/review'
import type { ReviewRecord } from '@/api/review'

vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return { ...actual, markIssue: vi.fn() }
})

// 展开单元会去拉文件 patch；测试里不该打真实网络（会连 127.0.0.1:3000 并抛 ECONNREFUSED）
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return { ...actual, getFilePatch: vi.fn().mockResolvedValue(null) }
})

/**
 * 结果区是「代码审查」页签与「审查记录-查看」弹窗共用的组件，
 * 这里只断言与**只读**相关的契约（类型分派本身由 utils/reviewResult 的单测覆盖）。
 */
function makeRecord(overrides: Partial<ReviewRecord> = {}): ReviewRecord {
  return {
    id: '1001',
    projectId: 'p1',
    branch: 'master',
    commitSha: 'abcdef1234567890',
    status: 2,
    progress: 100,
    createdAt: '2026-09-10 08:00:00',
    resultJson: JSON.stringify({ units: [], summary: '无问题' }),
    ...overrides
  }
}

const diffRecord = (status = 2) =>
  makeRecord({
    status,
    resultJson: JSON.stringify({ units: [{ path: 'A.java' }], commit: { sha: 'abcdef1' } })
  })

const options = {
  global: {
    stubs: {
      ...antStubs,
      // 真实 DiffViewer 经 buildExtendData 把评论转成 `{issue, issueIndex}` 再喂给 extend 插槽
      DiffViewer: {
        name: 'DiffViewer',
        props: ['comments'],
        computed: {
          extendItems(this: any) {
            return ((this.comments as any[]) || []).map((c: any) => c.data)
          }
        },
        template: '<div class="diff-viewer-stub"><slot name="extend" :items="extendItems" /></div>'
      },
      CouplingResult: true,
      PatternResult: true,
      RawResult: true
    }
  }
}

/** 端到端只读链路：弹窗 → ReviewResult(readonly) → DiffReviewResult → 行内按钮 */
const diffResult = {
  commit: { sha: 'abcdef1234567890', baseSha: '1234567890abcdef', files: 1 },
  units: [
    {
      path: 'A.java',
      status: 'success',
      unit: { name: 'foo', kind: 'method', lines: '1-10' },
      issues: [{ severity: 'MAJOR', title: '空指针风险', newLine: 5 }]
    }
  ]
}

describe('ReviewResult 只读降级', () => {
  it('未触发审查时给占位文案', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: null, projectId: 'p1' },
      ...options
    })
    expect(wrapper.find('.placeholder').exists()).toBe(true)
    expect(wrapper.text()).toContain('尚未触发审查')
  })

  it('只读态记录尚未到位时提示"正在载入"，而不是让人误以为这条记录是空的', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: null, projectId: 'p1', readonly: true },
      ...options
    })
    expect(wrapper.text()).toContain('正在载入审查记录')
    expect(wrapper.text()).not.toContain('尚未触发审查')
  })

  it('readonly 打开时根节点带 is-readonly', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: makeRecord({ status: 3 }), projectId: 'p1', readonly: true },
      ...options
    })
    expect(wrapper.find('.review-result').classes()).toContain('is-readonly')
  })

  it('失败/部分成功才给重审按钮，成功后不给', () => {
    for (const status of [3, 4]) {
      const wrapper = mount(ReviewResult, {
        props: { record: makeRecord({ status }), projectId: 'p1' },
        ...options
      })
      expect(wrapper.text()).toContain('重审失败单元')
    }
    const success = mount(ReviewResult, {
      props: { record: makeRecord({ status: 2 }), projectId: 'p1' },
      ...options
    })
    expect(success.text()).not.toContain('重审失败单元')
  })

  it('只读态不渲染重审入口（重审只在「审查记录」列表里）', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: makeRecord({ status: 3 }), projectId: 'p1', readonly: true },
      ...options
    })
    expect(wrapper.text()).not.toContain('重审失败单元')
  })

  it('只读态把 readonly 透传给 diff 结果组件（行内标记随之置灰）', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: diffRecord(), projectId: 'p1', readonly: true },
      ...options
    })
    const diff = wrapper.findComponent({ name: 'DiffReviewResult' })
    expect(diff.exists()).toBe(true)
    expect(diff.props('readonly')).toBe(true)
    expect(diff.props('recordId')).toBe('1001')
    expect(diff.props('commitSha')).toBe('abcdef1234567890')
  })

  it('只读态即使收到 mark 事件也不外抛（多一层兜底）', async () => {
    const wrapper = mount(ReviewResult, {
      props: { record: diffRecord(), projectId: 'p1', readonly: true },
      ...options
    })
    await wrapper.findComponent({ name: 'DiffReviewResult' }).vm.$emit('mark', 'A.java', 0, 1)
    expect(wrapper.emitted('mark')).toBeUndefined()
  })

  it('只读端到端：点行内"误报"不发起标记请求、不外抛事件', async () => {
    const wrapper = mount(ReviewResult, {
      props: {
        record: makeRecord({ status: 4, resultJson: JSON.stringify(diffResult) }),
        projectId: 'p1',
        readonly: true
      },
      ...options
    })
    const falsePositive = wrapper
      .findAll('a-button-stub')
      .find((b) => b.text() === '误报')
    expect(falsePositive, '只读视图里仍应能看到(置灰的)误报按钮').toBeTruthy()
    expect(falsePositive!.attributes('disabled')).toBeDefined()
    await falsePositive!.trigger('click')
    expect(markIssue).not.toHaveBeenCalled()
    expect(wrapper.emitted('mark')).toBeUndefined()
  })

  it('非只读端到端：点"误报"外抛标记事件（写库由页面负责）', async () => {
    const wrapper = mount(ReviewResult, {
      props: {
        record: makeRecord({ status: 4, resultJson: JSON.stringify(diffResult) }),
        projectId: 'p1'
      },
      ...options
    })
    const falsePositive = wrapper
      .findAll('a-button-stub')
      .find((b) => b.text() === '误报')
    await falsePositive!.trigger('click')
    expect(wrapper.emitted('mark')?.[0]).toEqual(['A.java', 0, 1])
  })

  it('非只读态照旧把 mark 事件透出去', async () => {
    const wrapper = mount(ReviewResult, {
      props: { record: diffRecord(), projectId: 'p1' },
      ...options
    })
    await wrapper.findComponent({ name: 'DiffReviewResult' }).vm.$emit('mark', 'A.java', 0, 2)
    expect(wrapper.emitted('mark')?.[0]).toEqual(['A.java', 0, 2])
  })

  it('非只读态把 retry 事件透出去', async () => {
    const wrapper = mount(ReviewResult, {
      props: { record: makeRecord({ status: 3 }), projectId: 'p1' },
      ...options
    })
    const retryBtn = wrapper.findAll('a-button-stub').find((b) => b.text().includes('重审失败单元'))
    expect(retryBtn).toBeTruthy()
    await retryBtn!.trigger('click')
    expect(wrapper.emitted('retry')).toBeTruthy()
  })

  it('resultJson 坏掉时不抛错（退化为空结果）', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: makeRecord({ resultJson: '{坏' }), projectId: 'p1' },
      ...options
    })
    expect(wrapper.find('.review-result').exists()).toBe(true)
    expect(wrapper.find('.placeholder').exists()).toBe(false)
  })

  it('已完成审查展示耗时（支撑指标 8 的"记录完整耗时"）', () => {
    const wrapper = mount(ReviewResult, {
      props: {
        record: makeRecord({
          status: 2,
          startedAt: '2026-09-10 08:00:00',
          finishedAt: '2026-09-10 08:12:34'
        }),
        projectId: 'p1'
      },
      ...options
    })
    expect(wrapper.text()).toContain('耗时 12 分 34 秒')
  })

  it('缺时间戳时不显示一个没有信息量的"耗时 —"', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: makeRecord({ status: 2 }), projectId: 'p1' },
      ...options
    })
    expect(wrapper.find('.duration').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('耗时')
  })

  it('执行中的记录显示已耗时', () => {
    const wrapper = mount(ReviewResult, {
      props: {
        record: makeRecord({ status: 1, progress: 40, startedAt: '2026-09-10 08:00:00' }),
        projectId: 'p1'
      },
      ...options
    })
    expect(wrapper.text()).toContain('已耗时')
    // 执行中不该出现"耗时 X"这种看似已完成的文案
    expect(wrapper.find('.duration').exists()).toBe(false)
  })

  it('轮询停止时给出「继续等待」入口，点了把事件抛出去', async () => {
    const wrapper = mount(ReviewResult, {
      props: {
        record: makeRecord({ status: 1, progress: 40 }),
        projectId: 'p1',
        pollError: '请求超时，请稍后重试'
      },
      ...options
    })

    // 文案在 a-alert 的 message 属性上（stub 渲染成元素属性）
    const alert = wrapper.find('a-alert-stub')
    expect(alert.attributes('message')).toContain('进度获取失败')
    expect(alert.attributes('message')).toContain('请求超时')

    const resume = wrapper.findAll('a-button-stub').find((b) => b.text() === '继续等待')!
    expect(resume).toBeTruthy()
    await resume.trigger('click')
    expect(wrapper.emitted('resume-poll')).toBeTruthy()
  })

  it('轮询正常时不显示恢复入口', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: makeRecord({ status: 1, progress: 40 }), projectId: 'p1' },
      ...options
    })

    expect(wrapper.findAll('a-button-stub').some((b) => b.text() === '继续等待')).toBe(false)
  })

  it('执行中的记录显示进度而不是结果', () => {
    const wrapper = mount(ReviewResult, {
      props: { record: makeRecord({ status: 1, progress: 40 }), projectId: 'p1' },
      ...options
    })
    expect(wrapper.text()).toContain('审查执行中…')
    expect(wrapper.text()).not.toContain('重审失败单元')
  })

  it('按 resultJson 分派到对应结果组件', () => {
    const cases: Array<[string, string]> = [
      [JSON.stringify({ units: [], commit: { sha: 'a' } }), 'DiffReviewResult'],
      [JSON.stringify({ nodes: [], edges: [] }), 'CouplingResult'],
      [JSON.stringify({ patterns: [] }), 'PatternResult'],
      [JSON.stringify({ raw: '文本' }), 'RawResult']
    ]
    for (const [json, component] of cases) {
      const wrapper = mount(ReviewResult, {
        props: { record: makeRecord({ resultJson: json }), projectId: 'p1' },
        ...options
      })
      expect(wrapper.findComponent({ name: component }).exists()).toBe(true)
    }
  })
})
