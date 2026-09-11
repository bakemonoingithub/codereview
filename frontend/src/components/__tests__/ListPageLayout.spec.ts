// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ListPageLayout from '@/components/ListPageLayout.vue'
import { antStubs } from '@/testUtils/antStubs'

/**
 * 列表页骨架（C3）。
 *
 * 抽它的原因：四个列表页原先"新建按钮一会儿最左一会儿最右、容器一会儿 class
 * 一会儿内联 style、全都没有页面标题" —— 页面上没有标题，用户只能靠侧边栏猜自己在哪。
 */
const options = { global: { stubs: { ...antStubs } } }

describe('ListPageLayout', () => {
  it('渲染页面标题与说明（"我在哪"的最直接答案）', () => {
    const wrapper = mount(ListPageLayout, {
      props: { title: '项目管理', subtitle: '维护要审查的代码仓库' },
      ...options
    })

    expect(wrapper.find('.page-title').text()).toBe('项目管理')
    expect(wrapper.find('.page-subtitle').text()).toBe('维护要审查的代码仓库')
  })

  it('没有说明时不渲染副标题元素', () => {
    const wrapper = mount(ListPageLayout, { props: { title: '项目管理' }, ...options })

    expect(wrapper.find('.page-title').exists()).toBe(true)
    expect(wrapper.find('.page-subtitle').exists()).toBe(false)
  })

  it('主操作固定在标题右侧区域', () => {
    const wrapper = mount(ListPageLayout, {
      props: { title: '项目管理' },
      slots: { actions: '<button class="primary-action">新建项目</button>' },
      ...options
    })

    expect(wrapper.find('.page-head-actions .primary-action').exists()).toBe(true)
  })

  it('没有提供筛选插槽时不留空占位', () => {
    const wrapper = mount(ListPageLayout, {
      props: { title: '项目管理' },
      ...options
    })

    expect(wrapper.find('.page-filters').exists()).toBe(false)
  })

  it('提供筛选插槽时渲染在表格上方', () => {
    const wrapper = mount(ListPageLayout, {
      props: { title: '策略' },
      slots: {
        filters: '<div class="my-filter">搜索</div>',
        default: '<div class="my-table">表格</div>'
      },
      ...options
    })

    const filters = wrapper.find('.page-filters')
    expect(filters.find('.my-filter').exists()).toBe(true)
    // 筛选区在内容之前
    expect(wrapper.html().indexOf('page-filters')).toBeLessThan(wrapper.html().indexOf('my-table'))
  })

  it('内容走默认插槽', () => {
    const wrapper = mount(ListPageLayout, {
      props: { title: '项目管理' },
      slots: { default: '<div class="my-table">表格</div>' },
      ...options
    })

    expect(wrapper.find('.my-table').text()).toBe('表格')
  })
})
