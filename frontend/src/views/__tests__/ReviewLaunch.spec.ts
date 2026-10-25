// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import CommitPickerModal from '@/components/CommitPickerModal.vue'
import ReviewLaunch from '@/views/ReviewLaunch.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ route: { query: {} as Record<string, string> }, push: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => context.route, useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({
  projectsApi: { list: vi.fn() }, modelsApi: { list: vi.fn() }, promptsApi: { list: vi.fn(), detail: vi.fn(), versions: vi.fn() },
  repositoryApi: { head: vi.fn(), commits: vi.fn(), commit: vi.fn(), branches: vi.fn() },
  tasksApi: { create: vi.fn(), copyInput: vi.fn(), detail: vi.fn() }
}))
import { projectsApi, modelsApi, repositoryApi, tasksApi, promptsApi } from '@/api/v2'

function deferred<T>() { let resolve!: (v: T) => void; let reject!: (v: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject }
}
const page = (items: unknown[]) => ({ items, nextCursor: null, hasMore: false })
const sha = (character: string) => character.repeat(40)
const commit = (commitSha: string, message: string, author = '作者') => ({
  sha: commitSha,
  message,
  author,
  date: '2026-10-24T08:00:00Z'
})
const state = (wrapper: any) => wrapper.vm.$.setupState as any
async function start() {
  const wrapper = mount(ReviewLaunch, { global: { stubs: { ...namedAntStubs, ScopePickerModal: true, RouterLink: true,
    'a-modal': { name: 'AModal', template: '<div class="modal-stub"><slot /></div>' } } } })
  await flushPromises(); return wrapper
}
beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear(); context.route.query = {}
  vi.mocked(projectsApi.list).mockResolvedValue(page([{ id: 'p1', name: '项目', repoUrl: 'repo', currentBranch: 'main' }]) as any)
  vi.mocked(modelsApi.list).mockResolvedValue(page([{ id: 'm1', name: '模型' }]) as any)
  vi.mocked(promptsApi.list).mockResolvedValue(page([]) as any)
  vi.mocked(repositoryApi.branches).mockResolvedValue(['main'])
  vi.mocked(repositoryApi.head).mockResolvedValue('sha-main')
  vi.mocked(repositoryApi.commits).mockResolvedValue({ commits: [], hasMore: false } as any)
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
  it('当前分支不在仓库里时改用真实分支，不拿 main 去读提交', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue(page([{ id: 'p1', name: '项目', repoUrl: 'repo', currentBranch: 'main' }]) as any)
    vi.mocked(repositoryApi.branches).mockResolvedValue(['dev'])
    const w = await start()
    expect(state(w).form.branch).toBe('dev')
    expect(repositoryApi.head).toHaveBeenCalledWith('p1', 'dev')
    expect(repositoryApi.commits).not.toHaveBeenCalled()
    w.unmount()
  })
  it('分支列表失败时不读取提交', async () => {
    vi.mocked(repositoryApi.branches).mockRejectedValue(new Error('分支列表读取失败'))
    const w = await start()
    expect(repositoryApi.head).not.toHaveBeenCalled()
    expect(repositoryApi.commits).not.toHaveBeenCalled()
    expect(state(w).form.branch).toBe('')
    expect(state(w).formError).toContain('分支列表')
    w.unmount()
  })
  it('分页追加更早提交并按 SHA 去重', async () => {
    const first = sha('a'); const second = sha('b')
    vi.mocked(repositoryApi.commits)
      .mockResolvedValueOnce({ commits: [commit(first, '第一页提交')], hasMore: true } as any)
      .mockResolvedValueOnce({ commits: [commit(first, '重复项更新'), commit(second, '第二页提交')], hasMore: false } as any)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()

    expect(s.commitsPage).toBe(1); expect(s.commitsHasMore).toBe(true)
    await s.loadMoreCommits()

    expect(vi.mocked(repositoryApi.commits).mock.calls.map((call) => [call[1], call[2]])).toEqual([
      ['main', 1], ['main', 2]
    ])
    expect(s.commits.map((item: any) => item.sha)).toEqual([first, second])
    expect(s.commits[0].message).toBe('重复项更新')
    expect(s.commitsPage).toBe(2); expect(s.commitsHasMore).toBe(false)
    w.unmount()
  })
  it('更早页失败后保留已加载列表，并且只重试同一页', async () => {
    const first = sha('a'); const second = sha('b')
    vi.mocked(repositoryApi.commits)
      .mockResolvedValueOnce({ commits: [commit(first, '最新提交')], hasMore: true } as any)
      .mockRejectedValueOnce(new Error('第二页暂时失败'))
      .mockResolvedValueOnce({ commits: [commit(second, '更早提交')], hasMore: false } as any)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()

    await s.loadMoreCommits()
    expect(s.commits.map((item: any) => item.sha)).toEqual([first])
    expect(s.commitsPage).toBe(1); expect(s.commitsHasMore).toBe(true)
    expect(s.commitsError).toContain('第二页')

    await s.loadMoreCommits()
    expect(repositoryApi.commits).toHaveBeenCalledTimes(2)
    await s.retryCommits()
    expect(vi.mocked(repositoryApi.commits).mock.calls.map((call) => call[2])).toEqual([1, 2, 2])
    expect(s.commits.map((item: any) => item.sha)).toEqual([first, second])
    expect(s.commitsPage).toBe(2); expect(s.commitsError).toBe('')
    w.unmount()
  })
  it('加载更早提交时合并重复请求', async () => {
    const first = sha('a'); const second = sha('b')
    const pending = deferred<any>()
    vi.mocked(repositoryApi.commits)
      .mockResolvedValueOnce({ commits: [commit(first, '最新提交')], hasMore: true } as any)
      .mockReturnValueOnce(pending.promise)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()

    const firstRequest = s.loadMoreCommits()
    const duplicateRequest = s.loadMoreCommits()
    await duplicateRequest
    expect(repositoryApi.commits).toHaveBeenCalledTimes(2)

    pending.resolve({ commits: [commit(second, '更早提交')], hasMore: false })
    await firstRequest
    expect(s.commits.map((item: any) => item.sha)).toEqual([first, second])
    w.unmount()
  })
  it('切换分支后丢弃旧分支迟到的失败', async () => {
    const mainCommit = sha('a'); const devCommit = sha('d')
    const stale = deferred<any>()
    vi.mocked(repositoryApi.commits)
      .mockResolvedValueOnce({ commits: [commit(mainCommit, 'main 提交')], hasMore: true } as any)
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce({ commits: [commit(devCommit, 'dev 提交')], hasMore: false } as any)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()

    const oldRequest = s.loadMoreCommits()
    s.form.branch = 'dev'
    const newRequest = s.onBranchChange()
    await newRequest
    stale.reject(new Error('旧分支失败'))
    await oldRequest

    expect(vi.mocked(repositoryApi.commits).mock.calls.map((call) => [call[1], call[2]])).toEqual([
      ['main', 1], ['main', 2], ['dev', 1]
    ])
    expect(s.commits.map((item: any) => item.sha)).toEqual([devCommit])
    expect(s.commitsPage).toBe(1); expect(s.commitsError).toBe('')
    w.unmount()
  })
  it('刷新失败保留列表和页次，不会跳过失败页续读', async () => {
    const first = sha('a'); const second = sha('b'); const refreshed = sha('c')
    vi.mocked(repositoryApi.commits)
      .mockResolvedValueOnce({ commits: [commit(first, '第一页')], hasMore: true } as any)
      .mockResolvedValueOnce({ commits: [commit(second, '第二页')], hasMore: true } as any)
      .mockRejectedValueOnce(new Error('刷新失败'))
      .mockResolvedValueOnce({ commits: [commit(refreshed, '刷新后提交')], hasMore: false } as any)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()
    await s.loadMoreCommits()

    await s.loadCommits()
    expect(s.commits.map((item: any) => item.sha)).toEqual([first, second])
    expect(s.commitsPage).toBe(2); expect(s.commitsHasMore).toBe(true)
    expect(s.commitsError).toBe('刷新失败')

    await s.loadMoreCommits()
    expect(repositoryApi.commits).toHaveBeenCalledTimes(3)
    await s.retryCommits()
    expect(vi.mocked(repositoryApi.commits).mock.calls.map((call) => call[2])).toEqual([1, 2, 1, 1])
    expect(s.commits.map((item: any) => item.sha)).toEqual([refreshed])
    expect(s.commitsPage).toBe(1); expect(s.commitsHasMore).toBe(false)
    w.unmount()
  })
  it('取消或重新确认同一提交保留范围，确认新提交才清空', async () => {
    const selected = sha('a'); const replacement = sha('b')
    vi.mocked(repositoryApi.commits).mockResolvedValueOnce({
      commits: [commit(selected, '当前提交'), commit(replacement, '新提交')],
      hasMore: false
    } as any)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()
    await s.confirmCommit(selected)
    s.form.targets = [{ path: 'A.java', oldPath: null, note: '保留的说明' }]

    s.openCommitPicker(); await nextTick()
    const picker = w.findComponent(CommitPickerModal)
    const replacementRow = () => picker.findAll('.commit-row').find((row) => row.text().includes('新提交'))!
    const pickerButton = (label: string) => picker.findAll('a-button-stub').find((button) => button.text().trim() === label)!
    await replacementRow().trigger('click')
    await pickerButton('取消').trigger('click'); await nextTick()
    expect(s.form.commitSha).toBe(selected)
    expect(s.form.targets).toEqual([{ path: 'A.java', oldPath: null, note: '保留的说明' }])

    s.openCommitPicker(); await nextTick()
    await pickerButton('使用这个提交').trigger('click'); await flushPromises()
    expect(s.form.commitSha).toBe(selected)
    expect(s.form.targets).toHaveLength(1)

    s.openCommitPicker(); await nextTick()
    await replacementRow().trigger('click')
    await pickerButton('使用这个提交').trigger('click'); await flushPromises()
    expect(s.form.commitSha).toBe(replacement)
    expect(s.form.baseSha).toBe('base-' + replacement)
    expect(s.form.targets).toEqual([])
    w.unmount()
  })
  it('填写的审查名称随任务提交', async () => {
    const w = await start(); const s = state(w)
    s.form.name = '  支付重试  '
    s.form.modelConfigId = 'm1'
    s.form.targets = [{ path: 'A.java', oldPath: null, note: '' }]
    vi.mocked(tasksApi.create).mockResolvedValue({ id: 'task-1' } as any)
    await s.submit()
    expect(vi.mocked(tasksApi.create).mock.calls[0][0].name).toBe('支付重试')
    w.unmount()
  })

  it('完整 SHA 可直接定位，且不改变分支历史的页次与选择', async () => {
    const recent = sha('a'); const old = sha('b')
    vi.mocked(repositoryApi.commits).mockResolvedValue({ commits: [commit(recent, '最近提交')], hasMore: true } as any)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()
    s.openCommitPicker()
    vi.mocked(repositoryApi.commit).mockResolvedValue({ ...commit(old, '很早的提交'), parents: ['parent'], files: [] } as any)

    await s.locateCommit(old)
    expect(repositoryApi.commit).toHaveBeenCalledWith('p1', old)
    expect(s.locatedCommit.sha).toBe(old)
    expect(s.commits.map((item: any) => item.sha)).toEqual([recent])
    expect(s.commitsPage).toBe(1); expect(s.commitsHasMore).toBe(true)
    expect(s.form.commitSha).toBe('')
    await s.confirmCommit(old)
    expect(s.form.commitSha).toBe(old); expect(s.form.baseSha).toBe('parent')
    w.unmount()
  })

  it('查询改变、关闭或切换分支后不写回迟到的 SHA 定位结果', async () => {
    const w = await start(); const s = state(w)
    for (const change of ['query', 'close', 'branch']) {
      s.openCommitPicker()
      const pending = deferred<any>()
      vi.mocked(repositoryApi.commit).mockReturnValueOnce(pending.promise)
      const request = s.locateCommit(sha('b'))
      expect(s.commitLookupLoading).toBe(true)
      if (change === 'query') s.clearCommitLookup()
      else if (change === 'close') s.commitPickerOpen = false
      else s.form.branch = 'other'
      pending.resolve({ ...commit(sha('b'), '过期定位'), parents: [], files: [] })
      await request
      expect(s.locatedCommit).toBeNull(); expect(s.commitLookupLoading).toBe(false)
      expect(s.commitLookupError).toBe('')
    }
    w.unmount()
  })

  it('定位失败可重试，失败不影响历史列表', async () => {
    const w = await start(); const s = state(w)
    s.openCommitPicker()
    vi.mocked(repositoryApi.commit).mockRejectedValueOnce(new Error('SHA 不存在'))
      .mockResolvedValueOnce({ ...commit(sha('b'), '有效提交'), parents: [], files: [] } as any)
    await s.locateCommit('short')
    expect(repositoryApi.commit).not.toHaveBeenCalled()
    await s.locateCommit(sha('b'))
    expect(s.commitLookupError).toBe('SHA 不存在'); expect(s.commitsError).toBe('')
    await s.locateCommit(sha('b'))
    expect(s.commitLookupError).toBe(''); expect(s.locatedCommit.sha).toBe(sha('b'))
    w.unmount()
  })

  it.each(['success', 'failure'])('关闭选择器后忽略在途分页的 %s 响应', async (result) => {
    const recent = sha('a')
    vi.mocked(repositoryApi.commits).mockResolvedValueOnce({ commits: [commit(recent, '第一页')], hasMore: true } as any)
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()
    s.openCommitPicker()
    const pending = deferred<any>()
    vi.mocked(repositoryApi.commits).mockReturnValueOnce(pending.promise)
    const request = s.loadMoreCommits()
    s.commitPickerOpen = false
    if (result === 'success') pending.resolve({ commits: [commit(sha('b'), '迟到的第二页')], hasMore: false })
    else pending.reject(new Error('迟到的错误'))
    await request
    expect(s.commits.map((item: any) => item.sha)).toEqual([recent])
    expect(s.commitsPage).toBe(1); expect(s.commitsError).toBe('')
    expect(s.commitsLoadingMore).toBe(false)
    w.unmount()
  })

  it('详情未就绪或失败不显示根提交，成功重试后显示真实基线', async () => {
    const w = await start(); const s = state(w)
    s.form.sourceKind = 'DIFF'; await nextTick(); await flushPromises()
    s.form.commitSha = sha('a')
    const pending = deferred<any>()
    vi.mocked(repositoryApi.commit).mockReturnValueOnce(pending.promise)
    const request = s.onCommitChange(); await nextTick()
    expect(w.text()).not.toContain('根提交（无父版本）')
    expect(s.scopeReady).toBe(false)
    pending.reject(new Error('提交详情暂时失败')); await request; await nextTick()
    expect(s.commitDetailError).toBe('提交详情暂时失败')
    expect(w.text()).not.toContain('根提交（无父版本）')
    vi.mocked(repositoryApi.commit).mockResolvedValueOnce({ ...commit(sha('a'), '真实根提交'), parents: [], files: [] } as any)
    await s.loadCommitDetail(sha('a')); await nextTick()
    expect(s.commitDetailError).toBe(''); expect(s.scopeReady).toBe(true)
    expect(w.text()).toContain('根提交（无父版本）')
    w.unmount()
  })

  it('固定历史通用规则版本时预览同一正文，版本失效时阻止提交', async () => {
    const current = { id: 'v2', versionNo: 2, content: '新版规则', createdAt: '2026-10-25' }
    const old = { id: 'v1', versionNo: 1, content: '历史规则', createdAt: '2026-10-24' }
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'g1', name: '规则', purpose: 'SPECIALIST', tags: [], currentVersion: current } as any)
    vi.mocked(promptsApi.versions).mockResolvedValue([current, old])
    const wrapper = await start(); const s = state(wrapper)
    s.form.modelConfigId = 'm1'; s.form.promptId = 'g1'; s.form.targets = [{ path: 'A.java' }]
    await s.loadPromptSelection('g1', 'v1')
    expect(s.selectedPromptVersion.content).toBe('历史规则')
    expect(s.promptError).toBe('')
    vi.mocked(promptsApi.versions).mockResolvedValue([current])
    await s.loadPromptSelection('g1', 'v1')
    expect(s.promptError).toContain('不可用')
    expect(s.selectedPromptVersion).toBeNull()
    expect(s.canSubmit).toBe(false)
    wrapper.unmount()
  })

})

