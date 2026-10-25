// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { readListPageSize, useListPagination, type ListPageKey } from '@/utils/useListPagination'

const page = (id: string, pageNum = 1, pageSize = 20, total = 65) => ({
  items: [{ id }], total, pageNum, pageSize, nextCursor: null, hasMore: pageNum * pageSize < total
})
const wrappers: VueWrapper[] = []
function start(key: ListPageKey = 'projects', loader = vi.fn().mockResolvedValue(page('one'))) {
  let state!: ReturnType<typeof useListPagination<{ id: string }>>
  wrappers.push(mount(defineComponent({ setup() {
    state = useListPagination(key, loader, '加载失败')
    return () => null
  } })))
  return { state, loader }
}
beforeEach(() => {
  for (const key of ['reviews', 'reports', 'projects', 'models', 'prompts']) {
    document.cookie = `ai-review-page-size-${key}=; Max-Age=0; Path=/`
  }
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

describe('列表分页与 Cookie 偏好', () => {
  it('初次每页 20，页码切换替换数据而不追加', async () => {
    const { state, loader } = start()
    await state.load()
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 20 })
    loader.mockResolvedValueOnce(page('two', 2))
    await state.pagination.value.onChange(2, 20)
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 2, pageSize: 20 })
    expect(state.items.value).toEqual([{ id: 'two' }])
    expect(state.pagination.value.current).toBe(2)
    expect(state.pagination.value.pageSizeOptions).toEqual(['10', '20', '50'])
    expect(state.pagination.value.showQuickJumper).toBe(true)
  })

  it('五类列表 Cookie 独立保存，重新挂载恢复对应大小', async () => {
    const keys: ListPageKey[] = ['reviews', 'reports', 'projects', 'models', 'prompts']
    for (const [index, key] of keys.entries()) {
      const size = [10, 20, 50][index % 3]
      const { state, loader } = start(key)
      loader.mockResolvedValueOnce(page(key, 1, size))
      await state.changePage(8, size)
      expect(readListPageSize(key)).toBe(size)
    }
    for (const [index, key] of keys.entries()) {
      const { state, loader } = start(key)
      await state.load()
      expect(loader).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: [10, 20, 50][index % 3] })
    }
  })

  it('条数变化与重新筛选均回到第一页，刷新保留当前页', async () => {
    const { state, loader } = start()
    loader.mockResolvedValueOnce(page('third', 3)); await state.changePage(3, 20)
    await state.refresh()
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 3, pageSize: 20 })
    loader.mockResolvedValueOnce(page('small', 1, 10)); await state.changePage(3, 10)
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 10 })
    await state.load()
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 10 })
  })

  it('失败保留上次成功的数据和页码，重试请求失败的目标页', async () => {
    const { state, loader } = start()
    await state.load()
    loader.mockRejectedValueOnce(new Error('网络故障'))
    await state.changePage(2, 20)
    expect(state.items.value).toEqual([{ id: 'one' }])
    expect(state.pageNum.value).toBe(1)
    expect(state.error.value).toBe('网络故障')
    loader.mockResolvedValueOnce(page('two', 2)); await state.retry()
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 2, pageSize: 20 })
    expect(state.error.value).toBe('')
    expect(state.pageNum.value).toBe(2)
  })

  it('旧页迟到响应不能覆盖新页，卸载后也不提交结果', async () => {
    const { state, loader } = start()
    let resolveOld!: (value: unknown) => void
    loader.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const old = state.changePage(2, 20)
    loader.mockResolvedValueOnce(page('new', 1, 10)); await state.changePage(1, 10)
    resolveOld(page('old', 2)); await old
    expect(state.items.value).toEqual([{ id: 'new' }])
    loader.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const pending = state.refresh(); wrappers[0].unmount()
    resolveOld(page('unmounted')); await pending
    expect(state.items.value).toEqual([{ id: 'new' }])
  })

  it('服务端在末页删除后回退到有效页时采用返回页码和总数', async () => {
    const { state, loader } = start()
    loader.mockResolvedValueOnce(page('last', 2, 20, 40)); await state.changePage(3, 20)
    expect(state.pageNum.value).toBe(2)
    expect(state.total.value).toBe(40)
    await state.refresh()
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 2, pageSize: 20 })
  })

  it('筛选内容改变后直接翻页也从第一页查询新筛选', async () => {
    let query = 'old'
    let state!: ReturnType<typeof useListPagination<{ id: string }>>
    const loader = vi.fn().mockResolvedValue(page('one'))
    wrappers.push(mount(defineComponent({ setup() {
      state = useListPagination('projects', loader, '失败', () => query)
      return () => null
    } })))
    await state.load()
    query = 'new'
    await state.changePage(3, 20)
    expect(loader).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 20 })
  })

  it('非法或无法读取的 Cookie 使用默认值，保存失败仍能翻页', async () => {
    document.cookie = 'ai-review-page-size-projects=999; Path=/'
    expect(readListPageSize('projects')).toBe(20)
    vi.spyOn(document, 'cookie', 'get').mockImplementation(() => { throw new Error('禁用') })
    vi.spyOn(document, 'cookie', 'set').mockImplementation(() => { throw new Error('禁用') })
    const { state, loader } = start()
    loader.mockResolvedValueOnce(page('small', 1, 10)); await state.changePage(1, 10)
    expect(loader).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10 })
    expect(state.items.value[0].id).toBe('small')
  })
})
