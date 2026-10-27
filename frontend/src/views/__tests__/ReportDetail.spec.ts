// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import ReportDetail from '@/views/ReportDetail.vue'
import ReportMarkdown from '@/components/ReportMarkdown.vue'
import ReportSourceModal from '@/components/ReportSourceModal.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({
  route: { params: { id: 'report-1' }, query: {} } as any,
  push: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute: () => context.route,
  useRouter: () => ({ push: context.push })
}))
vi.mock('@/api/v2', () => ({
  reportsApi: { detail: vi.fn(), start: vi.fn(), download: vi.fn() },
  modelsApi: { list: vi.fn() },
  promptsApi: { list: vi.fn(), detail: vi.fn(), versions: vi.fn() }
}))
vi.mock('@/components/ReportMarkdown.vue', () => ({
  default: { props: ['text', 'sourceCount'], emits: ['citation'], template: '<div class="report-body-stub">{{ text }}</div>' }
}))

import { modelsApi, promptsApi, reportsApi } from '@/api/v2'

const source = {
  ordinal: 1, resultId: 'result-1', kind: 'ISSUE', projectId: 'project-1',
  projectName: '订单服务', taskId: 'task-1', taskRoute: 'SPECIALIST', path: 'A.java',
  headSha: 'abcdef123456', baseSha: null, summary: '重试可能重复扣款', issueCount: 1,
  accurate: 1, inaccurate: 0, unknown: 0, unassessed: 0
}
const report = {
  id: 'report-1', title: '订单报告', state: 'SUCCEEDED', body: '# 结论\n\n重试风险 [S1]。',
  errorCode: null, errorMessage: null, modelName: '默认', sourceCount: 1, taskCount: 1,
  versionCount: 1, kindCounts: { ISSUE: 1 }, sources: [source], calls: [],
  model: { id: 'model-1', name: '默认', modelName: 'qwen', contextWindow: 32000, maxOutput: 4096, tokenizer: 'qwen', protocol: 'OPENAI_CHAT', revision: 1 },
  instruction: null, createdAt: '2026-10-21T10:00:00', updatedAt: '2026-10-21T10:05:00',
  prompt: null,
  startedAt: '2026-10-21T10:01:00', finishedAt: '2026-10-21T10:05:00'
}

const stubs = {
  ...namedAntStubs,
  'a-card': silentStub('a-card-stub'),
  'a-modal': silentStub('a-modal-stub'),
  ReportSourceModal: { props: ['open', 'reportId', 'ordinal'], template: '<div class="source-modal-stub" />' },
  CallReaderModal: { props: ['open', 'callId'], template: '<div class="call-stub" />' }
}

async function start() {
  const wrapper = mount(ReportDetail, { global: { stubs } })
  await flushPromises()
  return wrapper
}

function state(wrapper: any) {
  return wrapper.vm.$.setupState as any
}

beforeEach(() => {
  vi.resetAllMocks()
  context.route = reactive({ params: { id: 'report-1' }, query: {} })
  vi.mocked(reportsApi.detail).mockResolvedValue(report as any)
  vi.mocked(reportsApi.download).mockResolvedValue(new Blob(['# 结论']))
  vi.mocked(modelsApi.list).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
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
})

