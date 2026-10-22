// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CodeBrowser from '@/views/CodeBrowser.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
const context = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({ projectsApi: { list: vi.fn() }, repositoryApi: {
  branches: vi.fn(), head: vi.fn(), paths: vi.fn(), commits: vi.fn(), commit: vi.fn(), file: vi.fn(), diff: vi.fn()
} }))
import { projectsApi, repositoryApi } from '@/api/v2'
function deferred<T>() { let resolve!: (v: T) => void; let reject!: (v: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject }
}
const SHA = 'a'.repeat(40)
const listing = (path = 'A.java', truncated = false) => ({ entries: [{ path, type: 'blob', reviewable: true }], truncated })
const state = (w: any) => w.vm.$.setupState as any
async function start() { const w = mount(CodeBrowser, { global: { stubs: { ...namedAntStubs,
  'a-tree': { inheritAttrs: false, template: '<div />' }, DiffViewer: true } } }); await flushPromises(); return w }
beforeEach(() => { vi.resetAllMocks(); sessionStorage.clear()
  vi.mocked(projectsApi.list).mockResolvedValue({ items: [{ id: 'p1', name: '一', currentBranch: 'main' }, { id: 'p2', name: '二', currentBranch: 'main' }] } as any)
  vi.mocked(repositoryApi.branches).mockResolvedValue(['main'])
  vi.mocked(repositoryApi.head).mockResolvedValue(SHA)
  vi.mocked(repositoryApi.paths).mockResolvedValue(listing() as any)
  vi.mocked(repositoryApi.file).mockResolvedValue({ content: 'original' } as any)
  vi.mocked(repositoryApi.commits).mockResolvedValue({ commits: [{ sha: 'new-commit', message: '新' }], hasMore: false } as any)
  vi.mocked(repositoryApi.commit).mockImplementation(async (_id, sha) => ({ sha, files: [{ path: 'A.java', reviewable: true }] }) as any)
})
describe('代码浏览来源与返回现场', () => {
  it('旧 HEAD 不写回，也不以新项目读取旧版本路径', async () => {
    const w = await start(); const s = state(w); const old = deferred<string>(); const latest = deferred<string>()
    vi.mocked(repositoryApi.head).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    s.branch = 'old'; const a = s.onBranchChange(); s.branch = 'new'; const b = s.onBranchChange()
    latest.resolve('b'.repeat(40)); await b; old.resolve('c'.repeat(40)); await a
    expect(s.version).toBe('b'.repeat(40)); expect(repositoryApi.paths).not.toHaveBeenCalledWith('p1', 'c'.repeat(40)); w.unmount()
  })
  it('切换项目后旧分支响应不能替换新项目分支', async () => {
    const w = await start(); const s = state(w); const old = deferred<string[]>(); const latest = deferred<string[]>()
    vi.mocked(repositoryApi.branches).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const a = s.onProjectChange(); s.projectId = 'p2'; const b = s.onProjectChange()
    latest.resolve(['main', 'new']); await b; old.resolve(['old']); await a
    expect(s.branches).toEqual(['main', 'new']); expect(s.projectId).toBe('p2'); w.unmount()
  })
  it('差异模式换分支时重新选择该分支提交', async () => {
    const w = await start(); const s = state(w); s.mode = 'DIFF'; await s.onModeChange()
    expect(s.commitSha).toBe('new-commit')
    vi.mocked(repositoryApi.commits).mockResolvedValue({ commits: [{ sha: 'other', message: '' }], hasMore: false } as any)
    s.branch = 'other'; await s.onBranchChange(); expect(s.commitSha).toBe('other')
    expect(repositoryApi.commit).toHaveBeenLastCalledWith('p1', 'other'); w.unmount()
  })
  it('文件预览迟到不能覆盖当前文件', async () => {
    const w = await start(); const s = state(w); const a = deferred<any>(); const b = deferred<any>()
    vi.mocked(repositoryApi.file).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const first = s.onSelect([], { node: { isLeaf: true, path: 'A.java' } })
    const second = s.onSelect([], { node: { isLeaf: true, path: 'B.java' } })
    b.resolve({ content: 'B text' }); await second; a.resolve({ content: 'A text' }); await first
    expect(s.previewPath).toBe('B.java'); expect(s.previewContent).toBe('B text'); w.unmount()
  })
  it('列表不完整时明确提示，加载失败保留同版本内容但禁止带入', async () => {
    const w = await start(); const s = state(w)
    vi.mocked(repositoryApi.paths).mockResolvedValue(listing('A.java', true) as any); await s.reload()
    expect(w.html()).toContain('文件清单不完整'); s.selected = ['A.java']
    vi.mocked(repositoryApi.paths).mockRejectedValue(new Error('失败')); await s.reload(); s.launchSelected()
    expect(s.entries[0].path).toBe('A.java'); expect(context.push).not.toHaveBeenCalled(); w.unmount()
  })
  it('返回浏览时恢复固定版本、选择与预览，重新读取保存版本', async () => {
    const w = await start(); const s = state(w); s.selected = ['A.java']; s.query = 'A'
    await s.onSelect([], { node: { isLeaf: true, path: 'A.java' } }); w.unmount()
    vi.mocked(repositoryApi.head).mockClear(); const restored = await start()
    expect(state(restored).selected).toEqual(['A.java']); expect(state(restored).query).toBe('A')
    expect(state(restored).previewPath).toBe('A.java'); expect(repositoryApi.head).not.toHaveBeenCalled(); restored.unmount()
  })
  it('提交加载更多失败后保留已有列表并重试同一页', async () => {
    const w = await start(); const s = state(w)
    vi.mocked(repositoryApi.commits).mockResolvedValueOnce({ commits: [{ sha: 'first', message: '' }], hasMore: true } as any)
    s.mode = 'DIFF'; await s.onModeChange()
    vi.mocked(repositoryApi.commits).mockRejectedValueOnce(new Error('失败')).mockResolvedValueOnce({ commits: [{ sha: 'second', message: '' }], hasMore: false } as any)
    await s.loadMoreCommits(); expect(s.commits).toHaveLength(1); await s.loadMoreCommits()
    expect(vi.mocked(repositoryApi.commits).mock.calls.slice(-2).map(call => call[2])).toEqual([2, 2])
    expect(s.commits).toHaveLength(2)
    s.commitsHasMore = true
    const late = deferred<any>(); vi.mocked(repositoryApi.commits).mockReturnValueOnce(late.promise)
    const oldPage = s.loadMoreCommits(); s.branch = 'other'; await s.onBranchChange()
    late.reject(new Error('旧来源失败')); await oldPage
    expect(s.error).toBe(''); expect(s.commitSha).toBe('new-commit'); w.unmount()
  })
})
