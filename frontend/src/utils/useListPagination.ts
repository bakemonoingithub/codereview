import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import type { Page } from '@/api/v2'

export const LIST_PAGE_SIZES = [10, 20, 50] as const
export type ListPageKey = 'reviews' | 'reports' | 'projects' | 'models' | 'prompts'
export type ListPageParams = { pageNum: number; pageSize: number }

function validSize(value: number) { return LIST_PAGE_SIZES.some(size => size === value) }

export function readListPageSize(key: ListPageKey): number {
  try {
    const prefix = `ai-review-page-size-${key}=`
    const entry = document.cookie.split(';').map(value => value.trim()).find(value => value.startsWith(prefix))
    const value = Number(entry?.slice(prefix.length))
    return validSize(value) ? value : 20
  } catch { return 20 }
}

function saveListPageSize(key: ListPageKey, value: number) {
  try {
    document.cookie = `ai-review-page-size-${key}=${value}; Max-Age=31536000; Path=/; SameSite=Lax`
  } catch { /* 偏好存储失败不影响分页。 */ }
}

/** 页码在请求成功后提交；失败保留最近成功页，重试仍请求失败的目标页。 */
export function useListPagination<T>(
  key: ListPageKey,
  loader: (params: ListPageParams) => Promise<Page<T>>,
  failureMessage: string,
  scope: () => string = () => ''
) {
  const items = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const error = ref('')
  const pageNum = ref(1)
  const pageSize = ref(readListPageSize(key))
  const total = ref(0)
  let preferredSize = pageSize.value
  let sequence = 0
  let disposed = false
  let lastScope: string | undefined
  let pending: ListPageParams = { pageNum: 1, pageSize: preferredSize }

  onBeforeUnmount(() => { disposed = true; sequence++ })

  async function fetchPage(requestedPage = 1, size = preferredSize) {
    const seq = ++sequence
    const currentScope = scope()
    const changedScope = lastScope !== undefined && lastScope !== currentScope
    lastScope = currentScope
    const params = { pageNum: changedScope ? 1 : Math.max(1, Math.trunc(requestedPage) || 1), pageSize: size }
    pending = params
    loading.value = true
    error.value = ''
    try {
      const result = await loader(params)
      if (seq !== sequence || disposed) return false
      items.value = result.items
      total.value = result.total ?? result.items.length
      pageNum.value = result.pageNum ?? params.pageNum
      pageSize.value = result.pageSize ?? params.pageSize
      return true
    } catch (failure) {
      if (seq === sequence && !disposed) error.value = (failure as Error).message || failureMessage
      return false
    } finally {
      if (seq === sequence && !disposed) loading.value = false
    }
  }

  function changePage(page: number, size: number) {
    if (!validSize(size)) return Promise.resolve(false)
    const changed = size !== preferredSize
    preferredSize = size
    saveListPageSize(key, size)
    return fetchPage(changed ? 1 : page, size)
  }

  const pagination = computed(() => ({
    current: pageNum.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: LIST_PAGE_SIZES.map(String),
    showQuickJumper: true,
    hideOnSinglePage: false,
    showTotal: (count: number, range: [number, number]) => `共 ${count} 条 · 当前 ${range[0]}–${range[1]} 条`,
    onChange: changePage
  }))

  return {
    items, loading, error, pageNum, pageSize, total, pagination, changePage, fetchPage,
    load: () => fetchPage(1),
    refresh: () => fetchPage(pageNum.value),
    retry: () => fetchPage(pending.pageNum, pending.pageSize)
  }
}
