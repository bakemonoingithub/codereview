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
vi.mock('@/api/v2', () => ({ projectsApi: { detail: vi.fn() }, repositoryApi: { pull: vi.fn() }, tasksApi: {
  detail: vi.fn(), statistics: vi.fn(), result: vi.fn(), materials: vi.fn(), calls: vi.fn(), cancel: vi.fn()
} }))
import { projectsApi, repositoryApi, tasksApi } from '@/api/v2'
import { recordFeedbackSave, feedbackDraftKey, storeFeedbackDraft, discardFeedbackDrafts } from '@/components/feedbackDrafts'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { Modal } from 'ant-design-vue'
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
  SpecialistReviewResultTab: { props: ['result'], template: '<div>{{ result.id }}</div>' },
  MaterialReaderModal: true, CodeReferenceModal: true, CallReaderModal: true, 'a-table-column': { template: '<span />' }
} } }); await flushPromises(); return w }
beforeEach(() => {
  vi.useRealTimers(); vi.resetAllMocks(); sessionStorage.clear()
  discardFeedbackDrafts('t1'); discardFeedbackDrafts('t2')
  context.route = reactive({ params: { id: 't1' }, query: {} })
  vi.mocked(tasksApi.detail).mockImplementation(async id => task(id) as any)
  vi.mocked(projectsApi.detail).mockResolvedValue({ name: '项目' } as any)
  vi.mocked(tasksApi.result).mockImplementation(async (_id, target) => result(target) as any)
  vi.mocked(tasksApi.statistics).mockResolvedValue(statistics() as any)
  vi.mocked(tasksApi.materials).mockResolvedValue([])
  vi.mocked(tasksApi.calls).mockImplementation(async (_id, params) => page('c-' + params?.targetId) as any)
})
describe('任务详情的轮询与目标隔离', () => {
  it('报告来源链接优先定位对应文件和结果页，不受已保存现场影响', async () => {
    sessionStorage.setItem('review-detail-t1', JSON.stringify({ selectedTargetId: 'a', activeTab: 'calls', callsPages: 1 }))
    context.route.query = { targetId: 'b' }
    const wrapper = await start()
    expect(state(wrapper).selectedTargetId).toBe('b')
    expect(state(wrapper).activeTab).toBe('result')
    expect(tasksApi.result).toHaveBeenCalledWith('t1', 'b', true)
    wrapper.unmount()
  })

  it('同任务切换链接目标后重新加载对应文件；无效目标沿用现有提示和回退', async () => {
    context.route.query = { targetId: 'a' }
    const wrapper = await start()
    context.route.query.targetId = 'b'; await flushPromises()
    expect(state(wrapper).selectedTargetId).toBe('b')
    expect(tasksApi.result).toHaveBeenLastCalledWith('t1', 'b', true)
    context.route.query.targetId = 'missing'; await flushPromises()
    expect(state(wrapper).selectedTargetId).toBe('a')
    expect(tasksApi.result).toHaveBeenLastCalledWith('t1', 'a', true)
    wrapper.unmount()
  })

  it('PR 详情在标题下提示版本变化，目标文件上方不显示 PR 摘要', async () => {
    const frozen = 'a'.repeat(40)
    vi.mocked(tasksApi.detail).mockResolvedValue({
      ...task(), sourceKind: 'PR', pullNumber: 12, pullTitle: '修复重试', pullUrl: 'https://example/12',
      sourceBranch: 'feature', targetBranch: 'dev', sourceHead: frozen, targetHead: 'b'.repeat(40),
      mergeBase: 'c'.repeat(40), textFiles: 2, changedFiles: 3, selectedFiles: 1,
      coverageNote: '二进制没有审查。', targets: [{ id: 'a', path: 'A.java', state: 'SUCCEEDED' }]
    } as any)
    vi.mocked(repositoryApi.pull).mockResolvedValue({
      pullRequest: { number: 12, sourceHead: 'd'.repeat(40), targetHead: 'b'.repeat(40) },
      mergeBase: 'c'.repeat(40), blocker: null
    } as any)
    const wrapper = await start()
    const sidebar = wrapper.get('.targets')
    expect(sidebar.element.firstElementChild?.textContent).toBe('目标文件1 个')
    expect(sidebar.text()).not.toContain('PR #12')
    expect(sidebar.text()).not.toContain('修复重试')
    expect(sidebar.text()).not.toContain('feature')
    expect(sidebar.text()).not.toContain('已选 1 / 文本 2 / 全部 3')
    expect(sidebar.text()).not.toContain('二进制没有审查。')
    expect(sidebar.find('a-alert-stub').exists()).toBe(false)
    const banner = wrapper.get('.detail-head + .review-detail-notices a-alert-stub[type="warning"]')
    expect(banner.attributes('type')).toBe('warning')
    expect(banner.attributes('message'))
      .toBe('当前 PR 版本已变化。本页结果仍对应发起时冻结的版本。')
    await wrapper.findAll('button').find(button => button.text() === '输入与配置')!.trigger('click')
    await flushPromises()
    const version = wrapper.get('.input-pairs .input-version')
    const label = 'PR #12 · cccccccccccc（共同祖先）→ aaaaaaaaaaaa（源提交）'
    expect(version.text()).toBe(label)
    expect(version.attributes('title')).toBe(`${label} · 目标提交 ${'b'.repeat(40)}`)
    const link = wrapper.get('.input-pairs a[href="https://example/12"]')
    expect(link.text()).toBe('打开 PR')
    expect(link.attributes('target')).toBe('_blank')
    wrapper.unmount()
  })

  it.each(['ISSUE', 'PATTERN', 'COUPLING'])('%s 初次打开就显示每个文件的发现数，不依赖结果详情', async (specialistType) => {
    const loaded = { ...task(), specialistType, targets: [
      { id: 'a', path: 'A.java', state: 'SUCCEEDED', findingCount: 6 },
      { id: 'b', path: 'B.java', state: 'SUCCEEDED', findingCount: 3 }
    ] }
    vi.mocked(tasksApi.detail).mockResolvedValue(loaded as any)
    const pending = deferred<any>()
    vi.mocked(tasksApi.result).mockReturnValueOnce(pending.promise)
    const w = await start()
    expect(w.findAll('.target').map(row => row.text())).toEqual([
      expect.stringContaining('6 项发现'), expect.stringContaining('3 项发现')
    ])
    expect(tasksApi.result).toHaveBeenCalledTimes(1)
    pending.resolve(result()); await flushPromises()
    await w.findAll('.target')[1].trigger('click'); await flushPromises()
    expect(w.findAll('.target').map(row => row.text())).toEqual([
      expect.stringContaining('6 项发现'), expect.stringContaining('3 项发现')
    ])
    w.unmount()
  })
  it('完整结果可以显示 0，未完成和失败的文件不显示发现数', async () => {
    vi.mocked(tasksApi.detail).mockResolvedValue({ ...task(), targets: [
      { id: 'a', path: 'A.java', state: 'SUCCEEDED', findingCount: 0 },
      { id: 'b', path: 'B.java', state: 'RUNNING', findingCount: null },
      { id: 'c', path: 'C.java', state: 'FAILED', errorCode: 'CONTEXT_LIMIT', findingCount: null }
    ] } as any)
    const w = await start()
    const rows = w.findAll('.target')
    expect(rows[0].text()).toContain('0 项发现')
    expect(rows[1].text()).not.toContain('项发现')
    expect(rows[2].text()).not.toContain('项发现')
    expect(rows[2].text()).toContain('CONTEXT_LIMIT')
    w.unmount()
  })
  it('刷新任务时兼容旧接口返回的问题数', async () => {
    const w = await start()
    vi.mocked(tasksApi.detail).mockResolvedValue({ ...task(), targets: [
      { id: 'a', path: 'A.java', state: 'SUCCEEDED', issueCount: 6 },
      { id: 'b', path: 'B.java', state: 'SUCCEEDED', issueCount: 2 }
    ] } as any)
    await state(w).loadTask()
    expect(w.findAll('.target')[1].text()).toContain('2 项发现')
    expect(state(w).selectedTargetId).toBe('a')
    w.unmount()
  })
  it('保留旧统计时仍展示刷新失败提示', async () => {
    const w = await start()
    vi.mocked(tasksApi.statistics).mockRejectedValueOnce(new Error('统计失败'))
    await state(w).loadStatistics('t1')
    expect(w.text()).toContain('50%')
    expect(w.text()).toContain('准确率统计读取失败：统计失败')
    w.unmount()
  })
  it('保存原任务反馈时刷新统计，旧任务通知不会刷新当前任务', async () => {
    const w = await start()
    const count = vi.mocked(tasksApi.statistics).mock.calls.length
    recordFeedbackSave('other'); await flushPromises()
    expect(tasksApi.statistics).toHaveBeenCalledTimes(count)
    recordFeedbackSave('t1'); await flushPromises()
    expect(tasksApi.statistics).toHaveBeenCalledTimes(count + 1)
    w.unmount()
  })
  it('旧任务统计迟到时不能覆盖新任务', async () => {
    const w = await start(); const pending = deferred<any>()
    vi.mocked(tasksApi.statistics).mockReturnValueOnce(pending.promise)
    const old = state(w).loadStatistics('t1')
    context.route.params.id = 't2'; await flushPromises()
    pending.resolve(statistics('ISSUE', { issues: { total: 999 } })); await old
    expect(state(w).stats.issues.total).toBe(2)
    w.unmount()
  })
  it('离页及切换任务守卫保护草稿，取消保留，确认后才丢弃', async () => {
    const w = await start()
    const key = feedbackDraftKey('t1', 'ISSUE', 'issue-1')
    storeFeedbackDraft(key, { assessment: null, comment: '草稿', revision: 1, dirty: true,
      baseline: { assessment: null, comment: '' }, updatedAt: null })
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(() => ({ destroy: vi.fn(), update: vi.fn() }) as any)
    const leave = vi.mocked(onBeforeRouteLeave).mock.calls[0][0] as any
    const update = vi.mocked(onBeforeRouteUpdate).mock.calls[0][0] as any
    expect(update({ params: { id: 't1' } }, { params: { id: 't1' } })).toBe(true)
    const cancelled = leave()
    ;(confirm.mock.calls[0][0] as any).onCancel()
    expect(await cancelled).toBe(false)
    const accepted = update({ params: { id: 't2' } }, { params: { id: 't1' } })
    ;(confirm.mock.calls[1][0] as any).onOk()
    expect(await accepted).toBe(true)
    confirm.mockRestore(); w.unmount()
  })
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
  it.each([
    ['QUEUED', '正在排队'], ['RUNNING', '正在审查'], ['FAILED', '审查失败'], ['CANCELLED', '已取消']
  ])('文件处于 %s 且没有发布结果时显示对应状态', async (status, copy) => {
    vi.mocked(tasksApi.detail).mockResolvedValue(task('t1', status) as any)
    vi.mocked(tasksApi.result).mockRejectedValue(Object.assign(new Error('该目标还没有已发布结果'), { code: 'NOT_FOUND' }))
    const w = await start()
    expect(w.get('.content .empty').text()).toContain(copy)
    expect(w.get('.content').find('a-alert-stub').exists()).toBe(false)
    w.unmount()
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
  it('部分完成的耦合审查在文件列表前同时显示状态和未反馈统计', async () => {
    vi.mocked(tasksApi.detail).mockResolvedValue({
      ...task('t1', 'PARTIAL'), specialistType: 'COUPLING'
    } as any)
    vi.mocked(tasksApi.statistics).mockResolvedValue(statistics('COUPLING', {
      results: { total: 2, accurate: 0, inaccurate: 0, unknown: 0, unassessed: 2, accuracyPercent: null },
      couplings: { total: 10, accurate: 0, inaccurate: 0, unknown: 0, unassessed: 10, accuracyPercent: null }
    }) as any)
    const w = await start()
    const notices = w.get('.detail-head + .review-detail-notices')
    expect(notices.element.nextElementSibling).toBe(w.get('.detail-grid').element)
    expect(notices.get('a-alert-stub[type="warning"]').attributes('message')).toContain('部分完成')
    const summary = notices.get('a-alert-stub[type="info"]')
    expect(summary.text()).toContain('本任务依赖解读判定准确率')
    expect(summary.text()).toContain('— · 暂无明确判定')
    expect(summary.text()).toContain('准确 0 · 不准确 0 · 无法判断 0 · 未反馈 10')
    w.unmount()
  })
  it('问题引用走专用代码阅读，材料入口仍走材料弹窗', async () => {
    const w = await start(); const s = state(w)
    s.openCodeReference({
      materialId: 'm1', path: 'A.java', sha: 'fixed', side: 'FILE', startLine: 10, endLine: 12,
      scope: 'issue', evidenceCount: 1,
      references: [{ materialId: 'm1', path: 'A.java', sha: 'fixed', side: 'FILE', startLine: 10, endLine: 12 }]
    })
    expect(s.codeOpen).toBe(true)
    expect(s.codePayload.path).toBe('A.java')
    expect(s.codePayload.references).toHaveLength(1)

    s.openMaterialById('m2')
    expect(s.materialOpen).toBe(true)
    expect(s.materialId).toBe('m2')
    w.unmount()
  })
  it('没有可阅读引用时退回材料弹窗', async () => {
    const w = await start(); const s = state(w)
    s.openCodeReference({ materialId: 'm3', path: 'A.java', side: 'FILE', startLine: 1, endLine: 1, references: [], scope: 'issue' })
    expect(s.materialOpen).toBe(true)
    expect(s.materialId).toBe('m3')
    expect(s.codeOpen).toBe(false)
    w.unmount()
  })
})
