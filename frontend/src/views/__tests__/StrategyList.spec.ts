// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { message } from 'ant-design-vue'
import StrategyList from '@/views/StrategyList.vue'
import PromptVersionSelect from '@/components/PromptVersionSelect.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({
  strategiesApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn()
  },
  modelsApi: { list: vi.fn() },
  promptsApi: { list: vi.fn(), detail: vi.fn(), versions: vi.fn() }
}))

import { modelsApi, promptsApi, strategiesApi } from '@/api/v2'

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-modal': { ...attrsStub('a-modal-stub'), name: 'AModal' },
      'a-table': false,
      'a-table-column': false
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(promptsApi.versions).mockResolvedValue([])
  ;(strategiesApi.list as any).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  ;(modelsApi.list as any).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  ;(promptsApi.list as any).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
})

async function mountList() {
  const wrapper = mount(StrategyList, options)
  await flush()
  return wrapper
}

describe('StrategyList', () => {
  it('无策略时给出空态引导', async () => {
    const wrapper = await mountList()

    expect(wrapper.text()).toContain('还没有审查策略')
    expect(wrapper.text()).toContain('专项类型 + 模型')
  })

  it('展示专项类型与模型名称', async () => {
    ;(strategiesApi.list as any).mockResolvedValue({
      items: [
        {
          id: 's1',
          name: '订单规范',
          specialistType: 'ISSUE',
          modelConfigId: 'm1',
          promptId: 'p1',
          revision: 1,
          createdAt: '2026-10-20T10:00:00',
          updatedAt: '2026-10-20T10:00:00'
        }
      ],
      nextCursor: null,
      hasMore: false
    })
    ;(modelsApi.list as any).mockResolvedValue({
      items: [{ id: 'm1', name: '默认模型' }],
      nextCursor: null,
      hasMore: false
    })
    ;(promptsApi.list as any).mockResolvedValue({
      items: [{ id: 'p1', name: '订单规则', purpose: 'SPECIALIST' }],
      nextCursor: null,
      hasMore: false
    })

    const wrapper = await mountList()

    expect(wrapper.text()).toContain('问题审查')
    expect(wrapper.text()).toContain('默认模型')
    expect(wrapper.text()).toContain('订单规则')
  })

  it('模型为空时给出创建入口', async () => {
    const wrapper = await mountList()
    const create = wrapper.findAll('a-button-stub').find((b) => b.text().includes('新建策略'))!
    await create.trigger('click')
    await flush()

    expect(wrapper.text()).toContain('暂无可用模型')
    expect(wrapper.text()).toContain('去创建模型')
  })

  it('模型加载失败时说明原因并给出重试入口', async () => {
    ;(modelsApi.list as any).mockRejectedValue(new Error('无法连接服务器'))
    const wrapper = await mountList()
    const create = wrapper.findAll('a-button-stub').find((b) => b.text().includes('新建策略'))!
    await create.trigger('click')
    await flush()

    expect(wrapper.text()).toContain('无法连接服务器')
    expect(wrapper.text()).toContain('去模型页重试')
  })

  it('未选择模型时不提交', async () => {
    const warn = vi.spyOn(message, 'warning').mockImplementation(() => ({}) as any)
    const wrapper = await mountList()
    const create = wrapper.findAll('a-button-stub').find((b) => b.text().includes('新建策略'))!
    await create.trigger('click')
    await flush()

    const modal = wrapper.findComponent({ name: 'AModal' })
    modal.vm.$emit('ok')
    await flush()

    expect(warn).toHaveBeenCalled()
    expect(strategiesApi.create).not.toHaveBeenCalled()
  })
  it('新策略默认固定当前版本，选择历史版本后保存并预览该正文', async () => {
    const versions = [
      { id: 'v2', versionNo: 2, content: '当前规则', createdAt: '2026-10-25T10:00:00' },
      { id: 'v1', versionNo: 1, content: '历史规则', createdAt: '2026-10-24T10:00:00' }
    ]
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'p1', name: '规则', tags: [], currentVersion: versions[0] } as any)
    vi.mocked(promptsApi.versions).mockResolvedValue(versions)
    const wrapper = await mountList()
    const s = (wrapper.vm as any).$.setupState as any
    s.openCreate()
    s.form.name = '固定规则'
    s.form.modelConfigId = 'm1'
    s.form.promptId = 'p1'
    s.onPromptChange('p1')
    await flush()
    expect(s.form.promptVersionId).toBe('v2')
    wrapper.findComponent(PromptVersionSelect).vm.$emit('update:value', 'v1')
    await flush()
    expect(s.selectedPromptVersion.content).toBe('历史规则')
    expect(wrapper.text()).toContain('历史规则')
    expect(wrapper.text()).not.toContain('当前规则')
    await s.onSave()
    expect(strategiesApi.create).toHaveBeenCalledWith(expect.objectContaining({ promptId: 'p1', promptVersionId: 'v1' }))
    wrapper.unmount()
  })

  it('旧策略保留动态版本选择，清空提示词也清空版本', async () => {
    const version = { id: 'v2', versionNo: 2, content: '当前规则', createdAt: '2026-10-25' }
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'p1', name: '规则', tags: [], currentVersion: version } as any)
    vi.mocked(promptsApi.versions).mockResolvedValue([version])
    const wrapper = await mountList()
    const s = (wrapper.vm as any).$.setupState as any
    s.openEdit({ id: 's1', specialistType: 'ISSUE', name: '旧策略', modelConfigId: 'm1', promptId: 'p1', promptVersionId: null, revision: 1 })
    await flush()
    expect(s.form.promptVersionId).toBe('')
    expect(s.allowFollowCurrent).toBe(true)
    await s.onSave()
    expect(strategiesApi.update).toHaveBeenCalledWith('s1', expect.objectContaining({ promptVersionId: undefined }))
    s.form.promptId = undefined
    s.form.promptVersionId = 'v2'
    s.onPromptChange(undefined)
    expect(s.form.promptVersionId).toBeUndefined()
    wrapper.unmount()
  })

  it('编辑失效版本时阻止保存，不自动替换成当前版本', async () => {
    const current = { id: 'v2', versionNo: 2, content: '当前规则', createdAt: '2026-10-25' }
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'p1', name: '规则', tags: [], currentVersion: current } as any)
    vi.mocked(promptsApi.versions).mockResolvedValue([current])
    const wrapper = await mountList()
    const s = (wrapper.vm as any).$.setupState as any
    s.openEdit({ id: 's1', specialistType: 'ISSUE', name: '旧策略', modelConfigId: 'm1', promptId: 'p1', promptVersionId: 'missing', revision: 1 })
    await flush()
    expect(s.form.promptVersionId).toBe('missing')
    expect(s.selectedPromptError).toContain('不可用')
    await s.onSave()
    expect(strategiesApi.update).not.toHaveBeenCalled()
    s.form.promptVersionId = 'v2'
    await flush()
    expect(s.selectedPromptError).toBe('')
    expect(s.selectedPromptVersion.content).toBe('当前规则')
    wrapper.unmount()
  })

  it('切换提示词时忽略旧版本请求的迟到响应', async () => {
    let finishOld!: (value: any) => void
    const old = new Promise<any>(resolve => { finishOld = resolve })
    const fresh = { id: 'v2', versionNo: 2, content: '新的规则', createdAt: '2026-10-25' }
    vi.mocked(promptsApi.detail).mockImplementation(id => id === 'old' ? old : Promise.resolve({ id, tags: [], currentVersion: fresh }) as any)
    vi.mocked(promptsApi.versions).mockResolvedValue([fresh])
    const wrapper = await mountList()
    const s = (wrapper.vm as any).$.setupState as any
    s.openCreate()
    s.form.promptId = 'old'; s.onPromptChange('old')
    s.form.promptId = 'new'; s.onPromptChange('new')
    await flush()
    finishOld({ id: 'old', currentVersion: fresh })
    await flush()
    expect(s.selectedPrompt.id).toBe('new')
    expect(s.form.promptVersionId).toBe('v2')
    wrapper.unmount()
  })

  it('策略列表和关联详情显示固定版本，不借用当前版本正文', async () => {
    const versions = [
      { id: 'v2', versionNo: 2, content: '当前正文', createdAt: '2026-10-25' },
      { id: 'v1', versionNo: 1, content: '固定的历史正文', createdAt: '2026-10-24' }
    ]
    vi.mocked(promptsApi.list).mockResolvedValue({ items: [{ id: 'p1', name: '规则', purpose: 'SPECIALIST' }] } as any)
    vi.mocked(promptsApi.detail).mockResolvedValue({ id: 'p1', name: '规则', tags: [], currentVersion: versions[0] } as any)
    vi.mocked(promptsApi.versions).mockResolvedValue(versions)
    vi.mocked(modelsApi.list).mockResolvedValue({ items: [{ id: 'm1', name: '模型' }] } as any)
    vi.mocked(strategiesApi.list).mockResolvedValue({ items: [{ id: 's1', name: '固定规则', specialistType: 'ISSUE',
      modelConfigId: 'm1', promptId: 'p1', promptVersionId: 'v1' }] } as any)
    const wrapper = await mountList()
    expect(wrapper.text()).toContain('固定 v1')
    await (wrapper.vm as any).$.setupState.openAssociationDetails('prompt', 'p1', 'v1')
    await flush()
    expect(wrapper.text()).toContain('固定的历史正文')
    expect(wrapper.text()).not.toContain('当前正文')
    wrapper.unmount()
  })

})
