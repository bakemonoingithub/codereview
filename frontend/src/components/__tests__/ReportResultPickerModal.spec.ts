// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportResultPickerModal from '@/components/ReportResultPickerModal.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
import type { ReportCandidate } from '@/api/v2'

vi.mock('@/api/v2', () => ({ reportsApi: { candidates: vi.fn() } }))
import { reportsApi } from '@/api/v2'

function candidate(id: string, extra: Partial<ReportCandidate> = {}): ReportCandidate {
  return { resultId: id, taskId: `task-${id}`, taskName: `审查 ${id}`, targetId: `target-${id}`,
    kind: 'ISSUE', projectId: 'p1', projectName: '订单服务', taskRoute: 'SPECIALIST', taskState: 'SUCCEEDED',
    sourceKind: 'FILES', path: `src/${id}.java`, oldPath: null, headSha: 'abcdef123456789', baseSha: null,
    branch: 'main', resultCreatedAt: '2026-10-25T09:00:00', issueCount: 2, feedback: null, ...extra }
}
const page = (items: ReportCandidate[], nextCursor: string | null = null) => ({ items, nextCursor, hasMore: !!nextCursor })
const state = (wrapper: any) => wrapper.vm.$.setupState as any
function button(wrapper: any, text: string) {
  return wrapper.findAll('a-button-stub').find((item: any) => item.text() === text)!
}
async function start(props: Record<string, unknown> = {}) {
  const wrapper = mount(ReportResultPickerModal, { props: { open: true, selected: [], projects: [], ...props } as any,
    global: { stubs: { ...namedAntStubs, 'a-modal': { name: 'AModal', template: '<div><slot /></div>' } } } })
  await flushPromises()
  return wrapper
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(reportsApi.candidates).mockResolvedValue(page([candidate('a'), candidate('b')]))
})

