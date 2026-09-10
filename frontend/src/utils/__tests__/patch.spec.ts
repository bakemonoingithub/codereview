import { describe, expect, it } from 'vitest'
import { buildExtendData, countPatchLines, toRenderablePatch } from '../patch'

describe('patch 头补齐（GitHub 的 patch 从 @@ 开始，解析器需要完整头）', () => {
  it('修改文件补 a/ 与 b/', () => {
    const out = toRenderablePatch('src/A.java', '@@ -1 +1 @@\n-a\n+b', 'modified')
    expect(out).toBe('--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n-a\n+b')
  })

  it('新增文件旧侧为 /dev/null', () => {
    const out = toRenderablePatch('src/New.java', '@@ -0,0 +1 @@\n+x', 'added')
    expect(out.startsWith('--- /dev/null\n+++ b/src/New.java\n')).toBe(true)
  })

  it('删除文件新侧为 /dev/null', () => {
    const out = toRenderablePatch('src/Gone.java', '@@ -1 +0,0 @@\n-x', 'removed')
    expect(out.startsWith('--- a/src/Gone.java\n+++ /dev/null\n')).toBe(true)
  })

  it('已有头则原样返回（幂等）', () => {
    const withHeader = '--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b'
    expect(toRenderablePatch('x', withHeader)).toBe(withHeader)
    expect(toRenderablePatch('x', toRenderablePatch('x', '@@ -1 +1 @@\n-a\n+b'))).toContain('@@ -1 +1 @@')
  })

  it('diff --git 开头的完整 patch 不重复加头', () => {
    const full = 'diff --git a/x b/x\nindex 1..2 100644\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b'
    expect(toRenderablePatch('x', full)).toBe(full)
  })

  it('空 patch 返回空串', () => {
    expect(toRenderablePatch('x', '')).toBe('')
    expect(toRenderablePatch('x', null)).toBe('')
    expect(toRenderablePatch('x', '   ')).toBe('')
  })
})

describe('extendData 组装（行级评论按新文件行号锚定）', () => {
  it('按行号分组，同一行多条聚合', () => {
    const out = buildExtendData([
      { line: 10, data: 'a' },
      { line: 10, data: 'b' },
      { line: 20, data: 'c' }
    ])
    expect(Object.keys(out.newFile).sort()).toEqual(['10', '20'])
    expect(out.newFile['10'].data).toEqual(['a', 'b'])
    expect(out.newFile['20'].data).toEqual(['c'])
  })

  it('无行号的评论被跳过（无法锚定就不展示在 diff 上）', () => {
    const out = buildExtendData([{ line: null, data: 'x' }, { data: 'y' }])
    expect(Object.keys(out.newFile)).toHaveLength(0)
  })

  it('空输入得到空映射', () => {
    expect(buildExtendData([]).newFile).toEqual({})
  })
})

describe('patch 增删行统计', () => {
  it('忽略 --- / +++ 头行', () => {
    const patch = '--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-a\n+b\n c'
    expect(countPatchLines(patch)).toEqual({ additions: 1, deletions: 1 })
  })

  it('空 patch 记 0', () => {
    expect(countPatchLines(null)).toEqual({ additions: 0, deletions: 0 })
  })
})