describe('报告详情阅读', () => {
  it('切换报告立即清空旧正文、来源与生成表单，失败不显示旧报告', async () => {
    const wrapper = await start()
    const s = state(wrapper)
    s.openSource(1)
    s.openCall('old-call')
    s.generateOpen = true
    s.generateForm.instruction = '旧要求'
    vi.mocked(reportsApi.detail).mockRejectedValueOnce(new Error('新报告读取失败'))
    context.route.params.id = 'report-2'
    await nextTick()
    expect(s.report).toBeNull()
    expect(s.sourceOpen).toBe(false)
    expect(s.callOpen).toBe(false)
    expect(s.generateOpen).toBe(false)
    expect(s.generateForm.instruction).toBe('')
    await flushPromises()
    expect(wrapper.text()).not.toContain('订单报告')
    expect(s.error).toBe('新报告读取失败')
    wrapper.unmount()
  })

  it('旧报告生成的迟到错误不污染新报告，重复点击只发起一次', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report, state: 'INPUT_FROZEN' } as any)
    const wrapper = await start()
    const s = state(wrapper)
    s.generateForm.modelConfigId = 'model-1'
    let reject!: (reason: Error) => void
    vi.mocked(reportsApi.start).mockImplementationOnce(() => new Promise((_done, fail) => { reject = fail }))
    const pending = s.startGeneration()
    await s.startGeneration()
    expect(reportsApi.start).toHaveBeenCalledTimes(1)
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report, id: 'report-2' } as any)
    context.route.params.id = 'report-2'
    await flushPromises()
    reject(new Error('旧报告错误'))
    await pending
    expect(s.generateError).toBe('')
    expect(s.report.id).toBe('report-2')
    wrapper.unmount()
  })
  it('展示冻结来源与正文，引用可打开对应来源', async () => {
    const wrapper = await start()
    const s = state(wrapper)

    expect(wrapper.text()).toContain('订单报告')
    expect(wrapper.find('.source-card').text()).toContain('A.java')
    expect(wrapper.find('.report-body-stub').text()).toContain('[S1]')
    wrapper.findComponent(ReportMarkdown).vm.$emit('citation', 1)
    expect(s.sourceOpen).toBe(true)
    expect(s.sourceOrdinal).toBe(1)
    wrapper.unmount()
  })

  it('默认阅读报告正文，输入与调用独立展示，标题下只保留项目和类型', async () => {
    const wrapper = await start()
    expect(state(wrapper).activeTab).toBe('result')
    expect(wrapper.find('.report-body').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.find('.report-input').attributes('style')).toContain('display: none')
    expect(wrapper.find('.calls').attributes('style')).toContain('display: none')
    expect(wrapper.find('.task-metadata').text()).toBe('订单服务 · 审查报告')
    expect(wrapper.find('.config-card').exists()).toBe(false)
    expect(wrapper.find('.overview').exists()).toBe(false)

    await wrapper.findAll('.tabbar button')[1].trigger('click')
    expect(wrapper.find('.report-input').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.find('.report-body').attributes('style')).toContain('display: none')
    expect(wrapper.findAll('.input-section h3').map(heading => heading.text())).toEqual([
      '任务配置', '报告规则', '报告材料', '任务信息'
    ])
    const info = wrapper.find('[aria-label="任务信息"]')
    expect(info.text()).toContain('report-1')
    expect(info.text()).toContain('创建与结束')
    expect(info.text()).toContain('模型配置修订')
    expect(info.find('details').exists()).toBe(false)
    expect(wrapper.find('[aria-label="报告材料"]').text()).toContain('1 个审查结果 · 1 个来源任务 · 1 个源码版本')
    wrapper.unmount()
  })

  it('输入中使用保存的规则与模型，不读取当前配置，规则正文默认收起', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report,
      instruction: '优先说明支付风险\n保留来源引用',
      prompt: { id: 'old-rule', name: '生成时规则', versionId: 'old-version', versionNo: 3, content: '生成时保存的正文' }
    } as any)
    const wrapper = await start()
    await wrapper.findAll('.tabbar button')[1].trigger('click')
    const rules = wrapper.find('[aria-label="报告规则"]')
    expect(rules.text()).toContain('生成时规则 · v3')
    expect(rules.find('.input-text').text()).toBe('优先说明支付风险\n保留来源引用')
    expect(rules.find('details').attributes('open')).toBeUndefined()
    expect(rules.find('pre').text()).toBe('生成时保存的正文')
    expect(wrapper.find('[aria-label="任务配置"]').text()).toContain('默认')
    expect(promptsApi.detail).not.toHaveBeenCalled()
    expect(modelsApi.list).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('混合来源只在共用侧栏列出，文件显示路径，Agent 显示摘要', async () => {
    const headSha = 'a'.repeat(40)
    const baseSha = 'b'.repeat(40)
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report,
      sourceCount: 2, taskCount: 2, versionCount: 2, kindCounts: { ISSUE: 1, AGENT: 1 },
      sources: [{ ...source, headSha, baseSha }, { ...source, ordinal: 2, resultId: 'agent-result', kind: 'AGENT', path: null, headSha: null, baseSha: null }]
    } as any)
    const wrapper = await start()
    await wrapper.findAll('.tabbar button')[1].trigger('click')
    const materials = wrapper.find('[aria-label="报告材料"]')
    expect(materials.find('.material-list').exists()).toBe(false)
    expect(materials.text()).toContain('2 个审查结果 · 2 个来源任务 · 2 个源码版本')
    expect(materials.text()).toContain('「报告来源」')
    const rows = wrapper.findAll('.source-card')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('A.java')
    expect(rows[0].text()).not.toContain('准确')
    expect(rows[0].text()).not.toContain(headSha)
    expect(rows[1].text()).toContain('重试可能重复扣款')
    expect(rows[1].text()).toContain('Agent 分析')
    for (const tab of wrapper.findAll('.tabbar button')) {
      await tab.trigger('click')
      expect(wrapper.findAll('.source-card')).toHaveLength(2)
    }
    await rows[1].trigger('click')
    expect(state(wrapper).sourceOrdinal).toBe(2)
    expect(state(wrapper).sourceOpen).toBe(true)
    expect(wrapper.findComponent(ReportSourceModal).props('reportId')).toBe('report-1')
    wrapper.unmount()
  })

  it('区分待生成时尚未选择配置和已生成但未使用规则，调用无记录时有说明', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report,
      state: 'INPUT_FROZEN', body: null, model: null, instruction: null, prompt: null, finishedAt: null
    } as any)
    const wrapper = await start()
    expect(wrapper.find('[aria-label="任务配置"]').text()).toContain('尚未选择')
    expect(wrapper.find('[aria-label="报告规则"]').text()).toContain('尚未设置')
    expect(wrapper.find('[aria-label="报告规则"]').text()).not.toContain('未使用通用规则')
    expect(wrapper.find('[aria-label="任务信息"]').text()).toContain('尚未开始生成')
    await wrapper.findAll('.tabbar button')[2].trigger('click')
    expect(wrapper.find('.calls').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.find('.calls').text()).toContain('暂无已保存的模型调用记录')

    vi.mocked(reportsApi.detail).mockResolvedValue(report as any)
    await state(wrapper).load()
    await flushPromises()
    expect(state(wrapper).activeTab).toBe('calls')
    expect(wrapper.find('[aria-label="报告规则"]').text()).toContain('未使用通用规则')
    expect(wrapper.find('[aria-label="报告规则"]').text()).toContain('未填写')
    expect(wrapper.find('[aria-label="报告规则"]').find('details').exists()).toBe(false)
    wrapper.unmount()
  })

  it('实际调用沿用完整请求与返回阅读入口，错误、状态和尝试顺序可见', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report, calls: [
      { id: 'call-1', state: 'FAILED', attempt: 1, errorCode: 'TIMEOUT', errorMessage: '请求超时', createdAt: '2026-10-21T10:01:00' },
      { id: 'call-2', state: 'SUCCEEDED', attempt: 2, errorCode: null, errorMessage: null, createdAt: '2026-10-21T10:02:00' }
    ] } as any)
    const wrapper = await start()
    await wrapper.findAll('.tabbar button')[2].trigger('click')
    const rows = wrapper.findAll('.call-row')
    expect(rows[0].text()).toContain('失败')
    expect(rows[0].text()).toContain('第 1 次传输尝试')
    expect(rows[0].text()).toContain('TIMEOUT')
    expect(rows[0].text()).toContain('请求超时')
    expect(rows[1].text()).toContain('成功')
    expect(wrapper.find('.report-body').find('.call-row').exists()).toBe(false)
    await rows[1].find('a-button-stub').trigger('click')
    expect(state(wrapper).callId).toBe('call-2')
    expect(state(wrapper).callOpen).toBe(true)
    wrapper.unmount()
  })

  it('失败页可跳到实际调用，切换报告后恢复正文 tab', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report, state: 'FAILED', body: null } as any)
    const wrapper = await start()
    const jump = wrapper.findAll('.report-body a-button-stub').find(button => button.text() === '查看实际调用')!
    await jump.trigger('click')
    expect(state(wrapper).activeTab).toBe('calls')
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report, id: 'report-2' } as any)
    context.route.params.id = 'report-2'
    await flushPromises()
    expect(state(wrapper).activeTab).toBe('result')
    expect(wrapper.find('.report-body').attributes('style') || '').not.toContain('display: none')
    wrapper.unmount()
  })

  it('详情展示生成时冻结的报告规则正文', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({
      ...report,
      prompt: { id: 'prompt-report', name: '报告规则', versionId: 'version-1', versionNo: 1, content: '冻结规则正文' },
      instruction: '本次报告要求'
    } as any)
    const wrapper = await start()

    expect(wrapper.text()).toContain('报告规则 · v1')
    expect(wrapper.text()).toContain('冻结规则正文')
    expect(wrapper.text()).toContain('本次报告要求')
    wrapper.unmount()
  })

  it('失败状态明确展示错误，不显示成功正文入口', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({
      ...report, state: 'FAILED', body: null, errorCode: 'CONTEXT_LIMIT',
      errorMessage: '完整请求超过模型上下文窗口，未发送'
    } as any)
    const wrapper = await start()

    expect(wrapper.text()).toContain('CONTEXT_LIMIT')
    expect(wrapper.text()).toContain('完整请求超过模型上下文窗口')
    expect(wrapper.text()).not.toContain('下载 Markdown')
    wrapper.unmount()
  })

  it('待生成报告可选择模型继续生成', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report, state: 'INPUT_FROZEN', body: null } as any)
    const wrapper = await start()
    const s = state(wrapper)
    s.generateForm.modelConfigId = 'model-1'
    s.generateOpen = true
    await s.startGeneration()
    await flushPromises()

    expect(reportsApi.start).toHaveBeenCalledWith('report-1', { modelConfigId: 'model-1', instruction: undefined })
    expect(s.generateOpen).toBe(false)
    wrapper.unmount()
  })

  it('生成时提交所选报告规则的具体版本', async () => {
    vi.mocked(reportsApi.detail).mockResolvedValue({ ...report, state: 'INPUT_FROZEN', body: null } as any)
    vi.mocked(modelsApi.list).mockResolvedValue({ items: [{ id: 'model-1', name: '默认', modelName: 'qwen', contextWindow: 32000 } as any], nextCursor: null, hasMore: false })
    const wrapper = await start()
    const s = state(wrapper)
    await s.openGenerate()
    s.generateForm.modelConfigId = 'model-1'
    s.generateForm.promptId = 'prompt-report'
    await flushPromises()
    s.generateForm.promptVersionId = 'version-1'
    await s.startGeneration()
    await flushPromises()

    expect(reportsApi.start).toHaveBeenCalledWith('report-1', {
      modelConfigId: 'model-1', instruction: undefined,
      promptId: 'prompt-report', promptVersionId: 'version-1'
    })
    wrapper.unmount()
  })
})
