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
beforeEach(() => { sessionStorage.clear(); vi.resetAllMocks(); vi.mocked(projectsApi.list).mockResolvedValue({ items: [] } as any)
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
  it('从详情返回后恢复已加载页数', async () => {
    const w = await start(); const s = state(w)
    vi.mocked(tasksApi.list).mockResolvedValueOnce(page('second') as any); await s.loadMore(); w.unmount()
    vi.mocked(tasksApi.list).mockResolvedValueOnce(page('first', 'cursor-2') as any).mockResolvedValueOnce(page('second') as any)
    const restored = await start(); expect(state(restored).tasks.map((row: any) => row.id)).toEqual(['first', 'second']); restored.unmount()
  })
  it('sessionStorage 不可用时仍然查询列表', async () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('不可用') })
    const w = await start(); expect(state(w).tasks[0].id).toBe('first'); w.unmount(); spy.mockRestore()
  })

  it('列表失败时显示带图标的错误条，重试是按钮而不是文字链接', async () => {
    vi.mocked(tasksApi.list).mockRejectedValueOnce(new Error('服务器内部错误'))
    const w = await start()
    const alert = w.find('.page-notices .load-error')
    expect(alert.exists()).toBe(true)
    expect(alert.attributes('type')).toBe('error')
    expect(alert.text()).toContain('服务器内部错误')
    expect(w.findAll('a-button-stub').some((b) => b.text() === '重试')).toBe(true)
    w.unmount()
  })

})
