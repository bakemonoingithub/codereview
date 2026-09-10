import { describe, expect, it } from 'vitest'
import {
  buildChangedFileTree,
  collectAllLeafPaths,
  collectReviewablePaths,
  countLeaves,
  extensionOf,
  filterChangedFiles,
  isProbablyBinary,
  isRenamed,
  isReviewable,
  sortByPath,
  statusMeta,
  summarize,
  type ChangedFile
} from '../changedFiles'

function file(path: string, status = 'modified', patch: string | null = '@@ -1 +1 @@'): ChangedFile {
  return { path, status, patch, additions: 1, deletions: 1 }
}

describe('状态徽标', () => {
  it('按状态给出 A/M/D/R 与颜色', () => {
    expect(statusMeta(file('a', 'added')).letter).toBe('A')
    expect(statusMeta(file('a', 'modified')).letter).toBe('M')
    expect(statusMeta(file('a', 'removed')).letter).toBe('D')
    expect(statusMeta(file('a', 'renamed')).letter).toBe('R')
    expect(statusMeta(file('a', 'removed')).color).toBe('red')
  })

  it('未知状态不崩，回落到 ?', () => {
    expect(statusMeta(file('a', 'copied')).letter).toBe('?')
    expect(statusMeta({ path: 'a' }).letter).toBe('?')
  })

  it('带 previousPath 即视为重命名（Gitea 不给 renamed 状态时要靠它）', () => {
    const renamed: ChangedFile = { path: 'new/A.java', previousPath: 'old/A.java', status: 'added' }
    expect(isRenamed(renamed)).toBe(true)
    expect(statusMeta(renamed).letter).toBe('R')
  })
})

describe('二进制判定与可审查性', () => {
  it('提取扩展名', () => {
    expect(extensionOf('src/main/A.java')).toBe('.java')
    expect(extensionOf('Makefile')).toBe('')
    expect(extensionOf('.gitignore')).toBe('')
  })

  it('图片/压缩包等判为二进制', () => {
    expect(isProbablyBinary('assets/logo.png')).toBe(true)
    expect(isProbablyBinary('lib/x.jar')).toBe(true)
    expect(isProbablyBinary('src/main/A.java')).toBe(false)
  })

  it('有 patch 一定可审查', () => {
    expect(isReviewable(file('src/A.java'))).toBe(true)
  })

  it('无 patch 时：文本文件仍可审（后端退化为审查完整文件），二进制禁用', () => {
    expect(isReviewable(file('src/main/A.java', 'modified', null))).toBe(true)
    expect(isReviewable(file('assets/logo.png', 'modified', null))).toBe(false)
  })

  it('空 patch 视为无 patch', () => {
    expect(isReviewable(file('assets/logo.png', 'modified', '   '))).toBe(false)
  })
})

describe('目录树组装', () => {
  const files = [
    file('src/main/java/com/x/B.java'),
    file('pom.xml'),
    file('src/main/java/com/x/A.java'),
    file('src/main/resources/app.yml')
  ]

  it('按路径排序并逐层建目录', () => {
    const tree = buildChangedFileTree(files)
    expect(tree.map((n) => n.title)).toEqual(['pom.xml', 'src'])
    const src = tree[1]
    expect(src.isLeaf).toBe(false)
    const main = src.children![0]
    expect(main.title).toBe('main')
    const javaDir = main.children!.find((n) => n.title === 'java')!
    const comDir = javaDir.children![0]
    expect(comDir.title).toBe('com')
    const xDir = comDir.children![0]
    expect(xDir.title).toBe('x')
    expect(xDir.children!.map((n) => n.title)).toEqual(['A.java', 'B.java'])
  })

  it('叶子带 file，目录不带', () => {
    const tree = buildChangedFileTree(files)
    expect(tree[0].file?.path).toBe('pom.xml')
    expect(tree[1].file).toBeUndefined()
  })

  it('统计叶子数与收集路径', () => {
    const tree = buildChangedFileTree(files)
    expect(countLeaves(tree[1])).toBe(3)
    expect(collectAllLeafPaths(tree)).toHaveLength(4)
    expect(collectReviewablePaths(tree)).toHaveLength(4)
  })

  it('不可审查的叶子不出现在可勾选路径里', () => {
    const tree = buildChangedFileTree([file('src/A.java'), file('assets/logo.png', 'modified', null)])
    expect(collectAllLeafPaths(tree)).toHaveLength(2)
    expect(collectReviewablePaths(tree)).toEqual(['src/A.java'])
  })

  it('空列表得到空树', () => {
    expect(buildChangedFileTree([])).toEqual([])
  })
})

describe('筛选', () => {
  const files = [
    file('src/main/A.java', 'modified'),
    file('src/main/B.java', 'added'),
    file('src/old/C.java', 'removed'),
    { path: 'src/new/D.java', status: 'added', previousPath: 'src/old/D.java', patch: '@@' } as ChangedFile
  ]

  it('按关键词过滤路径', () => {
    expect(filterChangedFiles(files, 'main', '').map((f) => f.path)).toEqual([
      'src/main/A.java',
      'src/main/B.java'
    ])
  })

  it('按状态过滤', () => {
    expect(filterChangedFiles(files, '', 'added').map((f) => f.path)).toEqual([
      'src/main/B.java',
      'src/new/D.java'
    ])
    expect(filterChangedFiles(files, '', 'removed').map((f) => f.path)).toEqual(['src/old/C.java'])
  })

  it('重命名筛选包含"带 previousPath 但状态非 renamed"的文件', () => {
    expect(filterChangedFiles(files, '', 'renamed').map((f) => f.path)).toEqual(['src/new/D.java'])
  })

  it('关键词不区分大小写，且可与状态叠加', () => {
    expect(filterChangedFiles(files, 'B.JAVA', 'added').map((f) => f.path)).toEqual(['src/main/B.java'])
  })

  it('空条件返回全部', () => {
    expect(filterChangedFiles(files)).toHaveLength(4)
  })
})

describe('汇总', () => {
  it('统计总数/可审查数/二进制数与增删行', () => {
    const files: ChangedFile[] = [
      { path: 'src/A.java', status: 'modified', patch: '@@', additions: 10, deletions: 2 },
      { path: 'assets/logo.png', status: 'modified', patch: null, additions: 0, deletions: 0 },
      { path: 'src/B.java', status: 'added', patch: '@@', additions: 5, deletions: 0 }
    ]
    expect(summarize(files)).toEqual({
      total: 3,
      reviewable: 2,
      binary: 1,
      additions: 15,
      deletions: 2
    })
  })

  it('缺省增删行按 0 处理', () => {
    expect(summarize([{ path: 'src/A.java' }]).additions).toBe(0)
  })
})

describe('排序不修改入参', () => {
  it('返回新数组', () => {
    const input = [file('b'), file('a')]
    const sorted = sortByPath(input)
    expect(sorted.map((f) => f.path)).toEqual(['a', 'b'])
    expect(input.map((f) => f.path)).toEqual(['b', 'a'])
  })
})
