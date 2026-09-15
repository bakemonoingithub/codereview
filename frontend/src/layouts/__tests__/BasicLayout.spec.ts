// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 侧边栏图标与折叠态品牌区。
 *
 * 回归背景：品牌区原先是"纯文字 + `overflow:hidden`"，侧栏收到 64px 时文字被**从中间硬裁**
 * （用户看到的就是"半截汉字"）；菜单项则一个图标都没有 —— 而 antd 的 `a-menu` 在 Sider 里
 * 会**自动隐藏文字**，于是折叠后每个菜单项是彻底空白的，连提示都没有。
 *
 * 这里锁三件事：① 每个菜单项都有图标；② 折叠时品牌区换成 logo 且**不再渲染文字**；
 * ③ 菜单项带 `title`（antd 折叠态靠它弹中文 tooltip，标签是 router-link 元素时尤其需要）。
 */
vi.mock('vue-router', async (importOriginal) => {
  // 保留 createRouter/createWebHashHistory：@/router 模块初始化时要用
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ path: '/projects' }), useRouter: () => ({ push: vi.fn() }) }
})

import BasicLayout from '@/layouts/BasicLayout.vue'
import { menuItems } from '@/router'

const options = {
  global: {
    stubs: {
      // 自带 router-link 替身：否则未安装 router 时它无法解析，菜单文字读不到
      'router-link': { props: ['to'], template: '<a :href="to"><slot /></a>' },
      'router-view': true
    }
  }
}

function mountLayout() {
  return mount(BasicLayout, options)
}

describe('侧边栏菜单图标', () => {
  it('每个菜单项都渲染出图标', () => {
    const wrapper = mountLayout()

    expect(menuItems.length).toBeGreaterThan(0)
    expect(wrapper.findAll('.ant-menu-item .anticon')).toHaveLength(menuItems.length)
  })

  it('图标与菜单一一对应（顺序不串位）', () => {
    const wrapper = mountLayout()
    const icons = wrapper.findAll('.ant-menu-item .anticon').map((i) => i.attributes('class'))

    // @ant-design/icons 会把组件名渲染成 kebab 的 class（FolderOutlined → anticon-folder）
    expect(icons[0]).toContain('anticon-folder')
    expect(icons[1]).toContain('anticon-robot')
    expect(icons[2]).toContain('anticon-setting')
    expect(icons[3]).toContain('anticon-file-text')
  })

  it('菜单项带 title，折叠态才有中文 tooltip', () => {
    const wrapper = mountLayout()
    // a-menu 内部会把列表再渲染一遍（隐藏的测量容器），所以先去重
    const titles = [
      ...new Set(wrapper.findAllComponents({ name: 'AMenuItem' }).map((m) => m.props('title')))
    ]

    expect(titles).toEqual(['项目', '模型', '策略', '提示词'])
  })
})

describe('侧边栏品牌区', () => {
  it('展开时同时显示 logo 与文字', () => {
    const wrapper = mountLayout()

    expect(wrapper.find('.logo-mark').attributes('src')).toBe('/favicon.svg')
    expect(wrapper.find('.logo-text').exists()).toBe(true)
    expect(wrapper.find('.logo-text').text()).toBe('智能代码分析')
  })

  it('折叠时只显示 logo，文字不再渲染（避免半截汉字）', async () => {
    const wrapper = mountLayout()

    // 折叠由 Sider 驱动（折叠按钮或 breakpoint）；这里直接触发 Sider 的回写
    wrapper.findComponent({ name: 'ALayoutSider' }).vm.$emit('update:collapsed', true)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.logo-mark').exists()).toBe(true)
    expect(wrapper.find('.logo-text').exists()).toBe(false)
    // 只看侧栏：页头那句"智能代码分析工具"不在此列
    expect(wrapper.findComponent({ name: 'ALayoutSider' }).text()).not.toContain('智能代码分析')
  })

  it('再展开时文字回来', async () => {
    const wrapper = mountLayout()
    const sider = wrapper.findComponent({ name: 'ALayoutSider' })

    sider.vm.$emit('update:collapsed', true)
    await wrapper.vm.$nextTick()
    sider.vm.$emit('update:collapsed', false)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.logo-text').text()).toBe('智能代码分析')
  })

  it('折叠状态绑回了 Sider（breakpoint 响应式折叠也要能被布局层读到）', () => {
    const wrapper = mountLayout()

    expect(wrapper.findComponent({ name: 'ALayoutSider' }).props('collapsed')).toBe(false)
  })
})
