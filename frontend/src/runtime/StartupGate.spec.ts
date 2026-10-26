import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import StartupGate from './StartupGate.vue'
const { load } = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('./config', () => ({ loadRuntimeConfig: load }))
describe('StartupGate', () => {
  it('blocks application startup on failure and allows a successful retry', async () => {
    load.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({})
    const ready = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(StartupGate, { props: { ready } })
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法打开应用')
    expect(ready).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(ready).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
