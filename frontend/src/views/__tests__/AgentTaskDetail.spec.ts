// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AgentTaskDetail from '@/views/AgentTaskDetail.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ id: 'task-1', push: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: context.id } }),
  useRouter: () => ({ push: context.push })
}))
vi.mock('@/api/v2', () => ({
  tasksApi: { detail: vi.fn(), cancel: vi.fn() },
  agentTasksApi: {
    progress: vi.fn(),
    toolCalls: vi.fn(),
    toolCall: vi.fn(),
    result: vi.fn()
  }
}))
import { agentTasksApi, tasksApi } from '@/api/v2'

const state = (wrapper: any) => wrapper.vm.$.setupState as any
const page = (items: unknown[], total = items.length) => ({
  items, total, pageNum: 1, pageSize: 20, nextCursor: null, hasMore: false
})

function task(state: string, stopReason: string | null = null) {
  return {
    id: 'task-1', name: 'Agent 调查', projectId: 'p1', route: 'AGENT',
    state, stopReason, sourceKind: 'FILES', branch: 'main',
    headSha: 'b8b0b50e4cce07188debd775ee80f70e13709cf5', revision: 3,
    createdAt: '2026-10-22 17:00:00', deadlineAt: '2026-10-22 17:20:00',
    config: { modelName: 'DeepSeek', promptId: null, promptVersionNo: null }
  }
}

function progress(state: string, stopReason: string | null = null) {
  return {
    taskId: 'task-1', state, stopReason, turnNo: 5, toolAttempts: 7, networkAttempts: 5,
    currentAction: state, covered: { turnNo: 5 }, limits: {
      modelTurns: 48, networkAttempts: 96, toolAttempts: 192, deadlineMinutes: 20,
      outputTokens: 16384, toolSchemaVersion: 'agent-tools-v1'
    },
    updatedAt: '2026-10-22 17:00:05'
  }
}

function result() {
  return {
    id: 'r1', schemaVersion: '1', completeness: 'COMPLETE', createdAt: '2026-10-22 17:00:06',
    body: {
      title: '任务创建入口',
      summary: '幂等键在 TaskService 处理。',
      answerStatus: 'ANSWERED',
      markdown: '## 结论\n\n重试入口重复调用支付接口。[E1]',
      citations: [{
        id: 'E1', materialId: 'm1', path: 'TaskService.java', side: 'FILE',
        sha: 'b8b0b50e', startLine: 96, endLine: 100
      }],
      unresolved: ['支付实现未取得。']
    },
    provenance: { toolCalls: 7 }
  }
}

