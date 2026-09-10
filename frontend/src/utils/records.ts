/**
 * 审查记录分页的纯逻辑。
 *
 * 后端只保证"超限截断到 100"，前端仍要自己算一遍，避免出现
 * "页码停在 4、但数据只有 2 页"这种空页——分页状态一旦和接口返回脱节，
 * 表现就是页面空白却没有任何报错。这里不依赖任何组件/DOM，可直接单测。
 */

/** 默认每页条数：与后端默认值 10 刻意不同，见下 */
export const DEFAULT_PAGE_SIZE = 20

/**
 * 可选的每页条数。
 * **必须与后端 `PageLimits.MAX_PAGE_SIZE` 一致**：这里放一个大于 100 的值，
 * 后端会静默截断，前端就会按错的条数算总页数并显示空页。
 */
export const PAGE_SIZE_OPTIONS = [10, 20, 50] as const

/** 单页上限，与后端 PageLimits.MAX_PAGE_SIZE 对齐 */
export const MAX_PAGE_SIZE = 100

export interface PaginationState {
  pageNum: number
  pageSize: number
  total: number
}

/** 统一响应里的 records 形状（后端为 MyBatis-Plus 的 Page 序列化结果） */
export interface PageLike<T> {
  records?: T[]
  total?: number
  current?: number
  size?: number
}

/** 页码从 1 起 */
export function clampPageNum(pageNum: number): number {
  if (!Number.isFinite(pageNum) || pageNum < 1) {
    return 1
  }
  return Math.floor(pageNum)
}

/** 条数不在合法列表里就回落默认值；再夹到上限内 */
export function clampPageSize(pageSize: number): number {
  if (!Number.isFinite(pageSize) || pageSize < 1) {
    return DEFAULT_PAGE_SIZE
  }
  return Math.min(Math.floor(pageSize), MAX_PAGE_SIZE)
}

/** 总页数；0 条也要算 1 页（表格不该出现"第 0 页"） */
export function totalPages(total: number, pageSize: number): number {
  const size = clampPageSize(pageSize)
  if (!Number.isFinite(total) || total <= 0) {
    return 1
  }
  return Math.ceil(total / size)
}

/**
 * 把接口返回归一化成可直接绑到分页器的状态。
 *
 * `total` 缺失时按当前页记录数兜底而不是归零：归零会让分页器直接消失，
 * 用户以为记录被清空了。
 */
export function normalizePagination<T>(
  page: PageLike<T> | null | undefined,
  fallbackSize: number = DEFAULT_PAGE_SIZE
): PaginationState {
  const records = page?.records || []
  const rawTotal = page?.total
  const total = typeof rawTotal === 'number' && rawTotal >= 0 ? rawTotal : records.length
  return {
    pageNum: clampPageNum(typeof page?.current === 'number' ? page.current : 1),
    pageSize: clampPageSize(typeof page?.size === 'number' ? page.size : fallbackSize),
    total
  }
}

/**
 * 改每页条数后该停在第几页。
 *
 * 用"当前第一条记录的序号"保持视觉位置：原本在看第 3 页第 1 条（第 41 条），
 * 改成每页 10 条后应该停在第 5 页，而不是被粗暴地打回第 1 页。
 */
export function pageAfterSizeChange(previous: PaginationState, nextSize: number): number {
  const size = clampPageSize(nextSize)
  const firstItemIndex = (clampPageNum(previous.pageNum) - 1) * clampPageSize(previous.pageSize)
  return clampPageNum(Math.floor(firstItemIndex / size) + 1)
}
