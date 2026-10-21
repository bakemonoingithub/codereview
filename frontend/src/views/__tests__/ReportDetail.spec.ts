// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportDetail from '@/views/ReportDetail.vue'
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
  modelsApi: { list: vi.fn() }
}))
vi.mock('@/components/ReportMarkdown.vue', () => ({
  default: { props: ['text'], template: '<div class="report-body-stub">{{ text }}</div>' }
}))

import { modelsApi, reportsApi } from '@/api/v2'

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
  startedAt: '2026-10-21T10:01:00', finishedAt: '2026-10-21T10:05:00'
}

const stubs = {
  ...namedAntStubs,
  'a-card': silentStub('a-card-stub'),
  'a-modal': silentStub('a-modal-stub'),
  ReportSourceDrawer: { props: ['open', 'reportId', 'ordinal'], template: '<div class="drawer-stub" />' },
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
  context.route = { params: { id: 'report-1' }, query: {} }
  vi.mocked(reportsApi.detail).mockResolvedValue(report as any)
  vi.mocked(reportsApi.download).mockResolvedValue(new Blob(['# 结论']))
  vi.mocked(modelsApi.list).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
})

describe('报告详情阅读', () => {
  it('展示冻结来源与正文，引用可打开对应来源', async () => {
    const wrapper = await start()
    const s = state(wrapper)

    expect(wrapper.text()).toContain('订单报告')
    expect(wrapper.text()).toContain('重试可能重复扣款')
    expect(wrapper.find('.report-body-stub').text()).toContain('[S1]')
    s.openSource(1)
    expect(s.sourceOpen).toBe(true)
    expect(s.sourceOrdinal).toBe(1)
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
})