describe('UPI 审核修复', () => {
  it('首次加载失败后重试选中当前版本', async () => {
    const w = await start(); const s = state(w)
    try {
      vi.mocked(promptsApi.detail).mockRejectedValueOnce(new Error('临时失败'))
      vi.mocked(promptsApi.versions).mockResolvedValue([])
      s.form.promptId = 'g1'; await flushPromises()
      const version = { id: 'v1', versionNo: 1, content: '规则', createdAt: '2026-10-25' }
      vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'g1', name: '规则', purpose: 'SPECIALIST', tags: [], currentVersion: version } as any)
      vi.mocked(promptsApi.versions).mockResolvedValue([version])
      await s.loadPromptSelection(s.form.promptId, s.form.promptVersionId)
      expect(s.promptError).toBe('')
      expect(s.form.promptVersionId).toBe('v1')
      expect(s.selectedPromptVersion.content).toBe('规则')
    } finally { w.unmount() }
  })
  it('前一页全部为 Agent 规则时继续读取专项规则并去重', async () => {
    vi.mocked(promptsApi.list).mockResolvedValueOnce({ items: Array.from({ length: 100 }, (_, i) => ({ id: `a${i}`, name: 'Agent', purpose: 'AGENT' })), nextCursor: 'next', hasMore: true } as any)
      .mockResolvedValueOnce(page([{ id: 'a0', name: 'Agent', purpose: 'AGENT' }, { id: 'g1', name: '专项规则', purpose: 'SPECIALIST' }]) as any)
    const w = await start()
    try {
      expect(promptsApi.list).toHaveBeenNthCalledWith(2, { limit: 100, cursor: 'next' })
      expect(state(w).promptOptions).toEqual([{ value: 'g1', label: '专项规则' }])
      expect(state(w).prompts).toHaveLength(101)
    } finally { w.unmount() }
  })
  it('后续分页失败不展示不完整规则列表', async () => {
    vi.mocked(promptsApi.list).mockResolvedValueOnce({ items: [{ id: 'g1', purpose: 'SPECIALIST' }], nextCursor: 'next', hasMore: true } as any)
      .mockRejectedValueOnce(new Error('后续分页失败'))
    const w = await start()
    try {
      expect(state(w).formError).toBe('后续分页失败')
      expect(state(w).promptOptions).toEqual([])
    } finally { w.unmount() }
  })
  it('重复分页游标停止加载并报告异常', async () => {
    vi.mocked(promptsApi.list).mockResolvedValue({ items: [], nextCursor: 'same', hasMore: true } as any)
    const w = await start()
    try {
      expect(promptsApi.list).toHaveBeenCalledTimes(2)
      expect(state(w).formError).toContain('分页异常')
    } finally { w.unmount() }
  })
})

