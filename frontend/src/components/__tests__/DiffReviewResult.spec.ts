// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import DiffReviewResult from '@/components/DiffReviewResult.vue'
import { antStubs } from '@/testUtils/antStubs'
import { MARK_ACCEPTED, type IssueMark } from '@/api/review'

vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return { ...actual, getFilePatch: vi.fn() }
})

import { getFilePatch } from '@/api/project'

/**
 * diff 结果的行内评论里带"误报/已采纳/撤销"三颗写库按钮。
 * 查看历史记录时必须置灰 —— 这是"仅做查看"最容易被漏掉的一处（它藏在插槽里）。
 */
const result = {
  commit: { sha: 'abcdef1234567890', baseSha: '1234567890abcdef', files: 1 },
  units: [
    {
      path: 'A.java',
      status: 'success',
      changeType: 'modified',
      unit: { name: 'foo', kind: 'method', lines: '1-10' },
      issues: [
        { severity: 'MAJOR', title: '空指针风险', newLine: 5, description: '可能为 null' },
        { severity: 'MINOR', title: '命名不规范' }
      ]
    }
  ]
}

// 与浏览器一致：disabled 元素不派发点击事件，这样 trigger('click') 才等价于真人点击
const options = {
  global: {
    config: { triggerEvent: { disabled: false } } as any,
    stubs: {
      ...antStubs,
      // 真实 DiffViewer 经 buildExtendData 把评论转成 `{issue, issueIndex}` 再喂给 extend 插槽，
      // stub 必须还原这一步，否则测的就不是真实渲染路径
      DiffViewer: {
        name: 'DiffViewer',
        // 用**对象形式**声明：数组形式的 prop 没有类型，裸属性 `highlight` 会拿到空字符串而不是 true
        props: {
          comments: { type: Array, default: () => [] },
          highlight: { type: Boolean, default: false }
        },
        computed: {
          extendItems(this: any) {
            return ((this.comments as any[]) || []).map((c: any) => c.data)
          }
        },
        template: '<div class="diff-viewer-stub"><slot name="extend" :items="extendItems" /></div>'
      }
    }
  }
}

function mountResult(props: Record<string, unknown> = {}) {
  return mount(DiffReviewResult, {
    props: {
      projectId: 'p1',
      commitSha: 'abcdef1234567890',
      recordId: '1001',
      result,
      marks: [] as IssueMark[],
      ...props
    },
    ...options
  })
}

function buttonByText(wrapper: ReturnType<typeof mountResult>, text: string) {
  return wrapper.findAll('a-button-stub').find((b) => b.text() === text)
}

describe('DiffReviewResult 行内标记的只读降级', () => {
  it('审查结果页的 diff 也要开语法高亮（与新弹窗一致）', () => {
    const wrapper = mountResult()

    const viewer = wrapper.findComponent({ name: 'DiffViewer' })
    expect(viewer.props('highlight'), '两处观感不能一个高亮一个不高亮').toBe(true)
  })

  it('非只读：误报/已采纳可点，且不透传 disabled', () => {
    const wrapper = mountResult()
    expect(buttonByText(wrapper, '误报')!.attributes('disabled')).toBeUndefined()
    expect(buttonByText(wrapper, '已采纳')!.attributes('disabled')).toBeUndefined()
  })

  it('只读：已标记的 issue 也不给"撤销"入口', () => {
    const marks: IssueMark[] = [
      { id: 'm1', recordId: '1001', unitPath: 'A.java', issueIndex: 0, markValue: MARK_ACCEPTED }
    ]
    expect(buttonByText(mountResult({ marks }), '撤销')).toBeTruthy()
    expect(buttonByText(mountResult({ marks, readonly: true }), '撤销')).toBeUndefined()
  })

  it('只读：误报/已采纳全部置灰（浏览器不会给 disabled 元素派发点击）', () => {
    const wrapper = mountResult({ readonly: true })
    expect(buttonByText(wrapper, '误报')!.attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, '已采纳')!.attributes('disabled')).toBeDefined()
  })

  it('非只读：点击误报抛出标记事件', async () => {
    const wrapper = mountResult()
    await buttonByText(wrapper, '误报')!.trigger('click')
    expect(wrapper.emitted('mark')?.[0]).toEqual(['A.java', 0, 1])
  })
})

/**
 * C4：英文枚举保留（与后端/日志逐字对应），中文放 tooltip。
 * tooltip 的 title 在 stub 上是元素属性，可以直接断言。
 */
