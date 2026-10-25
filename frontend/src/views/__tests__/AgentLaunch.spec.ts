// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AgentLaunch from '@/views/AgentLaunch.vue'
import PromptVersionSelect from '@/components/PromptVersionSelect.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

const context = vi.hoisted(() => ({ push: vi.fn(), route: { query: {} as Record<string, string> } }))
vi.mock('vue-router', () => ({
  useRoute: () => context.route,
  useRouter: () => ({ push: context.push })
}))
vi.mock('@/api/v2', () => ({
  agentTasksApi: { create: vi.fn(), copyInput: vi.fn() },
  projectsApi: { list: vi.fn() },
  modelsApi: { list: vi.fn(), validateAgent: vi.fn() },
  promptsApi: { list: vi.fn(), detail: vi.fn(), versions: vi.fn() },
  repositoryApi: { branches: vi.fn(), head: vi.fn(), commits: vi.fn() }
}))
import { agentTasksApi, projectsApi, modelsApi, promptsApi, repositoryApi } from '@/api/v2'

const page = (items: unknown[]) => ({ items, nextCursor: null, hasMore: false })
const state = (wrapper: any) => wrapper.vm.$.setupState as any

async function start() {
  const wrapper = mount(AgentLaunch, {
    global: { stubs: { ...namedAntStubs, ScopePickerModal: true, 'a-modal': { name: 'AModal', template: '<div><slot /></div>' } } }
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  context.route.query = {}
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
  it('选择历史提示词版本时预览并提交该版本，清空时重置版本', async () => {
    const versions = [
      { id: 'v2', versionNo: 2, content: '新版调查', createdAt: '2026-10-25' },
      { id: 'v1', versionNo: 1, content: '旧版调查', createdAt: '2026-10-24' }
    ]
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'g1', name: '指导', tags: [], currentVersion: versions[0] } as any)
    vi.mocked(promptsApi.versions).mockResolvedValue(versions)
    const wrapper = await start(); const s = state(wrapper)
    s.form.guidancePromptId = 'g1'
    await flushPromises()
    expect(s.form.guidancePromptVersionId).toBe('v2')
    wrapper.findComponent(PromptVersionSelect).vm.$emit('update:value', 'v1')
    s.form.paths = ['src/A.java']; s.form.requirements = '调查调用链'
    await flushPromises()
    expect(wrapper.text()).toContain('旧版调查')
    expect(wrapper.text()).not.toContain('新版调查')
    await s.submit()
    expect(agentTasksApi.create).toHaveBeenCalledWith(expect.objectContaining({ guidancePromptVersionId: 'v1' }), expect.any(String))
    s.form.guidancePromptId = undefined
    expect(s.form.guidancePromptVersionId).toBeUndefined()
    expect(s.guidancePromptVersions).toEqual([])
    wrapper.unmount()
  })

  it('复制历史参数时保留版本，版本失效时阻止提交', async () => {
    context.route.query = { copyFrom: 'old-task' }
    const versions = [
      { id: 'v2', versionNo: 2, content: '新版', createdAt: '2026-10-25' },
      { id: 'v1', versionNo: 1, content: '旧版', createdAt: '2026-10-24' }
    ]
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'g1', name: '指导', tags: [], currentVersion: versions[0] } as any)
    vi.mocked(promptsApi.versions).mockResolvedValue(versions)
    vi.mocked(agentTasksApi.copyInput).mockResolvedValue({ projectId: 'p1', modelConfigId: 'm1',
      guidancePromptId: 'g1', guidancePromptVersionId: 'v1', requirements: '原要求',
      scope: { kind: 'FILES', branch: 'main', paths: ['src/A.java'] } } as any)
    const wrapper = await start(); const s = state(wrapper)
    expect(s.form.guidancePromptVersionId).toBe('v1')
    expect(s.selectedGuidanceVersion.content).toBe('旧版')
    expect(s.canSubmit).toBe(true)
    vi.mocked(promptsApi.versions).mockResolvedValue([versions[0]])
    await s.loadGuidancePrompt()
    expect(s.guidancePromptError).toContain('不可用')
    expect(s.form.guidancePromptVersionId).toBe('v1')
    expect(s.canSubmit).toBe(false)
    s.form.guidancePromptVersionId = 'v2'
    await flushPromises()
    expect(s.guidancePromptError).toBe('')
    expect(s.canSubmit).toBe(true)
    wrapper.unmount()
  })

  it('指导提示词加载失败可重试，迟到响应不能覆盖新选择或清空状态', async () => {
    let finishOld!: (value: any) => void
    const old = new Promise<any>(resolve => { finishOld = resolve })
    const version = { id: 'v1', versionNo: 1, content: '规则', createdAt: '2026-10-25' }
    vi.mocked(promptsApi.detail).mockImplementation(id => id === 'old' ? old : Promise.resolve({ id, tags: [], currentVersion: version }) as any)
    vi.mocked(promptsApi.versions).mockResolvedValue([version])
    const wrapper = await start(); const s = state(wrapper)
    s.form.guidancePromptId = 'old'
    s.form.guidancePromptId = 'new'
    await flushPromises()
    expect(s.selectedGuidancePrompt.id).toBe('new')
    s.form.guidancePromptId = undefined
    finishOld({ id: 'old', currentVersion: version })
    await flushPromises()
    expect(s.selectedGuidancePrompt).toBeNull()
    expect(s.form.guidancePromptVersionId).toBeUndefined()
    vi.mocked(promptsApi.versions).mockRejectedValueOnce(new Error('读取版本失败'))
    s.form.guidancePromptId = 'new'
    await flushPromises()
    expect(s.guidancePromptError).toContain('读取版本失败')
    await s.loadGuidancePrompt()
    expect(s.guidancePromptError).toBe('')
    expect(s.form.guidancePromptVersionId).toBe('v1')
    wrapper.unmount()
  })

})
