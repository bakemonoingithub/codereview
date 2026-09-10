/**
 * patch 渲染前的纯逻辑：补 diff 头、把行级评论映射成 @git-diff-view 的 extendData。
 *
 * GitHub 的 files[].patch 从 `@@` 开始，**不含** `--- a/` `+++ b/` 头；
 * 而 diff 解析器需要完整头，故在此按变更类型补齐。
 */

export type ChangeStatus = 'added' | 'modified' | 'removed' | 'renamed' | string

/** 给 patch 补上 unified diff 头（已有头则原样返回） */
export function toRenderablePatch(
  path: string,
  patch?: string | null,
  status?: ChangeStatus | null
): string {
  const body = (patch || '').replace(/\s+$/, '')
  if (!body) {
    return ''
  }
  if (body.startsWith('--- ') || body.startsWith('diff --git')) {
    return body
  }
  const normalized = (status || 'modified').toLowerCase()
  const oldHeader = normalized === 'added' ? '/dev/null' : `a/${path}`
  const newHeader = normalized === 'removed' ? '/dev/null' : `b/${path}`
  return `--- ${oldHeader}\n+++ ${newHeader}\n${body}`
}

/**
 * 把「按新文件行号锚定的数据」转成 DiffView 的 extendData。
 * 同一行有多条时只保留第一条——extend 插槽每行只渲染一次，其余由插槽内部展开。
 */
export function buildExtendData<T>(items: Array<{ line?: number | null; data: T }>): {
  newFile: Record<string, { data: T[] }>
} {
  const newFile: Record<string, { data: T[] }> = {}
  for (const item of items) {
    if (item.line === null || item.line === undefined || Number.isNaN(item.line)) {
      continue
    }
    const key = String(item.line)
    if (newFile[key]) {
      newFile[key].data.push(item.data)
    } else {
      newFile[key] = { data: [item.data] }
    }
  }
  return { newFile }
}

/** 统计 patch 中的新增/删除行数（用于在 diff 头部展示） */
export function countPatchLines(patch?: string | null): { additions: number; deletions: number } {
  if (!patch) {
    return { additions: 0, deletions: 0 }
  }
  let additions = 0
  let deletions = 0
  for (const line of patch.split('\n')) {
    if (line.startsWith('+++') || line.startsWith('---')) {
      continue
    }
    if (line.startsWith('+')) {
      additions++
    } else if (line.startsWith('-')) {
      deletions++
    }
  }
  return { additions, deletions }
}
