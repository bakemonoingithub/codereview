// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyGuide from '@/components/EmptyGuide.vue'
import { antStubs } from '@/testUtils/antStubs'

/**
 * 列表空态引导（C1）。
 *
 * 原先四个列表页在无数据时只有一行"暂无数据"，用户看不到下一步该做什么。
 */
const options = { global: { stubs: { ...antStubs } } }

describe('EmptyGuide', () => {
  it('渲染主文案与补充说明', () => {
    const wrapper = mount(EmptyGuide, {
      props: { title: '还没有项目', hint: '创建一个项目把仓库接进来' },
      ...options
    })

    expect(wrapper.find('.guide-title').text()).toBe('还没有项目')
    expect(wrapper.find('.guide-hint').text()).toBe('创建一个项目把仓库接进来')
  })

  it('没有说明时不渲染说明元素', () => {
    const wrapper = mount(EmptyGuide, { props: { title: '还没有项目' }, ...options })

    expect(wrapper.find('.guide-hint').exists()).toBe(false)
  })

  it('给了主操作文案才渲染按钮，点了把事件抛出去', async () => {
    const wrapper = mount(EmptyGuide, {
      props: { title: '还没有项目', actionText: '新建项目' },
      ...options
    })

    const button = wrapper.findAll('a-button-stub').find((b) => b.text() === '新建项目')!
    expect(button).toBeTruthy()
    await button.trigger('click')
    expect(wrapper.emitted('action')).toBeTruthy()
  })

  it('没有主操作文案时不渲染按钮（如"搜索无结果"场景）', () => {
    const wrapper = mount(EmptyGuide, { props: { title: '没有匹配的提示词' }, ...options })

    expect(wrapper.findAll('a-button-stub').length).toBe(0)
  })
})
