import { computed, ref, watch, type Ref } from 'vue'
import {
  DEFAULT_PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
  clampPageSize,
  pageAfterSizeChange
} from '@/utils/records'

/**
 * 「审查记录」与「报告-选择审查记录」共用的服务端分页状态机。
 *
 * 抽出来是因为两处的分页逻辑本该完全一致（默认 20、可改 10/20/50、改条数按首条位置折算页码、
 * total 缺失时兜底）；各写一份必然漂移，而漂移的表现是"某一页悄悄少显示几条"这种不报错的错。
 */

export interface PageResult<T> {
  records: T[]
  total: number
}

/**
 * 加载器：**失败就抛**，由本 hook 统一兜住。
 *
 * 早先的约定是"失败返回 null"，结果两个调用方都 `catch { return null }` 之后
 * 什么也不做 —— hook 只好把列表清空，界面上"记录被清空了"和"接口挂了"长得一模一样。
 * 改成抛异常后，错误信息（已由 request.ts 归一成中文）能一路传到界面上。
 */
export type PageLoader<T> = (params: { pageNum: number; pageSize: number }) => Promise<PageResult<T>>

export interface UseRecordPaginationOptions<T> {
  loader: PageLoader<T>
  /** 首次加载是否立即执行，默认 true */
  immediate?: boolean
}

export function useRecordPagination<T>(options: UseRecordPaginationOptions<T>) {
  const records = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  /** 非空表示"本次加载失败"，界面据此显示错误提示与重试 */
  const error = ref('')
  const pageNum = ref(1)
  const pageSize = ref<number>(DEFAULT_PAGE_SIZE)
  const total = ref(0)

  /** 请求序号：慢回来的旧请求不许覆盖新请求的结果 */
  let requestSeq = 0

  async function fetchPage() {
    const seq = ++requestSeq
    loading.value = true
    try {
      const result = await options.loader({ pageNum: pageNum.value, pageSize: pageSize.value })
      if (seq !== requestSeq) {
        return
      }
      records.value = result?.records || []
      // total 缺失时按当前页条数兜底而不是归零：归零会让分页器整个消失
      total.value = typeof result?.total === 'number' && result.total >= 0 ? result.total : records.value.length
      error.value = ''
    } catch (e: any) {
      if (seq !== requestSeq) {
        return
      }
      // 失败**不清空**已有数据：清空会让用户以为"记录没了"，而其实只是这次没取到。
      // 代价是翻页失败时表格短暂显示的是上一页内容 —— 但上方有明确的错误提示与重试，
      // 且重试会按用户点的那一页重新拉，比"清空 + 不说话"诚实得多。
      error.value = e?.message || '加载失败，请重试'
    } finally {
      if (seq === requestSeq) {
        loading.value = false
      }
    }
  }

  /**
   * 表格分页器回调。
   * 改每页条数时按"当前第一条的序号"折算页码，别把用户粗暴打回第一页。
   */
  async function onPageChange(nextPage: number, nextSize: number) {
    if (clampPageSize(nextSize) !== clampPageSize(pageSize.value)) {
      pageNum.value = pageAfterSizeChange(
        { pageNum: pageNum.value, pageSize: pageSize.value, total: total.value },
        nextSize
      )
      pageSize.value = clampPageSize(nextSize)
    } else {
      pageNum.value = nextPage
    }
    await fetchPage()
  }

  /** 回到第 1 页重拉：新产生的记录一定排在最前，否则"提交了却看不到变化" */
  async function reloadFromFirstPage() {
    pageNum.value = 1
    await fetchPage()
  }

  const pagination = computed(() => ({
    current: pageNum.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: PAGE_SIZE_OPTIONS.map(String),
    showTotal: (value: number) => `共 ${value} 条`,
    onChange: onPageChange
  }))

  if (options.immediate !== false) {
    void fetchPage()
  }

  // 调用方换了数据源（如加上状态过滤）就重新从第 1 页拉
  watch(
    () => options.loader,
    (next, prev) => {
      if (next !== prev) {
        void reloadFromFirstPage()
      }
    }
  )

  return {
    records,
    loading,
    error,
    pageNum,
    pageSize,
    total,
    pagination,
    fetchPage,
    onPageChange,
    reloadFromFirstPage
  }
}