function formItem(wrapper: any, label: string) {
  return wrapper.findAll('a-form-item-stub').find((item: any) => item.attributes('label') === label)
}

describe('发起弹窗骨架', () => {
  it('三分区与页脚统一，星号只来自 required', async () => {
    const w = await start()
    expect(w.findComponent({ name: 'LaunchModalShell' }).props('title')).toBe('发起专项审查')
    expect(w.findAll('.launch-section')).toHaveLength(3)
    expect(w.text()).toContain('1. 审查目标')
    expect(w.text()).toContain('2. 范围与版本')
    expect(w.text()).toContain('3. 补充要求')
    const bar = w.find('.submit-bar')
    expect(bar.text().indexOf('取消')).toBeGreaterThanOrEqual(0)
    expect(bar.text().indexOf('取消')).toBeLessThan(bar.text().indexOf('开始审查'))
    expect(w.html()).not.toContain('class="required"')
    expect(w.html()).not.toContain('>必填<')
    for (const label of ['项目', '模型', '分支', '固定版本', '目标文件']) {
      expect(formItem(w, label)?.attributes('required'), label).toBe('')
    }
    expect(formItem(w, '审查名称')?.attributes('required')).toBeUndefined()
    expect(formItem(w, '规则版本')).toBeUndefined()
    const s = state(w)
    s.form.promptId = 'g1'
    await nextTick()
    expect(formItem(w, '规则版本')?.attributes('required')).toBe('')
    s.form.sourceKind = 'DIFF'
    await nextTick()
    expect(formItem(w, '审查提交')?.attributes('required')).toBe('')
    expect(formItem(w, '固定版本')).toBeUndefined()
    w.unmount()
  })

  it('复制参数时沿用原标题', async () => {
    context.route.query = { copyFrom: 'old-task' }
    vi.mocked(tasksApi.copyInput).mockResolvedValue({ projectId: 'p1', specialistType: 'ISSUE', sourceKind: 'FILES',
      branch: 'main', targets: [], modelConfigId: 'm1', smartSplit: false, reobserveHead: true } as any)
    vi.mocked(tasksApi.detail).mockResolvedValue({ headSha: 'old-sha' } as any)
    const w = await start()
    expect(w.findComponent({ name: 'LaunchModalShell' }).props('title')).toBe('复制参数发起新审查')
    w.unmount()
  })
})