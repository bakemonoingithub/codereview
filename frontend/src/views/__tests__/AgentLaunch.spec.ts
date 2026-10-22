// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AgentLaunch from '@/views/AgentLaunch.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({
  agentTasksApi: { create: vi.fn(), copyInput: vi.fn() },
  projectsApi: { list: vi.fn() },
  modelsApi: { list: vi.fn(), validateAgent: vi.fn() },
  promptsApi: { list: vi.fn() },
  repositoryApi: { branches: vi.fn(), head: vi.fn(), commits: vi.fn() }
}))
import { agentTasksApi, projectsApi, modelsApi, promptsApi, repositoryApi } from '@/api/v2'

const page = (items: unknown[]) => ({ items, nextCursor: null, hasMore: false })
const state = (wrapper: any) => wrapper.vm.$.setupState as any

async function start() {
  const wrapper = mount(AgentLaunch, {
    global: { stubs: { ...namedAntStubs, ScopePickerModal: true } }
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(projectsApi.list).mockResolvedValue(
    page([{ id: 'p1', name: '订单服务', currentBranch: 'main' }]) as any)
  vi.mocked(modelsApi.list).mockResolvedValue(page([
    { id: 'm1', name: 'DeepSeek', modelName: 'deepseek-flash', agentValidationStatus: 'PASSED' },
    { id: 'm2', name: '未验证模型', modelName: 'other', agentValidationStatus: 'NONE' }
  ]) as any)
  vi.mocked(promptsApi.list).mockResolvedValue(page([
    { id: 'g1', name: 'Agent 指导', purpose: 'AGENT' },
    { id: 'g2', name: '专项策略', purpose: 'SPECIALIST' }
  ]) as any)
  vi.mocked(repositoryApi.branches).mockResolvedValue(['main'])
  vi.mocked(repositoryApi.head).mockResolvedValue('head-1')
  vi.mocked(repositoryApi.commits).mockResolvedValue({ commits: [], hasMore: false } as any)
  vi.mocked(agentTasksApi.create).mockResolvedValue({
    id: 'task-1', state: 'QUEUED', route: 'AGENT', detailUrl: '/api/v2/review-tasks/task-1'
  } as any)
})

describe('Agent 发起表单', () => {
  it('只列出已通过 Agent 验证的模型与 AGENT 用途提示词', async () => {
    const wrapper = await start()
    const s = state(wrapper)

    expect(s.agentModels).toHaveLength(1)
    expect(s.agentModels[0].id).toBe('m1')
    expect(s.prompts).toHaveLength(1)
    expect(s.prompts[0].purpose).toBe('AGENT')
    expect(s.form.projectId).toBe('p1')
    expect(s.form.branch).toBe('main')
    expect(s.form.observedSha).toBe('head-1')
    wrapper.unmount()
  })

  it('文件范围提交时携带观察版本与所选路径', async () => {
    const wrapper = await start()
    const s = state(wrapper)

    s.onScopeConfirm([{ path: 'src/A.java' }, { path: 'src/B.java' }])
    s.form.modelConfigId = 'm1'
    s.form.requirements = '检查重试链路'
    await flushPromises()

    expect(s.canSubmit).toBe(true)
    await s.submit()
    await flushPromises()

    expect(agentTasksApi.create).toHaveBeenCalledTimes(1)
    const [body, key] = vi.mocked(agentTasksApi.create).mock.calls[0]
    expect(key).toEqual(expect.any(String))
    expect(body.scope.kind).toBe('FILES')
    expect(body.scope.branch).toBe('main')
    expect(body.scope.observedSha).toBe('head-1')
    expect(body.scope.paths).toEqual(['src/A.java', 'src/B.java'])
    expect(context.push).toHaveBeenCalledWith({ path: '/reviews/task-1', query: { from: '/agents/new' } })
    wrapper.unmount()
  })

  it('提交范围按选择顺序保留，且不使用文件观察版本', async () => {
    const wrapper = await start()
    const s = state(wrapper)

    vi.mocked(repositoryApi.commits).mockResolvedValue({
      commits: [
        { sha: 'c3', message: 'third', author: 'a', date: 'd' },
        { sha: 'c1', message: 'first', author: 'a', date: 'd' }
      ],
      hasMore: false
    } as any)
    s.form.kind = 'COMMITS'
    s.form.modelConfigId = 'm1'
    s.form.requirements = '比较两次提交'
    await s.loadCommits()
    s.toggleCommit('c3')
    s.toggleCommit('c1')
    s.toggleCommit('c3')
    s.toggleCommit('c3')

    expect(s.form.commits).toEqual(['c1', 'c3'])
    await s.submit()
    await flushPromises()

    const [body] = vi.mocked(agentTasksApi.create).mock.calls[0]
    expect(body.scope.kind).toBe('COMMITS')
    expect(body.scope.commits).toEqual(['c1', 'c3'])
    expect(body.scope.branch).toBeUndefined()
    expect(body.scope.observedSha).toBeUndefined()
    wrapper.unmount()
  })

  it('文字范围缺少描述时不能提交', async () => {
    const wrapper = await start()
    const s = state(wrapper)

    s.form.kind = 'TEXT'
    s.form.modelConfigId = 'm1'
    s.form.requirements = '调查支付链路'
    await flushPromises()

    expect(s.canSubmit).toBe(false)
    s.form.description = '订单提交到支付回调'
    await flushPromises()
    expect(s.canSubmit).toBe(true)
    wrapper.unmount()
  })
})
