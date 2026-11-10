// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportCreate from '@/views/ReportCreate.vue'
import ReportResultPickerModal from '@/components/ReportResultPickerModal.vue'
import ReportSourceModal from '@/components/ReportSourceModal.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({
  promptsApi: { list: vi.fn(), detail: vi.fn(), versions: vi.fn() },
  projectsApi: { list: vi.fn() },
  modelsApi: { list: vi.fn() },
  reportsApi: { candidates: vi.fn(), freezeInputs: vi.fn(), start: vi.fn() }
}))

import { modelsApi, projectsApi, promptsApi, reportsApi } from '@/api/v2'

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
  ReportSourceModal: true
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
  vi.mocked(promptsApi.list).mockResolvedValue({ items: [
    { id: 'prompt-report', name: '报告规则', purpose: 'REPORT' },
    { id: 'prompt-agent', name: 'Agent 指导', purpose: 'AGENT' }
  ] as any, nextCursor: null, hasMore: false })
  vi.mocked(promptsApi.detail).mockResolvedValue({
    id: 'prompt-report', name: '报告规则', purpose: 'REPORT', description: null, tags: [], revision: 2,
    currentVersion: { id: 'version-2', versionNo: 2, content: '当前正文', createdAt: '2026-10-25T00:00:00' },
    createdAt: '', updatedAt: ''
  } as any)
  vi.mocked(promptsApi.versions).mockResolvedValue([
    { id: 'version-2', versionNo: 2, content: '当前正文', createdAt: '2026-10-25T00:00:00' },
    { id: 'version-1', versionNo: 1, content: '历史正文', createdAt: '2026-10-24T00:00:00' }
  ] as any)
  vi.mocked(reportsApi.candidates).mockResolvedValue({ items: [candidate], nextCursor: null, hasMore: false })
  vi.mocked(reportsApi.freezeInputs).mockResolvedValue({ reportId: 'report-1', title: '报告', state: 'INPUT_FROZEN', createdAt: '', sourceCount: 1 })
  vi.mocked(reportsApi.start).mockResolvedValue({ id: 'report-1', state: 'QUEUED' } as any)
})

