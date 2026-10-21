// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MaterialReaderModal from '@/components/MaterialReaderModal.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
vi.mock('@/api/v2', () => ({ tasksApi: { material: vi.fn() }, repositoryApi: { file: vi.fn(), diff: vi.fn() } }))
import { tasksApi, repositoryApi } from '@/api/v2'
function deferred<T>() { let resolve!: (v: T) => void
  const promise = new Promise<T>(yes => { resolve = yes }); return { promise, resolve }
}
const saved = (id = 'm1', content = 'saved') => ({ metadata: { id, path: id + '.java', sha: 'fixed-sha', side: 'FILE',
  startLine: 10, endLine: 10, completeness: 'FRAGMENT', sent: true }, content, complete: true })
const state = (w: any) => w.vm.$.setupState as any
async function start() { const w = mount(MaterialReaderModal, { props: { open: true, taskId: 't1', projectId: 'p1', headSha: 'head', materialId: 'm1' },
  global: { stubs: { ...namedAntStubs, 'a-modal': { template: '<div><slot /></div>' }, DiffViewer: true } } }); await flushPromises(); return w }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(tasksApi.material).mockResolvedValue(saved() as any)
  vi.mocked(repositoryApi.file).mockResolvedValue({ content: 'full file' } as any) })
describe('材料阅读的固定内容与异步隔离', () => {
  it('全文后返回保存片段，恢复实际发送文本及原始行号', async () => {
    const w = await start(); const s = state(w); await s.loadFull()
    expect(w.text()).toContain('full file'); s.showSaved(); await flushPromises()
    expect(w.find('pre').text()).toBe('10 | saved'); expect(w.find('pre').text()).not.toContain('full file')
    expect(repositoryApi.file).toHaveBeenCalledWith('p1', 'fixed-sha', 'm1.java'); w.unmount()
  })
  it('扩展全文失败不阻止返回保存内容', async () => {
    const w = await start(); const s = state(w); vi.mocked(repositoryApi.file).mockRejectedValue(new Error('读取失败'))
    await s.loadFull(); expect(s.contentError).toBe('读取失败'); s.showSaved(); await flushPromises()
    expect(w.find('pre').text()).toContain('saved'); expect(s.contentError).toBe(''); w.unmount()
  })
  it('旧材料响应不能覆盖新材料', async () => {
    const w = await start(); const a = deferred<any>(); const b = deferred<any>()
    vi.mocked(tasksApi.material).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    await w.setProps({ materialId: 'old' }); await w.setProps({ materialId: 'new' })
    b.resolve(saved('new', 'new text')); await flushPromises(); a.resolve(saved('old', 'old text')); await flushPromises()
    expect(state(w).detail.metadata.id).toBe('new'); expect(w.find('pre').text()).toContain('new text'); w.unmount()
  })
  it('全文迟到时不能覆盖已切回的保存片段', async () => {
    const w = await start(); const s = state(w); const pending = deferred<any>()
    vi.mocked(repositoryApi.file).mockReturnValue(pending.promise)
    const reading = s.loadFull(); s.showSaved(); pending.resolve({ content: 'late full' }); await reading; await flushPromises()
    expect(w.find('pre').text()).toContain('saved'); expect(w.find('pre').text()).not.toContain('late full'); w.unmount()
  })
  it('关闭或更换任务后丢弃旧请求并重新读取相同材料 ID', async () => {
    const w = await start(); const pending = deferred<any>()
    vi.mocked(tasksApi.material).mockReturnValueOnce(pending.promise)
    await w.setProps({ materialId: 'old' }); await w.setProps({ open: false })
    pending.resolve(saved('old')); await flushPromises(); expect(state(w).detail).toBeNull()
    await w.setProps({ open: true, taskId: 't2', materialId: 'm1' }); await flushPromises()
    expect(tasksApi.material).toHaveBeenLastCalledWith('t2', 'm1'); w.unmount()
  })
})
