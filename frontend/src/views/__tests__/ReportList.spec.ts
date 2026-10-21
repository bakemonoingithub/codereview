// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportList from '@/views/ReportList.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({ reportsApi: { list: vi.fn() } }))

import { reportsApi } from '@/api/v2'

const summary = {
  id: 'report-1', title: '订单报告', state: 'SUCCEEDED', errorCode: null, errorMessage: null,
  modelName: '默认', sourceCount: 2, taskCount: 2, versionCount: 1, kindCounts: { ISSUE: 1, PATTERN: 1 },
  createdAt: '2026-10-21T10:00:00', updatedAt: '2026-10-21T10:05:00',
  startedAt: '2026-10-21T10:01:00', finishedAt: '2026-10-21T10:05:00'
}

const stubs = {
  ...namedAntStubs,
  'a-table': { name: 'ATable', template: '<div class="table-stub" />' },
  'a-table-column': { name: 'ATableColumn', template: '<div />' },
  'a-card': silentStub('a-card-stub')
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(reportsApi.list).mockResolvedValue({ items: [summary], nextCursor: null, hasMore: false })
})

describe('报告列表', () => {
  it('刷新使旧分页失效，后续仍可加载更多', async () => {
    const wrapper = mount(ReportList, { global: { stubs } })
    await flushPromises()
    const s = (wrapper.vm as any).$.setupState
    s.nextCursor = 'old'
    let resolve!: (value: any) => void
    vi.mocked(reportsApi.list).mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    const pending = s.loadMore()
    vi.mocked(reportsApi.list).mockResolvedValueOnce({ items: [summary], nextCursor: 'new', hasMore: true })
    await s.load()
    resolve({ items: [{ ...summary, id: 'stale' }], nextCursor: null, hasMore: false })
    await pending
    expect(s.loadingMore).toBe(false)
    expect(s.reports).toHaveLength(1)
    await s.loadMore()
    expect(reportsApi.list).toHaveBeenLastCalledWith({ cursor: 'new', limit: 20 })
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
})