async function start() {
  const wrapper = mount(AgentTaskDetail, {
    global: {
      stubs: {
        ...namedAntStubs,
        MaterialReaderModal: true,
        MarkovView: true
      }
    }
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  context.id = 'task-1'
  vi.mocked(tasksApi.detail).mockResolvedValue(task('SUCCEEDED') as any)
  vi.mocked(agentTasksApi.progress).mockResolvedValue(progress('SUCCEEDED') as any)
  vi.mocked(agentTasksApi.toolCalls).mockResolvedValue(page([]) as any)
  vi.mocked(agentTasksApi.result).mockResolvedValue(result() as any)
})

describe('Agent 任务详情', () => {
  it('已完成的 Agent 任务展示结果、来源与未解决事项', async () => {
    const wrapper = await start()
    const s = state(wrapper)

    expect(s.result.body.title).toBe('任务创建入口')
    expect(s.result.body.citations[0].path).toBe('TaskService.java')
    expect(s.progress.limits.modelTurns).toBe(48)
    expect(wrapper.text()).toContain('任务创建入口')
    expect(wrapper.text()).toContain('支付实现未取得。')
    wrapper.unmount()
  })

  it('没有完整结果时明确说明只保留调查过程', async () => {
    vi.mocked(tasksApi.detail).mockResolvedValue(task('PARTIAL', 'PROTOCOL_INVALID') as any)
    vi.mocked(agentTasksApi.progress).mockResolvedValue(
      progress('PARTIAL', 'PROTOCOL_INVALID') as any)
    vi.mocked(agentTasksApi.result).mockRejectedValue(new Error('尚未发布完整结果'))

    const wrapper = await start()
    const s = state(wrapper)

    expect(s.result).toBeNull()
    expect(s.resultError).toContain('未发布完整结果')
    expect(s.progress.stopReason).toBe('PROTOCOL_INVALID')
    wrapper.unmount()
  })

  it('耗时和输出各自展示实际用量及上限，输出峰值不与累计量混用', async () => {
    vi.mocked(agentTasksApi.progress).mockResolvedValue({
      ...progress('SUCCEEDED'), elapsedSeconds: 310,
      outputUsage: { totalOutputTokens: 24000, peakOutputTokens: 12000, reportedCalls: 5, unreportedCalls: 0 }
    } as any)
    const wrapper = await start()
    const meters = wrapper.findAll('.agent-meter')
    expect(meters).toHaveLength(5)
    expect(meters[3].text()).toContain('总耗时')
    expect(meters[3].find('strong').text()).toContain('5 分 10 秒')
    expect(meters[3].find('progress').attributes()).toMatchObject({ value: '310', max: '1200' })
    expect(meters[4].find('strong').text()).toContain('12,000')
    expect(meters[4].find('strong').text()).toContain('16,384')
    expect(meters[4].text()).toContain('累计已记录 24,000 Token')
    expect(meters[4].find('progress').attributes()).toMatchObject({ value: '12000', max: '16384' })
    wrapper.unmount()
  })

  it('未返回的用量和缺失的耗时显示未知，不伪装成零', async () => {
    vi.mocked(agentTasksApi.progress).mockResolvedValue({
      ...progress('FAILED'), elapsedSeconds: null,
      outputUsage: { totalOutputTokens: null, peakOutputTokens: null, reportedCalls: 0, unreportedCalls: 2 }
    } as any)
    const wrapper = await start()
    const meters = wrapper.findAll('.agent-meter')
    expect(meters[3].find('strong').text()).toContain('—')
    expect(meters[3].find('progress').attributes('value')).toBeUndefined()
    expect(meters[4].find('strong').text()).toContain('—')
    expect(meters[4].find('progress').attributes('value')).toBeUndefined()
    expect(meters[4].text()).toContain('2 次调用未返回输出用量')
    wrapper.unmount()
  })

  it('运行中的任务通过轮询转为终态并加载结果', async () => {
    vi.useFakeTimers()
    vi.mocked(tasksApi.detail).mockResolvedValue(task('RUNNING') as any)
    vi.mocked(agentTasksApi.progress).mockResolvedValue({ ...progress('RUNNING'), elapsedSeconds: 2 } as any)
    const wrapper = mount(AgentTaskDetail, {
      global: { stubs: { ...namedAntStubs, MaterialReaderModal: true, MarkovView: true } }
    })
    await flushPromises()

    vi.mocked(agentTasksApi.progress).mockResolvedValue({ ...progress('SUCCEEDED'), elapsedSeconds: 6 } as any)
    vi.advanceTimersByTime(2000)
    await flushPromises()

    const s = state(wrapper)
    expect(s.task.state).toBe('SUCCEEDED')
    expect(s.result.body.title).toBe('任务创建入口')
    expect(s.elapsedText).toBe('6 秒')
    const requests = vi.mocked(agentTasksApi.progress).mock.calls.length
    vi.advanceTimersByTime(4000)
    await flushPromises()
    expect(agentTasksApi.progress).toHaveBeenCalledTimes(requests)
    vi.useRealTimers()
    wrapper.unmount()
  })

  it('调用记录分页并打开逐次调用详情', async () => {
    vi.mocked(agentTasksApi.toolCalls).mockResolvedValue(page([
      { id: 'c1', turnNo: 1, ordinal: 1, toolName: 'read_code', status: 'OK',
        startedAt: '2026-10-22 17:00:01', finishedAt: '2026-10-22 17:00:02' },
      { id: 'c2', turnNo: 2, ordinal: 1, toolName: 'submit_result', status: 'REJECTED_BATCH',
        startedAt: '2026-10-22 17:00:03', finishedAt: '2026-10-22 17:00:03' }
    ], 2) as any)
    vi.mocked(agentTasksApi.toolCall).mockImplementation(async (_taskId, callId) => ({
      id: callId, turnNo: callId === 'c1' ? 1 : 2, ordinal: 1,
      toolName: callId === 'c1' ? 'read_code' : 'submit_result',
      status: callId === 'c1' ? 'OK' : 'REJECTED_BATCH',
      providerToolCallId: callId === 'c1' ? 'p1' : 'p2', arguments: '{"version":"V1"}',
      envelope: { status: callId === 'c1' ? 'OK' : 'INVALID_ARGUMENT' },
      materialIds: callId === 'c1' ? ['m1'] : [],
      startedAt: '2026-10-22 17:00:01', finishedAt: '2026-10-22 17:00:02'
    } as any))

    const wrapper = await start()
    const s = state(wrapper)
    expect(s.calls).toHaveLength(2)

    await s.openCall('c1')
    expect(agentTasksApi.toolCall).toHaveBeenCalledWith('task-1', 'c1')
    expect(s.callDetail.toolName).toBe('read_code')
    await s.openCall('c2')
    expect(agentTasksApi.toolCall).toHaveBeenCalledWith('task-1', 'c2')
    expect(wrapper.text()).toContain('整批拒绝（未执行）')
    wrapper.unmount()
  })

  it('取消按钮使用当前修订号', async () => {
    vi.mocked(tasksApi.cancel).mockResolvedValue(task('CANCELLED') as any)
    const wrapper = await start()
    const s = state(wrapper)

    await s.cancel()
    expect(tasksApi.cancel).toHaveBeenCalledWith('task-1', 3)
    expect(s.task.state).toBe('CANCELLED')
    wrapper.unmount()
  })
})
