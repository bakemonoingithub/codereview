// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ReviewLaunch from '@/views/ReviewLaunch.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ route: { query: {} as Record<string, string> }, push: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => context.route, useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({
  projectsApi: { list: vi.fn() }, modelsApi: { list: vi.fn() }, strategiesApi: { list: vi.fn() },
  repositoryApi: { head: vi.fn(), commits: vi.fn(), commit: vi.fn() },
  tasksApi: { create: vi.fn(), copyInput: vi.fn(), detail: vi.fn() }
}))
import { projectsApi, modelsApi, strategiesApi, repositoryApi, tasksApi } from '@/api/v2'

function deferred<T>() { let resolve!: (v: T) => void; let reject!: (v: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject }
}
const page = (items: unknown[]) => ({ items, nextCursor: null, hasMore: false })
const state = (wrapper: any) => wrapper.vm.$.setupState as any
async function start() {
  const wrapper = mount(ReviewLaunch, { global: { stubs: { ...namedAntStubs, ScopePickerModal: true, RouterLink: true } } })
  await flushPromises(); return wrapper
}
beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear(); context.route.query = {}
  vi.mocked(projectsApi.list).mockResolvedValue(page([{ id: 'p1', name: '项目', repoUrl: 'repo', currentBranch: 'main' }]) as any)
  vi.mocked(modelsApi.list).mockResolvedValue(page([{ id: 'm1', name: '模型' }]) as any)
  vi.mocked(strategiesApi.list).mockResolvedValue(page([]) as any)
  vi.mocked(repositoryApi.head).mockResolvedValue('sha-main')
  vi.mocked(repositoryApi.commits).mockResolvedValue({ commits: [] } as any)
  vi.mocked(repositoryApi.commit).mockImplementation(async (_id, sha) => ({ sha, parents: ['base-' + sha], files: [] }) as any)
})

