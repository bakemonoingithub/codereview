// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportList from '@/views/ReportList.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({ reportsApi: { list: vi.fn(), delete: vi.fn() } }))

import { reportsApi } from '@/api/v2'

const summary = {
  id: 'report-1', title: '订单报告', state: 'SUCCEEDED', errorCode: null, errorMessage: null,
  modelName: '默认', sourceCount: 2, taskCount: 2, versionCount: 1, kindCounts: { ISSUE: 1, PATTERN: 1 },
  createdAt: '2026-10-21T10:00:00', updatedAt: '2026-10-21T10:05:00',
  startedAt: '2026-10-21T10:01:00', finishedAt: '2026-10-21T10:05:00'
}

const stubs = {
  ...namedAntStubs,
  'a-input-search': silentStub('a-input-search-stub'),
  'a-table': { name: 'ATable', template: '<div class="table-stub" />' },
  'a-table-column': { name: 'ATableColumn', template: '<div />' },
  'a-card': silentStub('a-card-stub')
}

beforeEach(() => {
  sessionStorage.clear()
  vi.resetAllMocks()
  vi.mocked(reportsApi.list).mockResolvedValue({ items: [summary], nextCursor: null, hasMore: false })
})

describe('报告列表', () => {
  it('刷新使旧翻页请求失效，后续仍可切换页码', async () => {
    const wrapper = mount(ReportList, { global: { stubs } })
    await flushPromises()
    const s = (wrapper.vm as any).$.setupState
    let resolve!: (value: any) => void
    vi.mocked(reportsApi.list).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const pending = s.pagination.onChange(2, 20)
    vi.mocked(reportsApi.list).mockResolvedValueOnce({ items: [summary], nextCursor: null, hasMore: true, total: 60, pageNum: 1, pageSize: 20 })
    await s.refresh()
    resolve({ items: [{ ...summary, id: 'stale' }], total: 60, pageNum: 2, pageSize: 20 })
    await pending
    expect(s.loading).toBe(false)
    expect(s.reports[0].id).toBe('report-1')
    await s.pagination.onChange(2, 20)
    expect(reportsApi.list).toHaveBeenLastCalledWith({ pageNum: 2, pageSize: 20 })
    wrapper.unmount()
  })
  it('展示来源、类型与状态，并可进入报告阅读', async () => {
    const wrapper = mount(ReportList, { global: { stubs } })
    await flushPromises()

    const s = (wrapper.vm as any).$.setupState
    expect(s.reports[0].title).toBe('订单报告')
    expect(s.reports[0].sourceCount).toBe(2)
    s.open('report-1')
    expect(context.push).toHaveBeenCalledWith('/reports/report-1')
    wrapper.unmount()
  })

  it('按报告名称或 ID 搜索，并从第一页读取匹配结果', async () => {
    const wrapper = mount(ReportList, { global: { stubs } })
    await flushPromises()

    const s = (wrapper.vm as any).$.setupState
    s.keyword = '订单'
    s.stateFilter = 'FAILED'
    await s.searchReports()

    expect(reportsApi.list).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 20, q: '订单', state: 'FAILED' })
    wrapper.unmount()
  })
  it('删除成功后刷新列表，空末页回退一页', async () => {
    const w = mount(ReportList, { global: { stubs } }); await flushPromises()
    const s = (w.vm as any).$.setupState
    const record = { id: 'delete-me', name: '待删审查', title: '待删报告', state: 'SUCCEEDED', revision: 7 }
    s.askDelete(record)
    s.pageNum = 2
    vi.mocked(reportsApi.delete).mockResolvedValue(undefined)
    vi.mocked(reportsApi.list).mockResolvedValueOnce({ items: [], pageNum: 2, pageSize: 20, total: 20 } as any)
      .mockResolvedValueOnce({ items: [{ id: 'previous' }], pageNum: 1, pageSize: 20, total: 20 } as any)
    await s.confirmDelete()
    expect(reportsApi.delete).toHaveBeenCalledWith('delete-me', 7)
    expect(reportsApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 1 }))
    expect(s.deleteTarget).toBeNull()
    w.unmount()
  })

  it('删除失败保留弹窗、记录及错误，允许重试', async () => {
    const w = mount(ReportList, { global: { stubs } }); await flushPromises()
    const s = (w.vm as any).$.setupState
    const record = { id: 'delete-me', state: 'SUCCEEDED', revision: 4 }
    s.askDelete(record)
    vi.mocked(reportsApi.list).mockClear()
    vi.mocked(reportsApi.delete).mockRejectedValueOnce(new Error('记录已变更，请刷新'))
    await s.confirmDelete()
    expect(s.deleteTarget.id).toBe('delete-me')
    expect(s.deleteError).toBe('记录已变更，请刷新')
    expect(reportsApi.list).not.toHaveBeenCalled()
    expect(s.deleting).toBe(false)
    w.unmount()
  })

  it('运行中记录不能打开删除弹窗，缺版本时不发送删除请求', async () => {
    const w = mount(ReportList, { global: { stubs } }); await flushPromises()
    const s = (w.vm as any).$.setupState
    s.askDelete({ id: 'running', state: 'RUNNING', revision: 1 })
    expect(s.deleteTarget).toBeNull()
    s.askDelete({ id: 'old', state: 'SUCCEEDED' })
    await s.confirmDelete()
    expect(reportsApi.delete).not.toHaveBeenCalled()
    expect(s.deleteError).toContain('版本缺失')
    w.unmount()
  })

})
