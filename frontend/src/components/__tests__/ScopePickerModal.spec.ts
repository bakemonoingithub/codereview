// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ScopePickerModal from '@/components/ScopePickerModal.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
vi.mock('@/api/v2', () => ({ repositoryApi: { paths: vi.fn(), file: vi.fn(), commit: vi.fn(), diff: vi.fn() } }))
import { repositoryApi } from '@/api/v2'
function deferred<T>() { let resolve!: (v: T) => void
  const promise = new Promise<T>((yes) => { resolve = yes }); return { promise, resolve }
}
const listing = (path: string) => ({ entries: [{ path, type: 'blob', reviewable: true }], truncated: false })
const state = (w: any) => w.vm.$.setupState as any
async function start() { const w = mount(ScopePickerModal, { props: {
  open: true, projectId: 'p1', mode: 'FILES', sha: 'sha1', selected: []
}, global: { stubs: { ...namedAntStubs, 'a-tree': { inheritAttrs: false, template: '<div />' }, 'a-modal': { template: '<div><slot /></div>' }, DiffViewer: true } } }); await flushPromises(); return w }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(repositoryApi.paths).mockResolvedValue(listing('A.java') as any) })
describe('范围弹窗的异步隔离', () => {
  it('来源变化后丢弃迟到列表', async () => {
    const w = await start(); const s = state(w); const old = deferred<any>(); const latest = deferred<any>()
    vi.mocked(repositoryApi.paths).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    await w.setProps({ sha: 'sha2' }); await w.setProps({ sha: 'sha3' })
    latest.resolve(listing('New.java')); await flushPromises(); old.resolve(listing('Old.java')); await flushPromises()
    expect(s.entries.map((x: any) => x.path)).toEqual(['New.java']); w.unmount()
  })
  it('加载失败后清空旧树且拒绝确认', async () => {
    const w = await start(); const s = state(w); s.checked = ['A.java']
    vi.mocked(repositoryApi.paths).mockRejectedValue(new Error('失败')); await s.load(); s.confirm()
    expect(s.entries).toEqual([]); expect(w.emitted('confirm')).toBeUndefined(); w.unmount()
  })
  it('加载中不能确认上一版本范围', async () => {
    const w = await start(); const s = state(w); const pending = deferred<any>()
    vi.mocked(repositoryApi.paths).mockReturnValue(pending.promise); const loading = s.load(); s.confirm()
    expect(w.emitted('confirm')).toBeUndefined(); pending.resolve(listing('A.java')); await loading; w.unmount()
  })
  it('先预览的文件迟到时不能覆盖后预览文件正文', async () => {
    const w = await start(); const s = state(w); const a = deferred<any>(); const b = deferred<any>()
    vi.mocked(repositoryApi.file).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const first = s.openPreview({ path: 'A.java' }); const second = s.openPreview({ path: 'B.java' })
    b.resolve({ content: 'new' }); await second; a.resolve({ content: 'old' }); await first
    expect(s.previewPath).toBe('B.java'); expect(s.previewContent).toBe('new'); w.unmount()
  })
  it('关闭弹窗后迟到列表不能恢复旧数据', async () => {
    const w = await start(); const s = state(w); const a = deferred<any>()
    vi.mocked(repositoryApi.paths).mockReturnValue(a.promise); const loading = s.load()
    await w.setProps({ open: false }); a.resolve(listing('Old.java')); await loading
    expect(s.entries).toEqual([]); w.unmount()
  })
  it('取消选择不向主表单发出确认', async () => {
    const w = await start(); state(w).checked = ['A.java']; state(w).close()
    expect(w.emitted('confirm')).toBeUndefined(); expect(w.emitted('update:open')).toEqual([[false]]); w.unmount()
  })
})
