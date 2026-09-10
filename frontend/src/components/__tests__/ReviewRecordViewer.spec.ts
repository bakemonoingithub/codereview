// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return { ...actual, getReview: vi.fn(), listMarks: vi.fn() }
})

import ReviewRecordViewer from '@/components/ReviewRecordViewer.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
import { getReview, listMarks } from '@/api/review'

/**
 * 查看弹窗的取数职责已从调用方移进组件：调用方只给 id，组件自己拉完整记录 + 标记。
 * 这里守住四条：按 id 取数、策略名用列表行兜底、失败有明确提示、切换 id 不显示上一条的残留。
 */
const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      ReviewConfigSnapshot: true
    }
  }
}

const fullRecord = {
  id: '1001',
  projectId: '9',
  strategyId: '5',
  branch: 'master',
  commitSha: 'abcdef1234567890',
  status: 2,
  progress: 100,
  createdAt: '2026-09-10 08:00:00',
  resultJson: JSON.stringify({ units: [] })
}

const listRow = {
  id: '1001',
  projectId: '9',
  strategyId: '5',
  strategyName: '变更审查（diff）',
  branch: 'master',
  commitSha: 'abcdef1234567890',
  status: 2,
  progress: 100,
  createdAt: '2026-09-10 08:00:00'
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function mountViewer(props: Record<string, unknown>) {
  return mount(ReviewRecordViewer, {
    props: { open: true, recordId: '1001', projectId: '9', ...props },
    ...options
  })
}

describe('ReviewRecordViewer 取数', () => {
  it('按 recordId 拉完整记录与标记，并把策略名从列表行带进去', async () => {
    ;(getReview as any).mockResolvedValue(fullRecord)
    ;(listMarks as any).mockResolvedValue([{ id: 'm1', unitPath: 'A.java', issueIndex: 0, markValue: 1 }])

    const wrapper = mountViewer({ row: listRow })
    await flush()

    expect(getReview).toHaveBeenCalledWith('1001')
    expect(listMarks).toHaveBeenCalledWith('1001')

    const snapshot = wrapper.findComponent({ name: 'ReviewConfigSnapshot' })
    expect(snapshot.props('record')).toMatchObject({
      id: '1001',
      // 详情接口不返回 strategyName，必须由列表行兜底，否则弹窗里策略名是空的
      strategyName: '变更审查（diff）',
      resultJson: fullRecord.resultJson
    })
    expect(snapshot.props('marks')).toHaveLength(1)
    expect(snapshot.props('marksLoading')).toBe(false)
  })

  it('详情拉取失败时给出明确提示，而不是一屏空白', async () => {
    ;(getReview as any).mockRejectedValue(new Error('boom'))
    ;(listMarks as any).mockResolvedValue([])

    const wrapper = mountViewer({ row: listRow })
    await flush()

    expect(wrapper.findComponent({ name: 'ReviewConfigSnapshot' }).exists()).toBe(false)
    // 弹窗内容渲染在 body 上、测试里 a-modal 被 stub，插槽不渲染，故断言组件状态
    expect((wrapper.vm as any).error).toBe(true)
    expect((wrapper.vm as any).loading).toBe(false)
  })

  it('未选中任何记录时不发请求', async () => {
    ;(getReview as any).mockClear()
    mountViewer({ recordId: '' })
    await flush()

    expect(getReview).not.toHaveBeenCalled()
  })

  it('切换到另一条记录时先清空，不显示上一条的残留', async () => {
    ;(getReview as any).mockResolvedValue(fullRecord)
    ;(listMarks as any).mockResolvedValue([])
    const wrapper = mountViewer({ row: listRow })
    await flush()
    expect(wrapper.findComponent({ name: 'ReviewConfigSnapshot' }).exists()).toBe(true)

    // 换 id 且让请求悬挂：此时必须回到"无数据 + 加载中"，而不是继续显示 1001 的内容
    ;(getReview as any).mockImplementation(() => new Promise(() => {}))
    await wrapper.setProps({ recordId: '1002' })
    await wrapper.vm.$nextTick()

    expect(wrapper.findComponent({ name: 'ReviewConfigSnapshot' }).exists()).toBe(false)
    expect((wrapper.vm as any).record).toBeNull()
    expect((wrapper.vm as any).loading).toBe(true)
  })
})
