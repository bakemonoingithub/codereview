// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReviewCenter from '@/views/ReviewCenter.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/v2', () => ({ projectsApi: { list: vi.fn() }, tasksApi: { list: vi.fn() } }))
import { projectsApi, tasksApi } from '@/api/v2'
function deferred<T>() { let resolve!: (v: T) => void
  const promise = new Promise<T>((yes) => { resolve = yes }); return { promise, resolve }
}
const page = (id: string, cursor: string | null = null) => ({ items: [{ id }], nextCursor: cursor, hasMore: !!cursor })
const state = (w: any) => w.vm.$.setupState as any
async function start() { const w = mount(ReviewCenter, { global: { stubs: {
  ...namedAntStubs, 'a-table-column': { template: '<span />' }
} } }); await flushPromises(); return w }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(projectsApi.list).mockResolvedValue({ items: [] } as any)
  vi.mocked(tasksApi.list).mockResolvedValue(page('first', 'cursor-2') as any) })
describe('审查中心列表', () => {
  it('后续页沿用服务端游标并追加，筛选后从第一页重载', async () => {
    const w = await start(); const s = state(w)
    vi.mocked(tasksApi.list).mockResolvedValue(page('second') as any); await s.loadMore()
    expect(vi.mocked(tasksApi.list).mock.calls[1][0]?.cursor).toBe('cursor-2')
    expect(s.tasks.map((x: any) => x.id)).toEqual(['first', 'second'])
    s.filterType = 'ISSUE'; await s.load()
    expect(vi.mocked(tasksApi.list).mock.calls[2][0]?.cursor).toBeUndefined()
    expect(s.tasks.map((x: any) => x.id)).toEqual(['second']); w.unmount()
  })
  it('旧筛选迟到响应不能覆盖新筛选列表', async () => {
    const w = await start(); const s = state(w); const old = deferred<any>(); const latest = deferred<any>()
    vi.mocked(tasksApi.list).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    s.filterType = 'ISSUE'; const first = s.load(); s.filterType = 'PATTERN'; const second = s.load()
    latest.resolve(page('latest')); await second; old.resolve(page('old')); await first
    expect(s.tasks[0].id).toBe('latest'); w.unmount()
  })
})
