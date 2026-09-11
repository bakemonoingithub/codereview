// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ProjectList from '@/views/ProjectList.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

/**
 * 列表级错误态（A 批 E7）。
 *
 * 回归背景：`load()` 原先**没有 catch**，请求失败时表格永远空着、
 * 既没有提示也没有重试入口 —— 用户分不清"没有数据"和"接口挂了"。
 */
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return { ...actual, listProjects: vi.fn(), createProject: vi.fn() }
})

import { listProjects } from '@/api/project'

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-table': false,
      'a-table-column': false
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('ProjectList 加载失败', () => {
  it('给出可重试的错误提示，而不是一直空白', async () => {
    ;(listProjects as any).mockRejectedValueOnce(
      new Error('无法连接服务器，请检查网络或后端服务是否已启动')
    )

    const wrapper = mount(ProjectList, options)
    await flush()

    const alert = wrapper.find('a-alert-stub')
    expect(alert.exists(), '失败时必须出现错误提示').toBe(true)
    expect(alert.attributes('message')).toContain('无法连接服务器')
    expect(wrapper.findAll('a-button-stub').some((b) => b.text() === '重试')).toBe(true)
  })

  it('点「重试」重新请求，成功后提示消失', async () => {
    ;(listProjects as any).mockRejectedValueOnce(new Error('请求超时，请稍后重试'))

    const wrapper = mount(ProjectList, options)
    await flush()
    expect(wrapper.find('a-alert-stub').exists()).toBe(true)

    ;(listProjects as any).mockResolvedValueOnce({ records: [{ id: '1', name: '演示项目' }] })
    const retry = wrapper.findAll('a-button-stub').find((b) => b.text() === '重试')!
    await retry.trigger('click')
    await flush()

    expect(wrapper.find('a-alert-stub').exists()).toBe(false)
    expect(wrapper.text()).toContain('演示项目')
  })

  it('加载成功时不渲染错误提示', async () => {
    ;(listProjects as any).mockResolvedValueOnce({ records: [] })

    const wrapper = mount(ProjectList, options)
    await flush()

    expect(wrapper.find('a-alert-stub').exists()).toBe(false)
  })

  /** C1：无数据时不能只有一行"暂无数据"，要告诉用户下一步做什么 */
  it('无数据时给出空态引导与新建入口', async () => {
    ;(listProjects as any).mockResolvedValueOnce({ records: [] })

    const wrapper = mount(ProjectList, options)
    await flush()

    expect(wrapper.text()).toContain('还没有项目')
    expect(wrapper.text()).toContain('Gitea')
    expect(wrapper.findAll('a-button-stub').some((b) => b.text() === '新建项目')).toBe(true)
  })
})
