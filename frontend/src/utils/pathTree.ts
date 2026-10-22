export interface FileTreeEntry {
  path: string
  reviewable?: boolean
  status?: string
  oldPath?: string | null
}

export interface FileTreeNode {
  key: string
  title: string
  isLeaf: boolean
  path?: string
  reviewable?: boolean
  status?: string
  oldPath?: string | null
  children?: FileTreeNode[]
}

/**
 * 把仓库路径列表构造成 Ant Design Tree 结构。
 *
 * 文件夹与文件都使用完整路径作 key；同一路径重复出现时以最后一条为准
 * （提交详情可能重复列出重命名两侧，调用方应先合并）。
 */
export function buildPathTree(entries: FileTreeEntry[]): FileTreeNode[] {
  const root: FileTreeNode = { key: '', title: '', isLeaf: false, children: [] }
  for (const entry of entries) {
    if (!entry.path) {
      continue
    }
    const segments = entry.path.split('/').filter(Boolean)
    let current = root
    segments.forEach((segment, index) => {
      const leaf = index === segments.length - 1
      const key = segments.slice(0, index + 1).join('/')
      let next = current.children?.find((node) => node.key === key)
      if (!next) {
        next = {
          key,
          title: segment,
          isLeaf: leaf,
          children: leaf ? undefined : [],
          path: leaf ? entry.path : undefined,
          reviewable: leaf ? entry.reviewable !== false : undefined,
          status: leaf ? entry.status : undefined,
          oldPath: leaf ? entry.oldPath : undefined
        }
        current.children!.push(next)
      } else if (leaf) {
        next.isLeaf = true
        next.path = entry.path
        next.reviewable = entry.reviewable !== false
        next.status = entry.status
        next.oldPath = entry.oldPath
      }
      current = next
    })
  }
  return sortNodes(root.children ?? [])
}

function sortNodes(nodes: FileTreeNode[]): FileTreeNode[] {
  nodes.sort((left, right) => {
    if (left.isLeaf !== right.isLeaf) {
      return left.isLeaf ? 1 : -1
    }
    return left.title.localeCompare(right.title)
  })
  for (const node of nodes) {
    if (node.children) {
      node.children = sortNodes(node.children)
    }
  }
  return nodes
}

/** 只保留匹配关键字或包含匹配后代的节点；空关键字返回原树。 */
export function filterPathTree(nodes: FileTreeNode[], keyword: string): FileTreeNode[] {
  const text = keyword.trim().toLowerCase()
  if (!text) {
    return nodes
  }
  const result: FileTreeNode[] = []
  for (const node of nodes) {
    if (node.isLeaf) {
      if (node.key.toLowerCase().includes(text)) {
        result.push(node)
      }
      continue
    }
    const children = filterPathTree(node.children ?? [], text)
    if (children.length) {
      result.push({ ...node, children })
    }
  }
  return result
}

/** 树中的全部文件 key；勾选父节点时不会再包含父目录 key。 */
export function leafKeys(nodes: FileTreeNode[]): string[] {
  const keys: string[] = []
  const walk = (items: FileTreeNode[]) => {
    for (const node of items) {
      if (node.isLeaf) {
        keys.push(node.key)
      } else {
        walk(node.children ?? [])
      }
    }
  }
  walk(nodes)
  return keys
}

/** Ant Tree 勾选结果中只保留实际文件 key。 */
export function checkedFileKeys(checked: (string | number)[], tree: FileTreeNode[]): string[] {
  const leaves = new Set(leafKeys(tree))
  return checked.map(String).filter((key) => leaves.has(key))
}

/**
 * 合并搜索结果里的勾选：搜索树只包含部分文件，回写不能抹掉筛选外的已选。
 */
export function mergeCheckedSelection(previous: string[], visible: string[],
                                      checkedVisible: string[]): string[] {
  const visibleSet = new Set(visible)
  const hidden = previous.filter((path) => !visibleSet.has(path))
  return Array.from(new Set([...hidden, ...checkedVisible]))
}