describe('DiffReviewResult 英文枚举的中文提示', () => {
  it('问题级别：显示 MAJOR，tooltip 给出"重要"', () => {
    const wrapper = mountResult()

    const titles = wrapper.findAll('a-tooltip-stub').map((t) => t.attributes('title'))
    expect(wrapper.text()).toContain('MAJOR')
    expect(titles).toContain('MAJOR · 重要')
  })

  it('变更类型：显示 modified，tooltip 给出"修改"', () => {
    const wrapper = mountResult()

    const titles = wrapper.findAll('a-tooltip-stub').map((t) => t.attributes('title'))
    expect(wrapper.text()).toContain('modified')
    expect(titles).toContain('modified · 修改')
  })
})

/**
 * C12：同名问题不应产生重复行 key。
 *
 * 这里必须用**真实表格**：只有真渲染才会输出 `data-row-key`，
 * stub 掉的表格读不到 key，断言会变成"什么都没测到"。
 */
describe('DiffReviewResult 问题行的 key', () => {
  const realTableOptions = {
    global: {
      stubs: {
        ...antStubs,
        'a-table': false,
        'a-table-column': false,
        DiffViewer: {
          name: 'DiffViewer',
          props: ['comments'],
          computed: {
            extendItems(this: any) {
              return ((this.comments as any[]) || []).map((c: any) => c.data)
            }
          },
          template: '<div class="diff-viewer-stub"><slot name="extend" :items="extendItems" /></div>'
        }
      }
    }
  }

  it('两条同名且都无法定位行号的问题，行 key 仍然唯一', async () => {
    const duplicated = {
      commit: { sha: 'abcdef1' },
      units: [
        {
          path: 'A.java',
          status: 'success',
          unit: { name: 'foo', kind: 'method', lines: '1-10' },
          issues: [
            { severity: 'MINOR', title: '命名不规范' },
            { severity: 'MINOR', title: '命名不规范' }
          ]
        }
      ]
    }

    const wrapper = mount(DiffReviewResult, {
      props: {
        projectId: 'p1',
        commitSha: 'abcdef1234567890',
        recordId: '1001',
        result: duplicated,
        marks: []
      },
      ...realTableOptions
    })
    await wrapper.vm.$nextTick()

    const keys = wrapper.findAll('tr[data-row-key]').map((tr) => tr.attributes('data-row-key'))
    expect(keys.length, '两条问题都应渲染成行').toBe(2)
    expect(new Set(keys).size, '行 key 不能重复（否则控制台刷告警）').toBe(2)
  })
})

// ---------------- patch 拉取失败（backlog ⑥：错误被伪装成"该文件无可用 diff"，且无法重试） ----------------

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DiffReviewResult 的 patch 拉取失败', () => {
  it('失败时给出错误与重试入口，而不是谎称"无可用 diff"', async () => {
    ;(getFilePatch as any).mockRejectedValueOnce(new Error('接口 500'))
    const wrapper = mountResult()
    await flush()
    await nextTick()

    expect(wrapper.find('a-alert-stub').attributes('message')).toContain('接口 500')
    expect(wrapper.find('.diff-viewer-stub').exists(), '不该再渲染 DiffViewer 的空态').toBe(false)
  })

  it('点重试会重新拉取，成功后渲染 diff（失败不再被当成"已加载"）', async () => {
    ;(getFilePatch as any)
      .mockRejectedValueOnce(new Error('接口 500'))
      .mockResolvedValueOnce('@@ -1 +1 @@\n-a\n+b')
    const wrapper = mountResult()
    await flush()
    await nextTick()

    const retry = wrapper.findAll('a-button-stub').find((b) => b.text() === '重试')
    expect(retry, '失败后必须给重试入口').toBeTruthy()
    await retry!.trigger('click')
    await flush()
    await nextTick()

    expect(getFilePatch).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.diff-viewer-stub').exists()).toBe(true)
  })

  it('真正没有可用 diff（返回 null）时仍走 DiffViewer 的空态，不报错', async () => {
    ;(getFilePatch as any).mockResolvedValueOnce(null)
    const wrapper = mountResult()
    await flush()
    await nextTick()

    expect(wrapper.find('.diff-viewer-stub').exists()).toBe(true)
    expect(wrapper.find('a-alert-stub').exists()).toBe(false)
  })
})
