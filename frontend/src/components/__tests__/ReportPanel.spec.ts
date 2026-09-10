// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ReportPanel from '@/components/ReportPanel.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

// 全部走 mock：这些接口在真实环境会打 axios（happy-dom 下会连 127.0.0.1:3000 并抛 ECONNREFUSED）
vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return { ...actual, listReviews: vi.fn(), getReview: vi.fn(), listMarks: vi.fn().mockResolvedValue([]) }
})
vi.mock('@/api/model', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/model')>()
  return { ...actual, listModels: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/prompt', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/prompt')>()
  return { ...actual, listPrompts: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return { ...actual, listStrategies: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/report', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/report')>()
  return {
    ...actual,
    listReports: vi.fn().mockResolvedValue({ records: [] }),
    generateReport: vi.fn(),
    getReport: vi.fn()
  }
})

/**
 * ReportPanel 的行为契约：请求参数、分页配置、跨页选中、查看入口。
 *
 * 表格用**真实 antd Table**（只 stub 其余组件），这样分页器与勾选框可以像用户那样操作；
 * 用 stub 表格时 `findComponent(...).props('pagination')` 取不到值（functional stub 无实例），
 * 断言会以"读 undefined"的形式假失败 —— 那是在测 stub，不是在测组件。
 */
const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-table': false,
      'a-table-column': false,
      'a-modal': false,
      'a-row': false,
      'a-col': false,
      'a-card': false,
      'a-form': false,
      'a-form-item': false,
      ReviewRecordViewer: true
    }
  }
}

function row(index: number, status = 2) {
  return {
    id: String(1000 + index),
    projectId: '9',
    strategyId: '5',
    strategyName: '变更审查（diff）',
    branch: 'master',
    commitSha: 'abcdef1234567890',
    status,
    progress: 100,
    createdAt: '2026-09-10 08:00:00'
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('ReportPanel 选择审查记录', () => {
  it('首次按默认每页 20 条请求，并把状态过滤下推给服务端', async () => {
    const { listReviews } = await import('@/api/review')
    const { wrapper } = await mountPanel()
    expect(listReviews).toHaveBeenCalledWith('9', { pageNum: 1, pageSize: 20, statusMin: 2 })
    wrapper.unmount()
  })

  it('渲染出 20 行记录，分页器显示总数与每页条数选项', async () => {
    const { wrapper } = await mountPanel()
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(20)
    expect(wrapper.find('.ant-pagination').exists()).toBe(true)
    expect(wrapper.text()).toContain('共 25 条')
    expect(wrapper.find('.ant-pagination-options').exists()).toBe(true)
    wrapper.unmount()
  })

  it('18 条数据时渲染出第 2 页', async () => {
    const { wrapper } = await mountPanel(18)
    const labels = wrapper.findAll('.ant-pagination-item').map((i) => i.text())
    expect(labels).toEqual(['1'])
    wrapper.unmount()
  })

  it('点第 2 页会按 pageNum=2 重新问服务端要数据', async () => {
    const { wrapper, listReviews, mockPage } = await mountPanel(25)
    const page2 = wrapper.findAll('.ant-pagination-item').find((i) => i.text() === '2')!
    mockPage(2, 20, 25)
    await page2.trigger('click')
    await flush()

    expect(listReviews).toHaveBeenLastCalledWith('9', { pageNum: 2, pageSize: 20, statusMin: 2 })
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(5)
    wrapper.unmount()
  })

  it('勾选后显示"已选 N 条"，翻页后已选记录仍在', async () => {
    const { wrapper, mockPage } = await mountPanel(25)
    // 勾第 1、2 行（真实复选框）
    const boxes = wrapper.findAll('.ant-table-tbody input[type="checkbox"]')
    await boxes[0].setValue(true)
    await boxes[1].setValue(true)
    await flush()
    expect(wrapper.text()).toContain('已选 2 条')

    const page2 = wrapper.findAll('.ant-pagination-item').find((i) => i.text() === '2')!
    mockPage(2, 20, 25)
    await page2.trigger('click')
    await flush()

    // 跨页保留：翻页不会把已选记录清掉
    expect(wrapper.text()).toContain('已选 2 条')
    wrapper.unmount()
  })

  it('清空按钮清掉选中集并隐藏自己', async () => {
    const { wrapper } = await mountPanel(25)
    await wrapper.findAll('.ant-table-tbody input[type="checkbox"]')[0].setValue(true)
    await flush()

    const clear = wrapper.findAll('a-button-stub').find((b) => b.text() === '清空')
    expect(clear, '选中后应出现"清空"按钮').toBeTruthy()
    await clear!.trigger('click')
    await flush()

    expect(wrapper.text()).toContain('已选 0 条')
    expect(wrapper.findAll('a-button-stub').find((b) => b.text() === '清空')).toBeFalsy()
    wrapper.unmount()
  })

  it('策略列显示后端 join 的策略名', async () => {
    const { wrapper } = await mountPanel(25)
    expect(wrapper.text()).toContain('变更审查（diff）')
    wrapper.unmount()
  })

  it('点某行"查看"把该记录交给只读弹窗', async () => {
    const { wrapper } = await mountPanel(25)
    const viewBtn = wrapper.findAll('a-button-stub').find((b) => b.text() === '查看')!
    await viewBtn.trigger('click')
    await flush()

    const viewer = wrapper.findComponent({ name: 'ReviewRecordViewer' })
    expect(viewer.props('open')).toBe(true)
    expect(viewer.props('recordId')).toBe('1000')
    expect((viewer.props('row') as any).id).toBe('1000')
    wrapper.unmount()
  })
})

/** 挂载 + 按需 mock：这里才 import api，确保 mock 已就绪 */
async function mountPanel(total = 25) {
  const { listReviews } = await import('@/api/review')
  const { listModels } = await import('@/api/model')
  const { listPrompts } = await import('@/api/prompt')
  const { listStrategies } = await import('@/api/strategy')
  const { listReports } = await import('@/api/report')

  ;(listModels as any).mockResolvedValue({ records: [] })
  ;(listPrompts as any).mockResolvedValue({ records: [] })
  ;(listStrategies as any).mockResolvedValue({ records: [{ id: '5', name: '变更审查（diff）' }] })
  ;(listReports as any).mockResolvedValue({ records: [] })

  function mockPage(pageNum: number, size: number, totalCount: number) {
    const start = (pageNum - 1) * size
    const count = Math.max(0, Math.min(size, totalCount - start))
    ;(listReviews as any).mockResolvedValue({
      records: Array.from({ length: count }, (_, i) => row(start + i)),
      total: totalCount,
      current: pageNum,
      size
    })
  }

  mockPage(1, 20, total)
  const wrapper = mount(ReportPanel, { props: { projectId: '9' }, ...options })
  await flush()
  await wrapper.vm.$nextTick()
  return { wrapper, listReviews: listReviews as any, mockPage }
}
