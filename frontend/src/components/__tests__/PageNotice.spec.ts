// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PageNotice from '@/components/PageNotice.vue'

describe('PageNotice', () => {
  it('渲染说明文字，默认是信息样式', () => {
    const wrapper = mount(PageNotice, { slots: { default: '配置后可选用' } })

    expect(wrapper.text()).toContain('配置后可选用')
    expect(wrapper.find('.is-info').exists()).toBe(true)
    expect(wrapper.find('.page-notice-close').exists()).toBe(false)
  })

  it('amber 与 red 使用对应变体', () => {
    expect(mount(PageNotice, { props: { variant: 'amber' } }).find('.is-amber').exists()).toBe(true)
    expect(mount(PageNotice, { props: { variant: 'red' } }).find('.is-red').exists()).toBe(true)
  })

  it('可关闭时抛出 close', async () => {
    const wrapper = mount(PageNotice, { props: { closable: true }, slots: { default: '新任务已受理' } })

    await wrapper.find('.page-notice-close').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