describe('报告生成表单', () => {
  it.each(['REPORT', 'COMMON'])('%s 只列报告用途规则，并提交用户选择的历史版本', async (purpose) => {
    vi.mocked(promptsApi.list).mockResolvedValue({ items: [{ id: 'prompt-report', name: '报告规则', purpose }] as any, hasMore: false, nextCursor: null })
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'prompt-report', name: '报告规则', purpose, tags: [], currentVersion: { id: 'version-2', versionNo: 2, content: '当前正文' } } as any)
    const wrapper = await start()
    const s = state(wrapper)
    s.selected.set(candidate.resultId, candidate)
    s.form.title = '报告'
    s.form.modelConfigId = 'model-1'
    s.form.promptId = 'prompt-report'
    await flushPromises()
    s.form.promptVersionId = 'version-1'

    expect(s.promptOptions).toEqual([{ value: 'prompt-report', label: '报告规则' }])
    expect(s.selectedPromptVersion.content).toBe('历史正文')
    await s.submit()
    await flushPromises()

    expect(reportsApi.start).toHaveBeenCalledWith('report-1', {
      modelConfigId: 'model-1', instruction: undefined,
      promptId: 'prompt-report', promptVersionId: 'version-1'
    })
    wrapper.unmount()
  })

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
    s.form.title = '报告'
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
    s.form.title = '报告'
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

  it('选材和已选材料都用共用弹窗查看结果，预览关闭后保留勾选', async () => {
    const row = { ...candidate, path: 'src/main/java/Order.java', oldPath: 'src/Order.java', taskName: '支付重试' }
    vi.mocked(reportsApi.candidates).mockResolvedValue({ items: [row], nextCursor: null, hasMore: false })
    const wrapper = await start()
    const s = state(wrapper)
    expect(s.fileName(row.path)).toBe('Order.java')
    expect(s.pathTitle(row)).toContain('src/main/java/Order.java')
    await wrapper.findAll('a-button-stub').find(button => button.text() === '选择审查结果')!.trigger('click')
    await flushPromises()
    const picker = wrapper.getComponent(ReportResultPickerModal)
    await picker.get('.result-main').trigger('click')
    await picker.findAll('a-button-stub').find(button => button.text() === '查看结果')!.trigger('click')
    const preview = wrapper.getComponent(ReportSourceModal)
    expect(preview.props()).toMatchObject({ open: true, candidate: row })
    expect(picker.get('.result-main').attributes('aria-pressed')).toBe('true')
    expect(s.selected.size).toBe(0)

    preview.vm.$emit('update:open', false)
    await flushPromises()
    expect(picker.props('open')).toBe(true)
    expect(picker.get('.result-main').attributes('aria-pressed')).toBe('true')
    await picker.findAll('a-button-stub').find(button => button.text() === '确定')!.trigger('click')
    expect(s.selected.size).toBe(1)
    expect(picker.props('open')).toBe(false)
    const selected = wrapper.get('.selected-item')
    expect(selected.get('.file-name').text()).toBe('Order.java')
    await selected.findAll('a-button-stub').find(button => button.text() === '查看结果')!.trigger('click')
    expect(wrapper.getComponent(ReportSourceModal).props()).toMatchObject({ open: true, candidate: row })
    expect(wrapper.findAllComponents(ReportSourceModal)).toHaveLength(1)
    expect(reportsApi.freezeInputs).not.toHaveBeenCalled()
    expect(context.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

})

function formItem(wrapper: any, label: string) {
  return wrapper.findAll('a-form-item-stub').find((item: any) => item.attributes('label') === label)
}

describe('发起弹窗骨架', () => {
  it('三分区与页脚统一，星号只来自 required', async () => {
    const wrapper = await start()
    expect(wrapper.findComponent({ name: 'LaunchModalShell' }).props('title')).toBe('生成报告')
    expect(wrapper.text()).toContain('1. 基本信息')
    expect(wrapper.text()).toContain('2. 审查结果')
    expect(wrapper.text()).toContain('3. 要求')
    expect(wrapper.text()).toContain('选择审查结果')
    const bar = wrapper.find('.submit-bar')
    expect(bar.text().indexOf('取消')).toBeGreaterThanOrEqual(0)
    expect(bar.text().indexOf('取消')).toBeLessThan(bar.text().indexOf('冻结材料并生成'))
    expect(wrapper.html()).not.toContain('class="required"')
    expect(wrapper.html()).not.toContain('>必填<')
    for (const label of ['报告名称', '模型']) {
      expect(formItem(wrapper, label)?.attributes('required'), label).toBe('')
    }
    expect(formItem(wrapper, '规则版本')).toBeUndefined()
    expect(formItem(wrapper, '报告名称')?.text()).not.toContain('（可选）')
    const optional = wrapper.findAll('a-form-item-stub').filter((item) => item.text().includes('（可选）'))
    expect(optional.map((item) => item.text())).toEqual(expect.arrayContaining([
      expect.stringContaining('通用规则'),
      expect.stringContaining('本次要求')
    ]))
    optional.forEach((item) => {
      expect(item.attributes('required')).toBeUndefined()
      expect(item.attributes('label')).toBeUndefined()
    })
    expect(wrapper.html()).toContain('不使用通用规则')
    expect(wrapper.html()).not.toContain('选择报告规则')
    const instruction = wrapper.findComponent({ name: 'ATextarea' })
    expect(instruction.attributes('maxlength')).toBe('4000')
    expect(instruction.attributes('rows')).toBeUndefined()
    expect(instruction.attributes('show-count')).toBe('')
    expect(instruction.vm.$.vnode.props['auto-size']).toEqual({ minRows: 4, maxRows: 8 })
    const s = state(wrapper)
    s.form.promptId = 'prompt-report'
    await flushPromises()
    expect(formItem(wrapper, '规则版本')?.attributes('required')).toBe('')
    expect(wrapper.findComponent({ name: 'PromptInfoPreview' }).exists()).toBe(true)
    expect(wrapper.text()).not.toContain('所选报告规则正文')
    wrapper.unmount()
  })
  it('分页读取通用规则，详情用途已变更时阻止生成', async () => {
    vi.mocked(promptsApi.list).mockResolvedValueOnce({ items: [
      { id: 'agent', purpose: 'AGENT' }, { id: 'specialist', purpose: 'SPECIALIST' }
    ] as any, hasMore: true, nextCursor: 'next' }).mockResolvedValueOnce({ items: [
      { id: 'common', name: '通用规则', purpose: 'COMMON' }
    ] as any, hasMore: false, nextCursor: null })
    const wrapper = await start(); const s = state(wrapper)
    expect(s.promptOptions).toEqual([{ value: 'common', label: '通用规则' }])
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'common', purpose: 'AGENT' } as any)
    s.form.promptId = 'common'
    await flushPromises()
    expect(s.promptError).toContain('用途')
    await s.submit()
    expect(reportsApi.start).not.toHaveBeenCalled()
    wrapper.unmount()
  })

})
