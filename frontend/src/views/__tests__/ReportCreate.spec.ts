// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportCreate from '@/views/ReportCreate.vue'
import ReportResultPickerModal from '@/components/ReportResultPickerModal.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({
  agentTasksApi: { result: vi.fn() },
  projectsApi: { list: vi.fn() },
  modelsApi: { list: vi.fn() },
  reportsApi: { candidates: vi.fn(), freezeInputs: vi.fn(), start: vi.fn() },
  tasksApi: { result: vi.fn() }
}))

import { agentTasksApi, modelsApi, projectsApi, reportsApi, tasksApi } from '@/api/v2'

const candidate = {
  resultId: 'result-1', taskId: 'task-1', targetId: 'target-1', kind: 'ISSUE' as const,
  projectId: 'project-1', projectName: '订单服务', taskRoute: 'SPECIALIST',
  taskState: 'SUCCEEDED', sourceKind: 'FILES' as const, path: 'A.java', oldPath: null,
  headSha: 'abcdef123456', baseSha: null, branch: 'main', resultCreatedAt: '2026-10-21T10:00:00',
  issueCount: 2, feedback: null
}

const stubs = {
  ...namedAntStubs,
  'a-table': { name: 'ATable', template: '<div class="table-stub" />' },
  'a-table-column': { name: 'ATableColumn', template: '<div />' },
  'a-card': silentStub('a-card-stub'),
  'a-breadcrumb': silentStub('a-breadcrumb-stub'),
  'a-breadcrumb-item': silentStub('a-breadcrumb-item-stub'),
  'a-modal': { name: 'AModal', template: '<div class="modal-stub"><slot /></div>' },
  SpecialistResultView: true, AgentResultPanel: true, CodeReferenceModal: true
}

async function start() {
  const wrapper = mount(ReportCreate, { global: { stubs } })
  await flushPromises()
  return wrapper
}

function state(wrapper: any) {
  return wrapper.vm.$.setupState as any
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(projectsApi.list).mockResolvedValue({ items: [{ id: 'project-1', name: '订单服务' } as any], nextCursor: null, hasMore: false })
  vi.mocked(modelsApi.list).mockResolvedValue({ items: [{ id: 'model-1', name: '默认', modelName: 'deepseek', contextWindow: 100000, validationStatus: 'PASSED' } as any], nextCursor: null, hasMore: false })
  vi.mocked(reportsApi.candidates).mockResolvedValue({ items: [candidate], nextCursor: null, hasMore: false })
  vi.mocked(reportsApi.freezeInputs).mockResolvedValue({ reportId: 'report-1', title: '报告', state: 'INPUT_FROZEN', createdAt: '', sourceCount: 1 })
  vi.mocked(reportsApi.start).mockResolvedValue({ id: 'report-1', state: 'QUEUED' } as any)
})

