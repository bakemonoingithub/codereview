// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { useRecordPagination } from '../useRecordPagination'

/**
 * 分页状态机是「审查记录」页签与「报告-选择审查记录」共用的核心，
 * 这里直接驱动它，把"翻页/改条数/失败/乱序响应"这些边界钉住。
 */
function mountPagination(loader: (params: { pageNum: number; pageSize: number }) => Promise<any>) {
  const api = {
    records: ref<any[]>([]),
    loading: ref(false),
    pageNum: ref(1),
    pageSize: ref(20),
    total: ref(0),
    pagination: ref<any>(null),
    onPageChange: async (_p: number, _s: number) => {},
    reloadFromFirstPage: async () => {},
    fetchPage: async () => {}
  }
  const Comp = {
    setup() {
      const state = useRecordPagination<any>({ loader })
      Object.assign(api, state)
      return () => null
    }
  }
  const wrapper = mount(Comp)
  return { wrapper, api }
}

/** 简单可控的假后端 */
function fakeBackend(total: number) {
  const calls: Array<{ pageNum: number; pageSize: number }> = []
  const loader = async (params: { pageNum: number; pageSize: number }) => {
    calls.push({ ...params })
    const start = (params.pageNum - 1) * params.pageSize
    const count = Math.max(0, Math.min(params.pageSize, total - start))
    return {
      records: Array.from({ length: count }, (_, i) => ({ id: String(start + i) })),
      total
    }
  }
  return { calls, loader }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('useRecordPagination', () => {
  it('首次加载用默认每页 20 条、第 1 页', async () => {
    const { calls, loader } = fakeBackend(18)
    const { api } = mountPagination(loader)
    await flush()

    expect(calls).toEqual([{ pageNum: 1, pageSize: 20 }])
    expect(api.records.value.length).toBe(18)
    expect(api.total.value).toBe(18)
    expect(api.pagination.value.showSizeChanger).toBe(true)
    expect(api.pagination.value.pageSizeOptions).toEqual(['10', '20', '50'])
  })

  it('翻页按新页码重新取数（条数不变时不该改页码）', async () => {
    const { calls, loader } = fakeBackend(18)
    const { api } = mountPagination(loader)
    await flush()

    await api.onPageChange(2, 20)
    await flush()

    expect(calls.at(-1)).toEqual({ pageNum: 2, pageSize: 20 })
    expect(api.pageNum.value).toBe(2)
  })

  it('改每页条数时按"当前第一条"折算页码，而不是打回第 1 页', async () => {
    const { calls, loader } = fakeBackend(200)
    const { api } = mountPagination(loader)
    await flush()

    // 每页 20 翻到第 3 页（第一条是第 41 条），再改成每页 10 → 应落在第 5 页
    await api.onPageChange(3, 20)
    await flush()
    await api.onPageChange(3, 10)
    await flush()

    expect(calls.at(-1)).toEqual({ pageNum: 5, pageSize: 10 })
    expect(api.pageSize.value).toBe(10)
  })

  it('加载器返回 null（请求失败）时清空数据，不抛错', async () => {
    const { api } = mountPagination(async () => null)
    await flush()

    expect(api.records.value).toEqual([])
    expect(api.total.value).toBe(0)
    expect(api.loading.value).toBe(false)
  })

  it('后端没给 total 时按当前页条数兜底（归零会让分页器整个消失）', async () => {
    const { api } = mountPagination(async () => ({ records: [{ id: '1' }, { id: '2' }], total: undefined as any }))
    await flush()

    expect(api.total.value).toBe(2)
  })

  it('慢回来的旧请求不许覆盖新请求的结果', async () => {
    const releases: Array<() => void> = []
    let call = 0
    const { api } = mountPagination(async () => {
      call += 1
      if (call === 1) {
        await new Promise<void>((resolve) => releases.push(resolve))
        return { records: [{ id: 'stale' }], total: 1 }
      }
      return { records: [{ id: 'fresh' }], total: 1 }
    })
    // 第一次请求还挂着，就发起第二次
    const second = api.reloadFromFirstPage()
    await flush()
    releases.forEach((release) => release())
    await second
    await flush()

    expect(api.records.value.map((r: any) => r.id)).toEqual(['fresh'])
  })

  it('reloadFromFirstPage 回到第 1 页', async () => {
    const { calls, loader } = fakeBackend(200)
    const { api } = mountPagination(loader)
    await flush()
    await api.onPageChange(4, 20)
    await flush()

    await api.reloadFromFirstPage()
    await flush()

    expect(calls.at(-1)).toEqual({ pageNum: 1, pageSize: 20 })
  })
})
