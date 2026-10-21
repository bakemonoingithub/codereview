import { describe, expect, it } from 'vitest'
import { buildPathTree, checkedFileKeys, filterPathTree, leafKeys } from '@/utils/pathTree'

describe('仓库路径树', () => {
  const tree = buildPathTree([
    { path: 'src/main/java/A.java', reviewable: true },
    { path: 'src/main/java/B.java', reviewable: true, status: 'added' },
    { path: 'README.md', reviewable: true }
  ])

  it('文件夹在前、文件在后，并保留文件元数据', () => {
    expect(tree.map((node) => node.title)).toEqual(['src', 'README.md'])
    expect(tree[0].children?.[0].title).toBe('main')
    const java = tree[0].children?.[0].children?.[0].children?.[0]
    expect(java?.title).toBe('A.java')
    expect(java?.isLeaf).toBe(true)
  })

  it('过滤时保留匹配文件及其父目录', () => {
    const filtered = filterPathTree(tree, 'B.java')
    expect(filtered).toHaveLength(1)
    expect(leafKeys(filtered)).toEqual(['src/main/java/B.java'])
  })

  it('勾选结果只保留实际文件 key，不把目录当目标', () => {
    const leaves = leafKeys(tree)
    expect(leaves).toEqual(['src/main/java/A.java', 'src/main/java/B.java', 'README.md'])
    expect(checkedFileKeys(['src', ...leaves], tree)).toEqual(leaves)
    expect(checkedFileKeys(['src/main/java'], tree)).toEqual([])
  })

  it('状态保留在叶子上，供范围弹窗显示新增/修改', () => {
    const filtered = filterPathTree(tree, 'B.java')
    const leaf = filtered[0].children?.[0].children?.[0].children?.[0]
    expect(leaf?.status).toBe('added')
  })
})