describe('报告生成表单', () => {
  it('主表单只保留材料摘要，弹窗确认才回填，取消保留原选择', async () => {
    const wrapper = await start(); const s = state(wrapper)
    expect(reportsApi.candidates).not.toHaveBeenCalled()
    s.selected.set(candidate.resultId, candidate)
    const trigger = wrapper.findAll('a-button-stub').find(button => button.text() === '选择审查结果')!
    await trigger.trigger('click'); await flushPromises()
    const picker = wrapper.findComponent(ReportResultPickerModal)
    const draft = state(picker)
    const another = { ...candidate, resultId: 'result-2', path: 'B.java' }
    draft.toggle(another)
    expect(s.selected.size).toBe(1)
    draft.close(); await flushPromises()
    expect(s.selected.size).toBe(1)
    await trigger.trigger('click'); await flushPromises()
    expect(draft.draft.has('result-2')).toBe(false)
    draft.toggle(another); draft.confirm(); await flushPromises()
    expect(s.selected.size).toBe(2)
    expect(s.pickerOpen).toBe(false)
    s.remove('result-1')
    expect(s.selected.size).toBe(1)
    expect(wrapper.text()).toContain('B.java')
    wrapper.unmount()
  })

  it('冻结期间重复提交只生成一次，模型和要求固定为提交时输入', async () => {
    const wrapper = await start()
    const s = state(wrapper)
    s.selected.set(candidate.resultId, candidate)
    s.form.modelConfigId = 'model-1'
    s.form.instruction = '原始要求'
    let resolve!: (value: any) => void
    vi.mocked(reportsApi.freezeInputs).mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    const pending = s.submit()
    s.form.modelConfigId = 'model-2'
    s.form.instruction = '后续编辑'
    await s.submit()
    expect(reportsApi.freezeInputs).toHaveBeenCalledTimes(1)
    resolve({ reportId: 'report-1' })
    await pending
    expect(reportsApi.start).toHaveBeenCalledWith('report-1', { modelConfigId: 'model-1', instruction: '原始要求' })
    wrapper.unmount()
  })

  it('离开表单后迟到的受理响应不强行跳回报告页', async () => {
    const wrapper = await start()
    const s = state(wrapper)
    s.selected.set(candidate.resultId, candidate)
    let resolve!: (value: any) => void
    vi.mocked(reportsApi.start).mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    const pending = s.submit()
    await flushPromises()
    wrapper.unmount()
    resolve({ id: 'report-1' })
    await pending
    expect(context.push).not.toHaveBeenCalled()
  })
  it('先冻结所选来源，再选择模型发起生成', async () => {
    const wrapper = await start()
    const s = state(wrapper)
    s.selected.set(candidate.resultId, candidate)
    s.form.title = '订单报告'
    s.form.modelConfigId = 'model-1'
    await s.submit()
    await flushPromises()

    expect(reportsApi.freezeInputs).toHaveBeenCalledWith({ title: '订单报告', resultIds: ['result-1'] })
    expect(reportsApi.start).toHaveBeenCalledWith('report-1', { modelConfigId: 'model-1', instruction: undefined })
    expect(context.push).toHaveBeenCalledWith('/reports/report-1')
    wrapper.unmount()
  })

  it('冻结失败时保留选材与表单，不发起生成', async () => {
    vi.mocked(reportsApi.freezeInputs).mockRejectedValueOnce(new Error('来源已被删除'))
    const wrapper = await start()
    const s = state(wrapper)
    s.selected.set(candidate.resultId, candidate)
    s.form.title = '订单报告'
    s.form.modelConfigId = 'model-1'
    await s.submit()
    await flushPromises()

    expect(s.selected.size).toBe(1)
    expect(s.form.title).toBe('订单报告')
    expect(s.formError).toBe('来源已被删除')
    expect(reportsApi.start).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('生成受理失败时进入已冻结报告，保留材料', async () => {
    vi.mocked(reportsApi.start).mockRejectedValueOnce(new Error('模型不可用'))
    const wrapper = await start()
    const s = state(wrapper)
    s.selected.set(candidate.resultId, candidate)
    s.form.title = '订单报告'
    s.form.modelConfigId = 'model-1'
    await s.submit()
    await flushPromises()

    expect(context.push).toHaveBeenCalledWith({ path: '/reports/report-1', query: { startFailed: '1' } })
    wrapper.unmount()
  })

  it('目标只显示文件名，查看结果走已有结果接口', async () => {
    const wrapper = await start()
    const s = state(wrapper)
    const row = { ...candidate, path: 'src/main/java/Order.java', oldPath: 'src/Order.java', taskName: '支付重试', targetId: 'target-1' }
    expect(s.fileName(row.path)).toBe('Order.java')
    expect(s.pathTitle(row)).toContain('src/main/java/Order.java')
    vi.mocked(tasksApi.result).mockResolvedValue({ id: 'r', targetId: 'target-1', kind: 'ISSUE', body: { summary: '有风险' } } as any)
    await s.openResult(row)
    expect(tasksApi.result).toHaveBeenCalledWith('task-1', 'target-1')
    expect(s.resultOpen).toBe(true)
    expect(s.resultView.body.summary).toBe('有风险')
    wrapper.unmount()
  })
  it('Agent 结果在表单内预览，保留材料不足状态及当前选材', async () => {
    vi.mocked(agentTasksApi.result).mockResolvedValue({ id: 'agent-r', body: {
      answerStatus: 'INSUFFICIENT_EVIDENCE', title: '调查', summary: '材料不足', markdown: '正文', citations: [], unresolved: ['未读取目标']
    } } as any)
    const wrapper = await start(); const s = state(wrapper)
    s.selected.set(candidate.resultId, candidate)
    await s.openResult({ ...candidate, kind: 'AGENT', path: null, targetId: null })
    expect(agentTasksApi.result).toHaveBeenCalledWith('task-1')
    expect(s.agentResult.body.answerStatus).toBe('INSUFFICIENT_EVIDENCE')
    expect(s.selected.size).toBe(1)
    expect(context.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('切换结果或关闭预览后，迟到响应不能覆盖当前内容', async () => {
    let resolveOld!: (value: any) => void
    vi.mocked(tasksApi.result).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce({ id: 'new-result', body: {} } as any)
    const wrapper = await start(); const s = state(wrapper)
    const pending = s.openResult(candidate)
    await s.openResult({ ...candidate, taskId: 'new-task', targetId: 'new-target' })
    resolveOld({ id: 'old-result' }); await pending
    expect(s.resultView.id).toBe('new-result')
    let resolveClosed!: (value: any) => void
    vi.mocked(tasksApi.result).mockReturnValueOnce(new Promise(resolve => { resolveClosed = resolve }))
    const closed = s.openResult(candidate)
    s.resultOpen = false
    resolveClosed({ id: 'closed-result' }); await closed
    expect(s.resultView).toBeNull()
    expect(s.resultLoading).toBe(false)
    wrapper.unmount()
  })

})
