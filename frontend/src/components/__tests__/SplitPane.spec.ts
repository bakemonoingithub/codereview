// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import SplitPane from '@/components/SplitPane.vue'
import { antStubs } from '@/testUtils/antStubs'

/**
 * SplitPane 的两条回归契约：
 *
 * 1. **高度不再写死**。原先默认值是 `calc(100vh - 250px)`，同一个组件在页签里和弹窗里
 *    的头部高度并不一样，那个 250 必然在其中一处对不上 —— 高了浪费屏幕，矮了被裁掉。
 *    现在不传 `height` 就按元素顶部位置实测，传了就原样用。
 * 2. **全屏遮罩删掉**。`.fs-mask`（z-index 1000）被最大化的面板（z-index 1001、inset 0）
 *    整块盖住，点击事件永远到不了它，是一段点不动的死代码。退出全屏靠面板头部的按钮。
 */
const SplitpanesStub = defineComponent({
  name: 'Splitpanes',
  emits: ['resized'],
  setup(_, { slots }) {
    return () => h('splitpanes-stub', {}, slots.default ? slots.default() : [])
  }
})

const PaneStub = defineComponent({
  name: 'Pane',
  setup(_, { slots }) {
    return () => h('pane-stub', {}, slots.default ? slots.default() : [])
  }
})

const options = {
  global: {
    stubs: { ...antStubs, Splitpanes: SplitpanesStub, Pane: PaneStub }
  }
}

function setViewportHeight(value: number) {
  Object.defineProperty(window, 'innerHeight', { value, writable: true, configurable: true })
}

describe('SplitPane', () => {
  beforeEach(() => {
    localStorage.clear()
    setViewportHeight(768)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('不传 height 时按元素顶部位置撑满剩余视口，不再用写死的 100vh-N', async () => {
    const wrapper = mount(SplitPane, options)
    await nextTick()

    // happy-dom 里 getBoundingClientRect 全为 0，于是高度 = innerHeight - 0 - 24
    expect(wrapper.find('.split-wrap').attributes('style')).toContain('height: 744px')
  })

  it('窗口变矮时跟着重算', async () => {
    const wrapper = mount(SplitPane, options)
    await nextTick()

    setViewportHeight(600)
    window.dispatchEvent(new Event('resize'))
    await nextTick()

    expect(wrapper.find('.split-wrap').attributes('style')).toContain('height: 576px')
  })

  it('窗口过矮时兜底到最小高度，不会算出负数或 0', async () => {
    setViewportHeight(100)
    const wrapper = mount(SplitPane, options)
    await nextTick()

    expect(wrapper.find('.split-wrap').attributes('style')).toContain('height: 320px')
  })

  it('显式传 height 时原样使用（弹窗里靠 100% 撑满模态框）', async () => {
    const wrapper = mount(SplitPane, { ...options, props: { height: '100%' } })
    await nextTick()

    const style = wrapper.find('.split-wrap').attributes('style')
    expect(style).toContain('height: 100%')
    expect(style).not.toContain('px')
  })

  it('全屏按钮切换最大化，且不再渲染点不动的遮罩', async () => {
    const wrapper = mount(SplitPane, { props: { leftTitle: '审查配置' }, ...options })
    await nextTick()

    expect(wrapper.find('.fs-mask').exists()).toBe(false)

    const pane = wrapper.findAll('.pane')[0]
    expect(pane.classes()).not.toContain('pane-maximized')

    await wrapper.findAll('a-button-stub')[0].trigger('click')
    expect(wrapper.findAll('.pane')[0].classes()).toContain('pane-maximized')
    expect(wrapper.find('.fs-mask').exists()).toBe(false)

    await wrapper.findAll('a-button-stub')[0].trigger('click')
    expect(wrapper.findAll('.pane')[0].classes()).not.toContain('pane-maximized')
  })

  it('拖动分隔条后把比例写进 localStorage', async () => {
    const wrapper = mount(SplitPane, {
      props: { storageKey: 'dsh:test-split' },
      ...options
    })

    wrapper.findComponent({ name: 'Splitpanes' }).vm.$emit('resized', { panes: [{ size: 57.4 }] })

    expect(localStorage.getItem('dsh:test-split')).toBe('57')
  })
})
