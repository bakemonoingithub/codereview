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
  it('已选文件按行展示，移除后同步选择器并提交剩余路径', async () => {
    const wrapper = await start(); const s = state(wrapper)
    s.onScopeConfirm([{ path: 'src/A.java' }, { path: 'src/B.java' }])
    s.form.requirements = '检查调用链'
    await flushPromises()
    const rows = wrapper.findAll('.target-row')
    expect(rows.map(row => row.find('code').text())).toEqual(['src/A.java', 'src/B.java'])
    await rows[0].find('a-button-stub').trigger('click')
    expect(s.form.paths).toEqual(['src/B.java'])
    expect(wrapper.findComponent({ name: 'ScopePickerModal' }).props('selected')).toEqual(['src/B.java'])
    await s.submit()
    expect(vi.mocked(agentTasksApi.create).mock.calls[0][0].scope.paths).toEqual(['src/B.java'])
    await wrapper.find('.target-row a-button-stub').trigger('click')
    expect(wrapper.find('.target-list').exists()).toBe(false)
    expect(s.canSubmit).toBe(false)
    wrapper.unmount()
  })

  it('固定版本字段支持重新读取，版本变化时清空文件范围', async () => {
    const wrapper = await start(); const s = state(wrapper)
    s.form.paths = ['src/A.java']
    s.form.requirements = '检查调用链'
    let finish!: (sha: string) => void
    vi.mocked(repositoryApi.head).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const preview = wrapper.findComponent({ name: 'LaunchVersionPreview' })
    expect(formItem(wrapper, '提交hash')?.attributes('required')).toBe('')
    expect(preview.props('refreshText')).toBe('重新读取')
    preview.vm.$emit('refresh')
    await flushPromises()
    expect(s.headLoading).toBe(true)
    expect(s.form.observedSha).toBe('')
    expect(s.canSubmit).toBe(false)
    finish('head-2')
    await flushPromises()
    expect(s.form.observedSha).toBe('head-2')
    expect(s.form.paths).toEqual([])
    expect(s.headMoved).toBe(true)
    s.onScopeConfirm([{ path: 'src/B.java' }])
    expect(s.headMoved).toBe(false)
    wrapper.unmount()
  })

  it('读取失败后重试仍能识别版本变化，版本不变时保留文件', async () => {
    const wrapper = await start(); const s = state(wrapper)
    s.form.paths = ['src/A.java']
    vi.mocked(repositoryApi.head).mockRejectedValueOnce(new Error('网络错误'))
    await s.loadHead()
    expect(s.form.observedSha).toBe('')
    expect(s.headError).toContain('网络错误')
    expect(s.canSubmit).toBe(false)
    vi.mocked(repositoryApi.head).mockResolvedValueOnce('head-1')
    await s.loadHead()
    expect(s.headError).toBe('')
    expect(s.form.paths).toEqual(['src/A.java'])
    vi.mocked(repositoryApi.head).mockRejectedValueOnce(new Error('暂不可用'))
    await s.loadHead()
    vi.mocked(repositoryApi.head).mockResolvedValueOnce('head-2')
    await s.loadHead()
    expect(s.form.paths).toEqual([])
    expect(s.headMoved).toBe(true)
    wrapper.unmount()
  })

  it('迟到的版本读取不能覆盖新分支，文字范围得以保留', async () => {
    const wrapper = await start(); const s = state(wrapper)
    s.form.kind = 'TEXT'
    s.form.description = '支付调用链'
    let finish!: (sha: string) => void
    vi.mocked(repositoryApi.head).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const oldRequest = s.loadHead()
    s.form.branch = 'new-branch'
    vi.mocked(repositoryApi.head).mockResolvedValueOnce('new-head')
    s.onHeadBranchChange()
    await flushPromises()
    finish('old-head')
    await oldRequest
    expect(s.form.observedSha).toBe('new-head')
    expect(s.form.description).toBe('支付调用链')
    expect(s.headLoading).toBe(false)
    wrapper.unmount()
  })

  it('切换分支时立即清空文件范围，即使两个分支指向同一版本', async () => {
    const wrapper = await start(); const s = state(wrapper)
    s.form.paths = ['src/A.java']
    s.form.branch = 'new-branch'
    s.onHeadBranchChange()
    expect(s.form.paths).toEqual([])
    expect(s.headMoved).toBe(true)
    await flushPromises()
    expect(s.form.observedSha).toBe('head-1')
    wrapper.unmount()
  })

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

  it('已选提交按行显示，移除中间项后编号、弹窗和提交顺序一致', async () => {
    const wrapper = await start(); const s = state(wrapper)
    const shas = ['a'.repeat(40), 'b'.repeat(40), 'c'.repeat(40)]
    s.form.kind = 'COMMITS'
    s.form.commits = [...shas]
    s.form.requirements = '比较提交'
    await flushPromises()
    expect(formItem(wrapper, '目标')?.text()).toContain('已选 3 项，最多 20 项')
    const rows = wrapper.findAll('.target-row')
    expect(rows.map(row => row.find('code').text())).toEqual([
      'C1 · aaaaaaaaaa', 'C2 · bbbbbbbbbb', 'C3 · cccccccccc'
    ])
    await rows[1].find('a-button-stub').trigger('click')
    expect(formItem(wrapper, '目标')?.text()).toContain('已选 2 项，最多 20 项')
    expect(wrapper.findAll('.target-row').map(row => row.find('code').text())).toEqual([
      'C1 · aaaaaaaaaa', 'C2 · cccccccccc'
    ])
    await s.openCommitPicker()
    await flushPromises()
    const picker = wrapper.findComponent({ name: 'CommitPickerModal' })
    expect(picker.props('selectedShas')).toEqual([shas[0], shas[2]])
    expect((picker.vm.$.setupState as any).draftShas).toEqual([shas[0], shas[2]])
    s.commitPickerOpen = false
    await s.submit()
    expect(vi.mocked(agentTasksApi.create).mock.calls[0][0].scope.commits).toEqual([shas[0], shas[2]])
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

function formItem(wrapper: any, label: string) {
  return wrapper.findAll('a-form-item-stub').find((item: any) => item.attributes('label') === label)
}

describe('发起弹窗骨架', () => {
  it('三分区与页脚统一，星号只来自 required', async () => {
    const wrapper = await start()
    expect(wrapper.findComponent({ name: 'LaunchModalShell' }).props('title')).toBe('发起 Agent 分析')
    expect(wrapper.findComponent({ name: 'LaunchModalShell' }).props('maskClosable')).toBe(true)
    expect(wrapper.findAll('.launch-section')).toHaveLength(3)
    expect(wrapper.text()).toContain('1. 基本信息')
    expect(wrapper.text()).toContain('2. 范围')
    expect(wrapper.text()).toContain('3. 要求')
    const bar = wrapper.find('.submit-bar')
    expect(bar.text().indexOf('取消')).toBeGreaterThanOrEqual(0)
    expect(bar.text().indexOf('取消')).toBeLessThan(bar.text().indexOf('开始 Agent 分析'))
    expect(wrapper.html()).not.toContain('class="required"')
    expect(wrapper.html()).not.toContain('>必填<')
    for (const label of ['项目', '模型', '范围类型', '分支', '提交hash', '目标', '本次要求']) {
      expect(formItem(wrapper, label)?.attributes('required'), label).toBe('')
    }
    const name = wrapper.findAll('a-form-item-stub').find((item) => item.text().includes('任务名称'))
    expect(name?.attributes('label')).toBeUndefined()
    expect(name?.text()).toContain('任务名称')
    expect(name?.text()).toContain('（可选）')
    expect(name?.attributes('required')).toBeUndefined()
    expect(wrapper.html()).not.toContain('任务名称（可选）')
    expect(wrapper.text()).toContain('文件/目录')
    expect(wrapper.text()).not.toContain('文件／目录')
    expect(wrapper.html()).not.toContain('不选则使用内置调查方法')
    const project = wrapper.findAll('a-select-stub').find((item) => item.attributes('placeholder') === '选择项目')
    expect(project?.attributes('show-search')).toBe('')
    expect(project?.attributes('option-filter-prop')).toBe('label')
    const requirements = wrapper.findComponent({ name: 'ATextarea' })
    expect(requirements.attributes('maxlength')).toBe('4000')
    expect(requirements.attributes('rows')).toBeUndefined()
    expect(requirements.attributes('show-count')).toBe('')
    expect(requirements.vm.$.vnode.props['auto-size']).toEqual({ minRows: 4, maxRows: 8 })
    expect(formItem(wrapper, '提示词版本')).toBeUndefined()
    const guidance = wrapper.findAll('a-form-item-stub').find((item) => item.text().includes('通用规则'))
    expect(guidance?.text()).toContain('（可选）')
    expect(guidance?.attributes('required')).toBeUndefined()
    const s = state(wrapper)
    s.form.guidancePromptId = 'g1'
    await flushPromises()
    expect(formItem(wrapper, '提示词版本')?.attributes('required')).toBe('')
    expect(wrapper.findComponent({ name: 'PromptInfoPreview' }).exists()).toBe(true)
    s.form.kind = 'TEXT'
    await flushPromises()
    expect(formItem(wrapper, '范围描述')?.attributes('required')).toBe('')
    s.form.kind = 'COMMITS'
    await flushPromises()
    expect(formItem(wrapper, '目标')?.attributes('required')).toBe('')
    expect(formItem(wrapper, '分支')?.attributes('required')).toBe('')
    expect(formItem(wrapper, '提交比较')).toBeUndefined()
    expect(wrapper.text()).toContain('提交比较')
    const target = formItem(wrapper, '目标')!
    expect(target.findAll('a-button-stub').some((button: { text: () => string }) => button.text() === '选择提交')).toBe(true)
    expect(target.text()).toContain('已选 0 项，最多 20 项')
    expect(wrapper.html()).toContain('placeholder="选择分支"')
    wrapper.unmount()
  })

  it('复制参数时沿用原标题', async () => {
    context.route.query = { copyFrom: 'old-task' }
    vi.mocked(agentTasksApi.copyInput).mockResolvedValue({ projectId: 'p1', modelConfigId: 'm1',
      requirements: '原要求', scope: { kind: 'FILES', branch: 'main', paths: ['src/A.java'] } } as any)
    const wrapper = await start()
    expect(wrapper.findComponent({ name: 'LaunchModalShell' }).props('title')).toBe('复制参数发起新 Agent 分析')
    const copied = wrapper.findAll('a-alert-stub').map((alert) => alert.attributes('message'))
    expect(copied).toContain('已带入原任务参数。请核对当前分支版本、范围和模型；确认后才会创建新任务。')
    expect(wrapper.html()).not.toContain('确认提交后才会创建新任务')
    wrapper.unmount()
  })
})
