// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import TaskDetail from '@/views/TaskDetail.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
const context = vi.hoisted(() => ({ route: {} as any, push: vi.fn() }))
// 组件用离开守卫保护未保存反馈；测试里只关心它们能被注册，不执行真实导航。
vi.mock('vue-router', () => ({
  useRoute: () => context.route,
  useRouter: () => ({ push: context.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@/api/v2', () => ({ projectsApi: { detail: vi.fn() }, tasksApi: {
  detail: vi.fn(), statistics: vi.fn(), result: vi.fn(), materials: vi.fn(), calls: vi.fn(), cancel: vi.fn()
} }))
import { projectsApi, tasksApi } from '@/api/v2'
function deferred<T>() { let resolve!: (v: T) => void
  const promise = new Promise<T>(yes => { resolve = yes }); return { promise, resolve }
}
const task = (id = 't1', status = 'SUCCEEDED') => ({ id, projectId: 'p1', state: status, specialistType: 'ISSUE',
  sourceKind: 'FILES', headSha: 'fixed', revision: 1, targets: [{ id: 'a', path: 'A.java', state: status }, { id: 'b', path: 'B.java', state: status }] })
const result = (id = 'a') => ({ id: 'r-' + id, targetId: id, body: { summary: id, issues: [] }, issues: [], provenance: {} })
const page = (id: string, more = false) => ({ items: [{ id }], nextCursor: more ? 'cursor-' + id : null, hasMore: more })
const statistics = (type = 'ISSUE', overrides: Record<string, unknown> = {}) => ({
  taskId: 't1', specialistType: type,
  issues: { total: 2, accurate: 1, inaccurate: 1, unknown: 0, unassessed: 0, accuracyPercent: 50 },
  results: { total: 0, accurate: 0, inaccurate: 0, unknown: 0, unassessed: 0, accuracyPercent: null },
  ...overrides
})
const state = (w: any) => w.vm.$.setupState as any
async function start() { const w = mount(TaskDetail, { global: { stubs: { ...namedAntStubs,
  SpecialistResultView: { props: ['result'], template: '<div>{{ result.id }}</div>' },
  MaterialReaderModal: true, CallReaderModal: true, 'a-table-column': { template: '<span />' }
} } }); await flushPromises(); return w }
beforeEach(() => {
  vi.useRealTimers(); vi.resetAllMocks(); sessionStorage.clear()
  context.route = reactive({ params: { id: 't1' }, query: {} })
  vi.mocked(tasksApi.detail).mockImplementation(async id => task(id) as any)
  vi.mocked(projectsApi.detail).mockResolvedValue({ name: '项目' } as any)
  vi.mocked(tasksApi.result).mockImplementation(async (_id, target) => result(target) as any)
  vi.mocked(tasksApi.statistics).mockResolvedValue(statistics() as any)
  vi.mocked(tasksApi.materials).mockResolvedValue([])
  vi.mocked(tasksApi.calls).mockImplementation(async (_id, params) => page('c-' + params?.targetId) as any)
})
describe('任务详情的轮询与目标隔离', () => {
  it('旧目标的更多调用不能追加到新目标', async () => {
    const w = await start(); const s = state(w); s.callsHasMore = true; s.callsCursor = 'cursor-a'
    const pending = deferred<any>(); vi.mocked(tasksApi.calls).mockReturnValueOnce(pending.promise)
    const more = s.loadMoreCalls(); await s.selectTarget('b')
    pending.resolve(page('late-a')); await more
    expect(s.calls.map((call: any) => call.id)).toEqual(['c-b']); expect(s.result.targetId).toBe('b'); w.unmount()
  })
  it('同目标刷新失败时保留最近成功结果和调用列表', async () => {
    const w = await start(); const s = state(w)
    vi.mocked(tasksApi.result).mockRejectedValueOnce(new Error('临时失败'))
    vi.mocked(tasksApi.calls).mockRejectedValueOnce(new Error('调用失败'))
    await s.loadTask(); expect(s.result.id).toBe('r-a'); expect(s.calls[0].id).toBe('c-a')
    expect(s.resultError).toBe('临时失败'); w.unmount()
  })
  it('切换路由参数后旧任务响应不回填', async () => {
    const w = await start(); const pending = deferred<any>()
    vi.mocked(tasksApi.detail).mockReturnValueOnce(pending.promise)
    state(w).activeTab = 'calls'
    const old = state(w).loadTask(); context.route.params.id = 't2'; await flushPromises()
    pending.resolve(task('t1')); await old; expect(state(w).task.id).toBe('t2'); expect(state(w).activeTab).toBe('result'); w.unmount()
  })
  it('慢响应期间不并发轮询，终态停止轮询', async () => {
    vi.useFakeTimers(); vi.mocked(tasksApi.detail).mockResolvedValue(task('t1', 'RUNNING') as any)
    const w = await start(); const pending = deferred<any>()
    vi.mocked(tasksApi.detail).mockReturnValueOnce(pending.promise)
    await vi.advanceTimersByTimeAsync(3000); await vi.advanceTimersByTimeAsync(9000)
    expect(tasksApi.detail).toHaveBeenCalledTimes(2)
    pending.resolve(task('t1')); await flushPromises(); await vi.advanceTimersByTimeAsync(9000)
    expect(tasksApi.detail).toHaveBeenCalledTimes(2); w.unmount(); vi.useRealTimers()
  })
  it('离开页面后迟到响应不重新建立轮询', async () => {
    vi.useFakeTimers(); const w = await start(); const pending = deferred<any>()
    vi.mocked(tasksApi.detail).mockReturnValueOnce(pending.promise)
    const old = state(w).loadTask(); w.unmount(); pending.resolve(task('t1', 'RUNNING')); await old
    expect(vi.getTimerCount()).toBe(0); vi.useRealTimers()
  })
  it('轮询刷新保留已加载的调用页次', async () => {
    vi.mocked(tasksApi.calls).mockResolvedValueOnce(page('first', true) as any)
    const w = await start(); const s = state(w)
    vi.mocked(tasksApi.calls).mockResolvedValueOnce(page('second') as any); await s.loadMoreCalls()
    vi.mocked(tasksApi.calls).mockResolvedValueOnce(page('latest-first', true) as any).mockResolvedValueOnce(page('latest-second') as any)
    await s.loadTask(); expect(s.calls.map((call: any) => call.id)).toEqual(['latest-first', 'latest-second']); w.unmount()
  })
  it('返回同一任务时恢复目标和页签', async () => {
    const w = await start(); const s = state(w); await s.selectTarget('b'); s.activeTab = 'materials'; w.unmount()
    const restored = await start(); expect(state(restored).selectedTargetId).toBe('b')
    expect(state(restored).activeTab).toBe('materials'); restored.unmount()
  })
  it('任务级准确率只展示问题级计数和明确判定比例', async () => {
    const w = await start()
    expect(w.text()).toContain('本任务问题判定准确率')
    expect(w.text()).toContain('50%')
    expect(w.text()).toContain('准确 1 · 不准确 1 · 无法判断 0 · 未反馈 0')
    expect(w.text()).toContain('仅统计本任务已完成结果中的问题')
    w.unmount()
  })
  it('没有明确判定时显示暂无判定，不显示 0% 或 100%', async () => {
    vi.mocked(tasksApi.statistics).mockResolvedValue(statistics('ISSUE', {
      issues: { total: 2, accurate: 0, inaccurate: 0, unknown: 1, unassessed: 1, accuracyPercent: null }
    }) as any)
    const w = await start()
    expect(w.text()).toContain('— · 暂无明确判定')
    expect(w.text()).not.toContain('0%')
    expect(w.text()).not.toContain('100%')
    w.unmount()
  })
})
