/**
 * 提交变更文件的纯逻辑：状态徽标、二进制判定、目录树组装、筛选与可审查路径收集。
 * 抽成无副作用的纯函数，便于单测（不依赖组件与网络）。
 */

/** 后端 ChangedFile DTO 的前端孪生类型 */
export interface ChangedFile {
  path: string
  previousPath?: string | null
  status?: string | null
  additions?: number | null
  deletions?: number | null
  changes?: number | null
  patch?: string | null
  /** 是否可审查：**由后端唯一判定**（`ReviewableFiles`），前端只读，不自己维护扩展名表 */
  reviewable?: boolean
}

export interface ChangedFileNode {
  /** 目录为目录路径，文件为完整文件路径 */
  key: string
  /** 展示名（末段） */
  title: string
  isLeaf: boolean
  children?: ChangedFileNode[]
  file?: ChangedFile
}

export interface StatusMeta {
  /** 单字母徽标：A/M/D/R */
  letter: string
  /** Ant Design 标签色 */
  color: string
  label: string
}

const STATUS_META: Record<string, StatusMeta> = {
  added: { letter: 'A', color: 'green', label: '新增' },
  modified: { letter: 'M', color: 'blue', label: '修改' },
  removed: { letter: 'D', color: 'red', label: '删除' },
  renamed: { letter: 'R', color: 'orange', label: '重命名' }
}

const UNKNOWN_META: StatusMeta = { letter: '?', color: 'default', label: '未知' }

/** 是否重命名：状态为 renamed，或宿主给了原路径 */
export function isRenamed(file: ChangedFile): boolean {
  return file.status === 'renamed' || !!file.previousPath
}

export function statusMeta(file: ChangedFile): StatusMeta {
  if (isRenamed(file)) {
    return STATUS_META.renamed
  }
  const status = (file.status || '').toLowerCase()
  return STATUS_META[status] || UNKNOWN_META
}

export function hasPatch(file: ChangedFile): boolean {
  return !!file.patch && file.patch.trim().length > 0
}

/**
 * 是否可审查 —— **直接读后端下发的字段**，前端不再自己判定。
 *
 * 这里原先维护了一份二进制扩展名表并据此置灰（`isProbablyBinary`）。两个问题：
 * ① 黑名单永远补不全（漏了 `.exe`/`.dll`/`.so`/`.bin`/`.7z` …）；
 * ② 判定分散在前端两处，早晚漂移。
 * 现在判定只在后端 `ReviewableFiles` 一处，前面两个视图与后端编排层读的是同一个结论。
 */
export function isReviewable(file: ChangedFile): boolean {
  return file.reviewable === true
}

export function fileName(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1)
}

/** 按路径稳定排序（后端已排序，前端再兜一道，保证渲染可复现） */
export function sortByPath(files: ChangedFile[]): ChangedFile[] {
  return [...files].sort((a, b) => a.path.localeCompare(b.path))
}

export function filterChangedFiles(files: ChangedFile[], keyword?: string, status?: string): ChangedFile[] {
  const kw = (keyword || '').trim().toLowerCase()
  const st = (status || '').trim().toLowerCase()
  return files.filter((f) => {
    if (kw && !f.path.toLowerCase().includes(kw)) {
      return false
    }
    if (!st) {
      return true
    }
    return st === 'renamed' ? isRenamed(f) : (f.status || '').toLowerCase() === st
  })
}

/** 扁平列表 → 目录树（按 path 排序保证父节点先于子节点） */
export function buildChangedFileTree(files: ChangedFile[]): ChangedFileNode[] {
  const roots: ChangedFileNode[] = []
  const dirIndex = new Map<string, ChangedFileNode>()

  for (const file of sortByPath(files)) {
    const segments = file.path.split('/')
    let parentChildren = roots
    let prefix = ''
    for (let i = 0; i < segments.length - 1; i++) {
      prefix = prefix ? `${prefix}/${segments[i]}` : segments[i]
      let dir = dirIndex.get(prefix)
      if (!dir) {
        dir = { key: prefix, title: segments[i], isLeaf: false, children: [] }
        dirIndex.set(prefix, dir)
        parentChildren.push(dir)
      }
      parentChildren = dir!.children!
    }
    parentChildren.push({
      key: file.path,
      title: segments[segments.length - 1],
      isLeaf: true,
      file
    })
  }
  return roots
}

/** 收集可勾选的叶子路径（跳过不可审查项） */
export function collectReviewablePaths(nodes: ChangedFileNode[]): string[] {
  const out: string[] = []
  const walk = (list: ChangedFileNode[]) => {
    for (const node of list) {
      if (node.isLeaf && node.file && isReviewable(node.file)) {
        out.push(node.key)
      }
      if (node.children?.length) {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return out
}

/** 收集全部叶子路径（含不可审查项，用于展示计数） */
export function collectAllLeafPaths(nodes: ChangedFileNode[]): string[] {
  const out: string[] = []
  const walk = (list: ChangedFileNode[]) => {
    for (const node of list) {
      if (node.isLeaf) {
        out.push(node.key)
      }
      if (node.children?.length) {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return out
}

/** 目录节点下的叶子数量（用于"全选/半选"判定与展示） */
export function countLeaves(node: ChangedFileNode): number {
  if (node.isLeaf) {
    return 1
  }
  return (node.children || []).reduce((sum, child) => sum + countLeaves(child), 0)
}

/** 变更清单统计（用于确认框与提示） */
export function summarize(files: ChangedFile[]): {
  total: number
  reviewable: number
  nonReviewable: number
  additions: number
  deletions: number
} {
  let reviewable = 0
  let nonReviewable = 0
  let additions = 0
  let deletions = 0
  for (const f of files) {
    if (isReviewable(f)) {
      reviewable++
    } else {
      nonReviewable++
    }
    additions += f.additions || 0
    deletions += f.deletions || 0
  }
  return { total: files.length, reviewable, nonReviewable, additions, deletions }
}
