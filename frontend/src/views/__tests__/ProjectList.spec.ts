// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ProjectList from '@/views/ProjectList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({
  projectsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn() }
}))

import { projectsApi } from '@/api/v2'

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

const project = {
  id: 'p1',
  name: '订单服务',
  repoUrl: 'https://git.example.com/order',
  currentBranch: 'main',
  credentialConfigured: true,
  revision: 3,
  createdAt: '2026-10-20T10:00:00',
  updatedAt: '2026-10-20T10:00:00'
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(projectsApi.list as any).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
})

describe('ProjectList', () => {
  it('加载失败时给出可重试提示', async () => {
    ;(projectsApi.list as any).mockRejectedValueOnce(new Error('无法连接服务器'))
    const wrapper = mount(ProjectList, options)
    await flush()

    const alert = wrapper.find('.load-error')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('无法连接服务器')
    expect(wrapper.findAll('a-button-stub').some((b) => b.text() === '重试')).toBe(true)
  })

  it('重试成功后提示消失并显示数据', async () => {
    ;(projectsApi.list as any).mockRejectedValueOnce(new Error('请求超时'))
    const wrapper = mount(ProjectList, options)
    await flush()

    ;(projectsApi.list as any).mockResolvedValueOnce({
      items: [project],
      nextCursor: null,
      hasMore: false
    })
    const retry = wrapper.findAll('a-button-stub').find((b) => b.text() === '重试')!
    await retry.trigger('click')
    await flush()

    expect(wrapper.find('.load-error').exists()).toBe(false)
    expect(wrapper.text()).toContain('订单服务')
  })

  it('空列表给出创建入口', async () => {
    const wrapper = mount(ProjectList, options)
    await flush()

    expect(wrapper.text()).toContain('还没有项目')
    expect(wrapper.findAll('a-button-stub').some((b) => b.text() === '新建项目')).toBe(true)
  })

  it('编辑时仓库地址锁定，令牌留空保留', async () => {
    ;(projectsApi.list as any).mockResolvedValue({
      items: [project],
      nextCursor: null,
      hasMore: false
    })
    const wrapper = mount(ProjectList, options)
    await flush()

    const edit = wrapper.findAll('a-button-stub').find((b) => b.text() === '编辑')!
    await edit.trigger('click')
    await flush()

    expect(wrapper.find('a-input-stub[disabled]').exists()).toBe(true)
    expect(wrapper.find('a-input-password-stub').attributes('placeholder')).toContain('留空不修改')
    expect(projectsApi.update).not.toHaveBeenCalled()
  })
})