describe('报告审查结果选择弹窗', () => {
  it('仅打开时加载，勾选是草稿，确认才提交并关闭', async () => {
    const wrapper = await start({ open: false })
    expect(reportsApi.candidates).not.toHaveBeenCalled()
    await wrapper.setProps({ open: true }); await flushPromises()
    await wrapper.findAll('.result-main')[1].trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    expect(state(wrapper).draft.has('b')).toBe(true)
    await button(wrapper, '确定').trigger('click')
    expect(wrapper.emitted('confirm')![0][0]).toEqual([candidate('b')])
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    wrapper.unmount()
  })

  it('取消丢弃草稿，重新打开从原选择回填', async () => {
    const wrapper = await start({ selected: [candidate('a')] })
    state(wrapper).toggle(candidate('b'))
    await button(wrapper, '取消').trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await wrapper.setProps({ open: false }); await wrapper.setProps({ open: true }); await flushPromises()
    expect([...state(wrapper).draft.keys()]).toEqual(['a'])
    wrapper.unmount()
  })

  it('跨项目和类型筛选保留草稿，确认允许清空全部选择', async () => {
    const wrapper = await start({ selected: [candidate('old')] })
    state(wrapper).toggle(candidate('b'))
    const agent = candidate('agent', { kind: 'AGENT', targetId: null, path: null, answerStatus: 'INSUFFICIENT_EVIDENCE', unresolvedCount: 2 })
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([agent]))
    state(wrapper).filterProject = 'p2'; state(wrapper).filterKind = 'AGENT'
    await state(wrapper).resetCandidates()
    expect(reportsApi.candidates).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 'p2', kind: 'AGENT' }))
    expect([...state(wrapper).draft.keys()]).toEqual(['old', 'b'])
    expect(wrapper.text()).toContain('材料不足')
    expect(wrapper.text()).toContain('未解决 2 项')
    await button(wrapper, '清空选择').trigger('click')
    await button(wrapper, '确定').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[[]]])
    wrapper.unmount()
  })

  it('关键词只筛选已加载数据，翻页按结果 ID 去重', async () => {
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([candidate('a')], 'next'))
    const wrapper = await start()
    await wrapper.find('input').setValue('abcdef')
    expect(wrapper.findAll('.result-main')).toHaveLength(1)
    await wrapper.find('input').setValue('b.java')
    expect(wrapper.findAll('.result-main')).toHaveLength(0)
    expect(wrapper.text()).toContain('关键词仅筛选已加载结果')
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([candidate('a'), candidate('b')]))
    await state(wrapper).loadMore(); await flushPromises()
    expect(state(wrapper).candidates).toHaveLength(2)
    expect(wrapper.findAll('.result-main')).toHaveLength(1)
    expect(wrapper.text()).toContain('已加载完当前筛选下的结果')
    wrapper.unmount()
  })

  it('失败的翻页保留列表和游标，重试同一页', async () => {
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([candidate('a')], 'next'))
    const wrapper = await start()
    vi.mocked(reportsApi.candidates).mockRejectedValueOnce(new Error('下一页读取失败'))
    await state(wrapper).loadMore()
    expect(state(wrapper).candidates).toHaveLength(1)
    expect(state(wrapper).nextCursor).toBe('next')
    const count = vi.mocked(reportsApi.candidates).mock.calls.length
    await state(wrapper).loadMore()
    expect(reportsApi.candidates).toHaveBeenCalledTimes(count)
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([candidate('b')]))
    await state(wrapper).retry()
    expect(reportsApi.candidates).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'next' }))
    expect(state(wrapper).candidates.map((item: ReportCandidate) => item.resultId)).toEqual(['a', 'b'])
    wrapper.unmount()
  })

  it('初次读取和刷新失败均能重试，刷新保留最近成功列表', async () => {
    vi.mocked(reportsApi.candidates).mockRejectedValueOnce(new Error('首次失败'))
    const wrapper = await start()
    expect(state(wrapper).error).toBe('首次失败')
    await state(wrapper).retry()
    expect(state(wrapper).candidates).toHaveLength(2)
    vi.mocked(reportsApi.candidates).mockRejectedValueOnce(new Error('刷新失败'))
    await state(wrapper).resetCandidates(false)
    expect(state(wrapper).candidates).toHaveLength(2)
    await state(wrapper).retry()
    expect(state(wrapper).error).toBe('')
    expect(reportsApi.candidates).toHaveBeenLastCalledWith(expect.not.objectContaining({ cursor: expect.anything() }))
    wrapper.unmount()
  })

  it('切换筛选后丢弃迟到的翻页结果，新的游标仍可用', async () => {
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([candidate('a')], 'old'))
    const wrapper = await start()
    const old = deferred<ReturnType<typeof page>>()
    vi.mocked(reportsApi.candidates).mockReturnValueOnce(old.promise)
    const pending = state(wrapper).loadMore()
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([candidate('new')], 'new-cursor'))
    state(wrapper).filterKind = 'AGENT'; await state(wrapper).resetCandidates()
    old.resolve(page([candidate('stale')]))
    await pending
    expect(state(wrapper).candidates.map((item: ReportCandidate) => item.resultId)).toEqual(['new'])
    expect(state(wrapper).nextCursor).toBe('new-cursor')
    expect(state(wrapper).loadingMore).toBe(false)
    await state(wrapper).loadMore()
    expect(reportsApi.candidates).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'new-cursor', kind: 'AGENT' }))
    wrapper.unmount()
  })

  it('旧请求失败不能覆盖新请求的加载状态，关闭和卸载后拒绝迟到响应', async () => {
    const first = deferred<ReturnType<typeof page>>()
    const second = deferred<ReturnType<typeof page>>()
    vi.mocked(reportsApi.candidates).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = await start()
    const pending = state(wrapper).resetCandidates()
    first.reject(new Error('旧错误')); await flushPromises()
    expect(state(wrapper).loading).toBe(true)
    expect(state(wrapper).error).toBe('')
    await wrapper.setProps({ open: false })
    second.resolve(page([candidate('late')])); await pending
    expect(state(wrapper).candidates).toEqual([])
    await wrapper.setProps({ open: true }); await flushPromises()
    const third = deferred<ReturnType<typeof page>>()
    vi.mocked(reportsApi.candidates).mockReturnValueOnce(third.promise)
    const afterUnmount = state(wrapper).resetCandidates()
    wrapper.unmount(); third.reject(new Error('卸载后的错误')); await afterUnmount
  })

  it('预览独立于勾选，材料不足的 Agent 结果可预览', async () => {
    const agent = candidate('agent', { kind: 'AGENT', targetId: null, path: null, answerStatus: 'INSUFFICIENT_EVIDENCE' })
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([agent]))
    const wrapper = await start()
    await button(wrapper, '查看结果').trigger('click')
    expect(wrapper.emitted('preview')).toEqual([[agent]])
    expect(state(wrapper).draft.size).toBe(0)
    wrapper.unmount()
  })

  it('遵守 200 个材料上限，移除后才可继续选择', async () => {
    const wrapper = await start({ selected: Array.from({ length: 200 }, (_, index) => candidate(`selected-${index}`)) })
    expect(state(wrapper).draft.size).toBe(200)
    state(wrapper).toggle(candidate('extra'))
    expect(state(wrapper).draft.has('extra')).toBe(false)
    state(wrapper).toggle(candidate('selected-0'))
    state(wrapper).toggle(candidate('extra'))
    expect(state(wrapper).draft.size).toBe(200)
    expect(state(wrapper).draft.has('extra')).toBe(true)
    wrapper.unmount()
  })

  it('触底加载下一页，重复滚动不并发读取同一页', async () => {
    vi.mocked(reportsApi.candidates).mockResolvedValueOnce(page([candidate('a')], 'next'))
    const wrapper = await start()
    const next = deferred<ReturnType<typeof page>>()
    vi.mocked(reportsApi.candidates).mockReturnValueOnce(next.promise)
    const event = { target: { scrollTop: 300, clientHeight: 360, scrollHeight: 660 } }
    state(wrapper).onScroll(event); state(wrapper).onScroll(event)
    expect(reportsApi.candidates).toHaveBeenCalledTimes(2)
    next.resolve(page([candidate('b')]))
    await flushPromises()
    expect(state(wrapper).candidates).toHaveLength(2)
    wrapper.unmount()
  })
})
