// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { message } from 'ant-design-vue'
import StrategyList from '@/views/StrategyList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({
  strategiesApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn()
  },
  modelsApi: { list: vi.fn() },
  promptsApi: { list: vi.fn() }
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
  vi.clearAllMocks()
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

    expect(wrapper.text()).toContain('问题审查（含提交差异）')
    expect(wrapper.text()).toContain('默认模型')
    expect(wrapper.text()).toContain('订单规则')
  })

  it('模型为空时给出创建入口', async () => {
    const wrapper = await mountList()
    const create = wrapper.findAll('a-button-stub').find((b) => b.text() === '新建策略')!
    await create.trigger('click')
    await flush()

    expect(wrapper.text()).toContain('暂无可用模型')
    expect(wrapper.text()).toContain('去创建模型')
  })

  it('模型加载失败时说明原因并给出重试入口', async () => {
    ;(modelsApi.list as any).mockRejectedValue(new Error('无法连接服务器'))
    const wrapper = await mountList()
    const create = wrapper.findAll('a-button-stub').find((b) => b.text() === '新建策略')!
    await create.trigger('click')
    await flush()

    expect(wrapper.text()).toContain('无法连接服务器')
    expect(wrapper.text()).toContain('去模型页重试')
  })

  it('未选择模型时不提交', async () => {
    const warn = vi.spyOn(message, 'warning').mockImplementation(() => ({}) as any)
    const wrapper = await mountList()
    const create = wrapper.findAll('a-button-stub').find((b) => b.text() === '新建策略')!
    await create.trigger('click')
    await flush()

    const modal = wrapper.findComponent({ name: 'AModal' })
    modal.vm.$emit('ok')
    await flush()

    expect(warn).toHaveBeenCalled()
    expect(strategiesApi.create).not.toHaveBeenCalled()
  })
})
