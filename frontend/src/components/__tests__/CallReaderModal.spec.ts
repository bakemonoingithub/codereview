// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CallReaderModal from '@/components/CallReaderModal.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
vi.mock('@/api/v2', () => ({ tasksApi: { call: vi.fn() } }))
import { tasksApi } from '@/api/v2'
function deferred<T>() { let resolve!: (v: T) => void
  const promise = new Promise<T>(yes => { resolve = yes }); return { promise, resolve }
}
const record = (id: string) => ({ id, requestBody: 'request-' + id, responseBody: 'response-' + id, usageJson: '{"tokens":12}' })
const state = (w: any) => w.vm.$.setupState as any
async function start() { const w = mount(CallReaderModal, { props: { open: true, taskId: 't1', callId: 'c1' },
  global: { stubs: { ...namedAntStubs, 'a-modal': { template: '<div><slot /></div>' } } } }); await flushPromises(); return w }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(tasksApi.call).mockResolvedValue(record('c1') as any) })
describe('实际调用阅读', () => {
  it('后打开的调用不被先前响应覆盖', async () => {
    const w = await start(); const a = deferred<any>(); const b = deferred<any>()
    vi.mocked(tasksApi.call).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    await w.setProps({ callId: 'old' }); await w.setProps({ callId: 'new' })
    b.resolve(record('new')); await flushPromises(); a.resolve(record('old')); await flushPromises()
    expect(w.find('pre').text()).toContain('request-new'); w.unmount()
  })
  it('任务变化时重新校验调用归属，关闭后不回填', async () => {
    const w = await start(); await w.setProps({ taskId: 't2' }); await flushPromises()
    expect(tasksApi.call).toHaveBeenLastCalledWith('t2', 'c1')
    const pending = deferred<any>(); vi.mocked(tasksApi.call).mockReturnValue(pending.promise)
    await w.setProps({ callId: 'old' }); await w.setProps({ open: false })
    pending.resolve(record('old')); await flushPromises(); expect(state(w).call).toBeNull(); w.unmount()
  })
  it('可以查看保存的调用用量', async () => {
    const w = await start(); state(w).part = 'usage'; await flushPromises()
    expect(w.find('pre').text()).toContain('"tokens": 12'); w.unmount()
  })
})