describe('专项发起来源和提交保护', () => {
  it('初次只读取一次 HEAD，默认不开启拆分', async () => {
    const w = await start(); expect(repositoryApi.head).toHaveBeenCalledTimes(1)
    expect(state(w).form.smartSplit).toBe(false); w.unmount()
  })
  it('快速切换分支后丢弃旧 HEAD', async () => {
    const w = await start(); const s = state(w)
    const old = deferred<string>(); const latest = deferred<string>()
    vi.mocked(repositoryApi.head).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    s.form.branch = 'old'; const a = s.onBranchChange()
    s.form.branch = 'new'; const b = s.onBranchChange()
    latest.resolve('sha-new'); await b; old.resolve('sha-old'); await a
    expect(s.form.observedSha).toBe('sha-new'); w.unmount()
  })
  it('重新观察到新版本时清空旧目标及说明', async () => {
    const w = await start(); const s = state(w)
    s.form.targets = [{ path: 'A.java', oldPath: null, note: '旧说明' }]
    vi.mocked(repositoryApi.head).mockResolvedValue('moved')
    await s.resolveHead(); expect(s.form.targets).toEqual([]); w.unmount()
  })
  it('版本读取中禁止提交，失败不保留旧版本', async () => {
    const w = await start(); const s = state(w)
    s.form.modelConfigId = 'm1'; s.form.targets = [{ path: 'A.java', oldPath: null, note: '' }]
    const pending = deferred<string>(); vi.mocked(repositoryApi.head).mockReturnValue(pending.promise)
    const loading = s.resolveHead(); expect(s.canSubmit).toBe(false)
    pending.reject(new Error('不可读取')); await loading
    expect(s.form.observedSha).toBe(''); expect(s.canSubmit).toBe(false); w.unmount()
  })
  it('刷新失败后的重试仍能识别版本移动', async () => {
    const w = await start(); const s = state(w)
    s.form.targets = [{ path: 'A.java', oldPath: null, note: '旧说明' }]
    vi.mocked(repositoryApi.head).mockRejectedValueOnce(new Error('暂时失败')).mockResolvedValueOnce('new-sha')
    await s.resolveHead(); expect(s.form.targets).toHaveLength(1)
    await s.resolveHead(); expect(s.form.targets).toEqual([]); w.unmount()
  })
  it('提交详情迟到时不覆盖新提交基线', async () => {
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()
    const old = deferred<any>(); const latest = deferred<any>()
    vi.mocked(repositoryApi.commit).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    s.form.commitSha = 'old'; const a = s.onCommitChange()
    s.form.commitSha = 'new'; const b = s.onCommitChange()
    latest.resolve({ sha: 'new', parents: ['new-base'] }); await b
    old.resolve({ sha: 'old', parents: ['old-base'] }); await a
    expect(s.form.baseSha).toBe('new-base'); expect(s.scopeReady).toBe(true); w.unmount()
  })
  it('差异模式更换分支时立即清除旧提交与范围', async () => {
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()
    s.form.commitSha = 'old'; await s.loadCommitDetail('old')
    s.form.targets = [{ path: 'A.java', note: '' }]; s.form.branch = 'other'
    expect(s.form.commitSha).toBe(''); expect(s.form.targets).toEqual([])
    expect(s.scopeReady).toBe(false); w.unmount()
  })
  it('相同输入重试复用幂等键，输入改变后生成新键', async () => {
    const w = await start(); const s = state(w)
    s.form.modelConfigId = 'm1'; s.form.targets = [{ path: 'A.java', oldPath: null, note: '' }]
    vi.mocked(tasksApi.create).mockRejectedValue(new Error('响应丢失'))
    await s.submit(); await s.submit()
    expect(vi.mocked(tasksApi.create).mock.calls[0][1]).toBe(vi.mocked(tasksApi.create).mock.calls[1][1])
    s.form.targets[0].note = '新要求'; await s.submit()
    expect(vi.mocked(tasksApi.create).mock.calls[2][1]).not.toBe(vi.mocked(tasksApi.create).mock.calls[0][1]); w.unmount()
  })
  it('SOURCE_MOVED 保留输入并阻止重复提交，刷新后重选', async () => {
    const w = await start(); const s = state(w)
    s.form.modelConfigId = 'm1'; s.form.targets = [{ path: 'A.java', oldPath: null, note: '说明' }]
    vi.mocked(tasksApi.create).mockRejectedValue(Object.assign(new Error('移动'), { code: 'SOURCE_MOVED' }))
    await s.submit(); expect(s.form.targets[0].note).toBe('说明'); expect(s.canSubmit).toBe(false)
    await s.refreshMovedSource(); expect(s.form.targets).toEqual([]); w.unmount()
  })
  it('复制 FILES 参数时重新观察 HEAD，移动则移除旧范围', async () => {
    context.route.query = { copyFrom: 'old-task' }
    vi.mocked(tasksApi.copyInput).mockResolvedValue({ projectId: 'p1', specialistType: 'ISSUE', sourceKind: 'FILES',
      branch: 'main', targets: [{ path: 'A.java', note: '旧说明' }], modelConfigId: 'm1', smartSplit: false, reobserveHead: true } as any)
    vi.mocked(tasksApi.detail).mockResolvedValue({ headSha: 'old-sha' } as any)
    const w = await start(); expect(state(w).form.observedSha).toBe('sha-main')
    expect(state(w).form.targets).toEqual([]); expect(state(w).copyHeadMoved).toBe(true); w.unmount()
  })
  it('普通发起不会消费此前遗留的浏览器带入数据', async () => {
    sessionStorage.setItem('review-launch-prefill', JSON.stringify({ projectId: 'p1', sourceKind: 'FILES', branch: 'stale', observedSha: 'old', targets: [{ path: 'Stale.java' }] }))
    const w = await start(); expect(state(w).form.branch).toBe('main'); expect(state(w).form.targets).toEqual([]); w.unmount()
  })
  it('显式从浏览器发起时保留固定版本和范围', async () => {
    context.route.query = { from: 'browser' }
    sessionStorage.setItem('review-launch-prefill', JSON.stringify({ projectId: 'p1', sourceKind: 'FILES', branch: 'main', observedSha: 'fixed', targets: [{ path: 'A.java', oldPath: null }] }))
    const w = await start(); expect(state(w).form.observedSha).toBe('fixed')
    expect(state(w).form.targets[0].path).toBe('A.java'); expect(repositoryApi.head).not.toHaveBeenCalled(); w.unmount()
  })

})
