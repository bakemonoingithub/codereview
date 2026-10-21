// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ProjectList from '@/views/ProjectList.vue'
import ModelConfigList from '@/views/ModelConfigList.vue'
import PromptList from '@/views/PromptList.vue'
import StrategyList from '@/views/StrategyList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({
  projectsApi: { list: vi.fn() }, modelsApi: { list: vi.fn() },
  promptsApi: { list: vi.fn() }, strategiesApi: { list: vi.fn() }
}))
import { projectsApi, modelsApi, promptsApi, strategiesApi } from '@/api/v2'

const options = { global: { stubs: {
  ...namedAntStubs,
  'a-input-search': { ...attrsStub('a-input-search-stub'), name: 'AInputSearch' },
  'a-table': false, 'a-table-column': false, 'a-modal': true, DiffViewer: true
} } }
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))
const page = (name: string, nextCursor: string | null = null) => ({
  items: [{ id: name, name, tags: [], specialistType: 'ISSUE', validationStatus: 'NONE' }],
  nextCursor, hasMore: !!nextCursor
})
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  for (const api of [projectsApi, modelsApi, promptsApi, strategiesApi]) {
    vi.mocked(api.list).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  }
})
afterEach(() => { wrapper?.unmount() })

async function search() {
  const input = wrapper.findComponent({ name: 'AInputSearch' })
  input.vm.$emit('update:value', '新的查询')
  input.vm.$emit('search')
  await flush()
}

describe.each([
  ['项目', ProjectList, projectsApi], ['模型', ModelConfigList, modelsApi],
  ['提示词', PromptList, promptsApi], ['策略', StrategyList, strategiesApi]
] as const)('%s列表请求顺序', (_name, component, api) => {
  it('旧响应不能覆盖新筛选的结果和分页游标', async () => {
    let resolveOld!: (value: unknown) => void
    vi.mocked(api.list).mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve }) as any)
      .mockResolvedValueOnce(page('新结果') as any)
    wrapper = mount(component, options)
    await flush()
    await search()
    resolveOld(page('旧结果', 'old-cursor'))
    await flush()
    expect(wrapper.text()).toContain('新结果')
    expect(wrapper.text()).not.toContain('旧结果')
    expect(wrapper.text()).not.toContain('加载更多')
  })

  it('旧请求的失败不能清除当前请求的加载状态或显示过期错误', async () => {
    let rejectOld!: (reason: Error) => void
    let resolveNew!: (value: unknown) => void
    vi.mocked(api.list).mockReturnValueOnce(new Promise((_resolve, reject) => { rejectOld = reject }) as any)
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve }) as any)
    wrapper = mount(component, options)
    await flush()
    await search()
    rejectOld(new Error('过期错误'))
    await flush()
    expect(wrapper.findComponent({ name: 'ATable' }).props('loading')).toBe(true)
    expect(wrapper.text()).not.toContain('过期错误')
    resolveNew(page('新结果'))
    await flush()
    expect(wrapper.findComponent({ name: 'ATable' }).props('loading')).toBe(false)
    expect(wrapper.text()).toContain('新结果')
  })

  it('新筛选开始后不追加上一筛选的下一页', async () => {
    let resolveMore!: (value: unknown) => void
    vi.mocked(api.list).mockResolvedValueOnce(page('第一页', 'next') as any)
      .mockReturnValueOnce(new Promise((resolve) => { resolveMore = resolve }) as any)
      .mockResolvedValueOnce(page('新结果') as any)
    wrapper = mount(component, options)
    await flush()
    await wrapper.findAll('a-button-stub').find((b) => b.text() === '加载更多')!.trigger('click')
    await search()
    resolveMore(page('旧第二页'))
    await flush()
    expect(wrapper.text()).toContain('新结果')
    expect(wrapper.text()).not.toContain('旧第二页')
    expect(wrapper.text()).not.toContain('第一页')
  })
})
