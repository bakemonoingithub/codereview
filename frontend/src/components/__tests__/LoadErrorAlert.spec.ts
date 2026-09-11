// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import { antStubs } from '@/testUtils/antStubs'

/**
 * 列表级错误态：有错误才渲染，且必须带可点的「重试」。
 * 这两条正是原先缺失的 —— 失败时页面一片空白、没有任何出口。
 */
const options = { global: { stubs: { ...antStubs } } }

describe('LoadErrorAlert', () => {
  it('没有错误时不渲染任何东西（调用方不必自己 v-if）', () => {
    const wrapper = mount(LoadErrorAlert, { props: { message: '' }, ...options })

    expect(wrapper.find('a-alert-stub').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('重试')
  })

  it('有错误时显示文案与「重试」按钮', () => {
    const wrapper = mount(LoadErrorAlert, { props: { message: '无法连接服务器' }, ...options })

    const alert = wrapper.find('a-alert-stub')
    expect(alert.exists()).toBe(true)
    expect(alert.attributes('message')).toBe('无法连接服务器')
    expect(alert.attributes('type')).toBe('error')
    expect(wrapper.findAll('a-button-stub').some((b) => b.text() === '重试')).toBe(true)
  })

  it('点「重试」把事件抛给调用方', async () => {
    const wrapper = mount(LoadErrorAlert, { props: { message: '加载失败' }, ...options })

    const retry = wrapper.findAll('a-button-stub').find((b) => b.text() === '重试')!
    await retry.trigger('click')

    expect(wrapper.emitted('retry')).toBeTruthy()
  })
})
