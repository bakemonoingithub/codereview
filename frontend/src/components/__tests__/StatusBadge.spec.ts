// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusBadge from '@/components/StatusBadge.vue'

describe('StatusBadge', () => {
  it('渲染文案并带状态圆点', () => {
    const wrapper = mount(StatusBadge, { props: { tone: 'green' }, slots: { default: '已完成' } })

    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.find('.dot').exists()).toBe(true)
    expect(wrapper.find('.is-green').exists()).toBe(true)
  })

  it('未传色调时使用默认样式', () => {
    const wrapper = mount(StatusBadge, { slots: { default: '未配置' } })

    expect(wrapper.find('.is-default').exists()).toBe(true)
  })
})