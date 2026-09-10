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

/** 明确按二进制处理的扩展名（无 patch 时不可审查） */
const BINARY_EXTENSIONS = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.bmp', '.ico', '.webp',
  '.zip', '.gz', '.tar', '.jar', '.war', '.class', '.pdf',
  '.woff', '.woff2', '.ttf', '.eot', '.otf',
  '.mp3', '.mp4', '.avi', '.mov', '.xlsx', '.xls', '.docx', '.doc', '.pptx'
])

export function extensionOf(path: string): string {
  const name = path.slice(path.lastIndexOf('/') + 1)
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot).toLowerCase() : ''
}

export function isProbablyBinary(path: string): boolean {
  return BINARY_EXTENSIONS.has(extensionOf(path))
}

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
 * 是否可勾选审查。
 * 无 patch 时：文本文件仍可审（后端会退化为审查完整文件）；二进制文件禁用（Q37）。
 */
export function isReviewable(file: ChangedFile): boolean {
  return hasPatch(file) || !isProbablyBinary(file.path)
}

/** 不可审查的原因文案 */
export function notReviewableReason(file: ChangedFile): string {
  return hasPatch(file) ? '' : '无可用 diff（二进制或改动过大），已禁用勾选'
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
  binary: number
  additions: number
  deletions: number
} {
  let reviewable = 0
  let binary = 0
  let additions = 0
  let deletions = 0
  for (const f of files) {
    if (isReviewable(f)) {
      reviewable++
    } else {
      binary++
    }
    additions += f.additions || 0
    deletions += f.deletions || 0
  }
  return { total: files.length, reviewable, binary, additions, deletions }
}
