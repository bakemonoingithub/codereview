import { describe, expect, it } from 'vitest'
import {
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
  clampPageNum,
  clampPageSize,
  normalizePagination,
  pageAfterSizeChange,
  totalPages
} from '../records'

describe('分页常量', () => {
  it('选项里不能有超过后端上限的值（否则后端静默截断、前端算错总页数）', () => {
    for (const size of PAGE_SIZE_OPTIONS) {
      expect(size).toBeLessThanOrEqual(MAX_PAGE_SIZE)
    }
    expect(MAX_PAGE_SIZE).toBe(100)
  })

  it('默认页大小在后端允许范围内，且不等于后端默认值 10（前端刻意选 20）', () => {
    expect(DEFAULT_PAGE_SIZE).toBe(20)
    expect(DEFAULT_PAGE_SIZE).toBeLessThanOrEqual(MAX_PAGE_SIZE)
  })
})

describe('页码归一', () => {
  it('非正数、NaN、小数都收敛到合法页码', () => {
    expect(clampPageNum(0)).toBe(1)
    expect(clampPageNum(-3)).toBe(1)
    expect(clampPageNum(Number.NaN)).toBe(1)
    expect(clampPageNum(Number.POSITIVE_INFINITY)).toBe(1)
    expect(clampPageNum(2.7)).toBe(2)
  })
})

describe('每页条数归一', () => {
  it('超限截断到 100', () => {
    expect(clampPageSize(1000)).toBe(MAX_PAGE_SIZE)
    expect(clampPageSize(MAX_PAGE_SIZE)).toBe(MAX_PAGE_SIZE)
  })

  it('非正数回落默认值（不是 1，否则会退化成一条一页）', () => {
    expect(clampPageSize(0)).toBe(DEFAULT_PAGE_SIZE)
    expect(clampPageSize(-5)).toBe(DEFAULT_PAGE_SIZE)
    expect(clampPageSize(Number.NaN)).toBe(DEFAULT_PAGE_SIZE)
  })
})

describe('总页数', () => {
  it('向上取整', () => {
    expect(totalPages(41, 20)).toBe(3)
    expect(totalPages(40, 20)).toBe(2)
    expect(totalPages(1, 20)).toBe(1)
  })

  it('0 条也算 1 页（表格不该出现"第 0 页"）', () => {
    expect(totalPages(0, 20)).toBe(1)
    expect(totalPages(Number.NaN, 20)).toBe(1)
  })
})

describe('接口响应归一', () => {
  it('取 records/total/current/size', () => {
    const state = normalizePagination({ records: [{ id: '1' }], total: 41, current: 3, size: 20 })
    expect(state).toEqual({ pageNum: 3, pageSize: 20, total: 41 })
  })

  it('total 缺失时按当前页记录数兜底，而不是归零（归零会让分页器整个消失）', () => {
    const state = normalizePagination({ records: [{ id: '1' }, { id: '2' }] })
    expect(state.total).toBe(2)
  })

  it('空响应不抛错', () => {
    expect(normalizePagination(undefined)).toEqual({
      pageNum: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      total: 0
    })
    expect(normalizePagination(null)).toEqual({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE, total: 0 })
  })

  it('后端回传的 size 超限也会被夹住', () => {
    expect(normalizePagination({ records: [], total: 0, size: 9999 }).pageSize).toBe(MAX_PAGE_SIZE)
  })
})

describe('改每页条数后停在那一页', () => {
  it('保持"当前第一条"的位置，而不是打回第 1 页', () => {
    // 第 3 页、每页 20 → 第一条是第 41 条；改成每页 10 → 应落在第 5 页
    expect(pageAfterSizeChange({ pageNum: 3, pageSize: 20, total: 200 }, 10)).toBe(5)
    // 第 3 页、每页 10 → 第一条是第 21 条；改成每页 50 → 应落在第 1 页
    expect(pageAfterSizeChange({ pageNum: 3, pageSize: 10, total: 200 }, 50)).toBe(1)
  })

  it('第 1 页改条数后仍在第 1 页', () => {
    expect(pageAfterSizeChange({ pageNum: 1, pageSize: 20, total: 200 }, 50)).toBe(1)
  })

  it('非法输入不产生 0 或负数页码', () => {
    expect(pageAfterSizeChange({ pageNum: 0, pageSize: 0, total: 0 }, 0)).toBe(1)
  })
})
