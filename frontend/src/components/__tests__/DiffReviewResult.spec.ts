// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DiffReviewResult from '@/components/DiffReviewResult.vue'
import { antStubs } from '@/testUtils/antStubs'
import { MARK_ACCEPTED, type IssueMark } from '@/api/review'

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
