import { describe, expect, it } from 'vitest'
import { hasChanges, toUnifiedPatch, type PromptDiffRow } from '../promptDiff'

const names = { oldName: 'prompt-v1', newName: 'prompt-v2' }

function row(type: string, text: string, oldLine?: number | null, newLine?: number | null): PromptDiffRow {
  return { type, text, oldLine, newLine }
}

describe('patch 头（缺头会让组件解析出 0 个 hunk、视图全空白，甚至抛异常）', () => {
  it('始终以 --- a/ 与 +++ b/ 开头', () => {
    const patch = toUnifiedPatch([row('same', 'a', 1, 1), row('add', 'b', null, 2)], names)

    expect(patch.startsWith('--- a/prompt-v1\n')).toBe(true)
    expect(patch.split('\n')[1]).toBe('+++ b/prompt-v2')
  })

  it('正文里以 ++ 开头的行被当作正文，不会挤掉头', () => {
    const patch = toUnifiedPatch(
      [row('same', 'keep', 1, 1), row('add', '++ double plus', null, 2)],
      names
    )
    const lines = patch.split('\n')

    expect(lines[0]).toBe('--- a/prompt-v1')
    expect(lines[1]).toBe('+++ b/prompt-v2')
    expect(lines[2]).toBe('@@ -1,1 +1,2 @@')
    expect(lines[3]).toBe(' keep')
    expect(lines[4]).toBe('+++ double plus')
  })
})

describe('单个覆盖全文的 hunk', () => {
  it('行号与计数由行列表推导', () => {
    const patch = toUnifiedPatch(
      [
        row('same', 'a', 1, 1),
        row('remove', 'b', 2, null),
        row('add', 'B', null, 2),
        row('same', 'c', 3, 3)
      ],
      names
    )

    expect(patch.split('\n')).toEqual([
      '--- a/prompt-v1',
      '+++ b/prompt-v2',
      '@@ -1,3 +1,3 @@',
      ' a',
      '-b',
      '+B',
      ' c'
    ])
  })

  it('全部未变更时仍产出完整 patch（不折叠，用户要看到全文）', () => {
    const rows = [row('same', 'a', 1, 1), row('same', 'b', 2, 2)]
    const patch = toUnifiedPatch(rows, names)

    expect(patch.split('\n').slice(2)).toEqual(['@@ -1,2 +1,2 @@', ' a', ' b'])
    expect(hasChanges(rows)).toBe(false)
  })

  it('旧版为空时按 git 惯例写 -0,0', () => {
    const patch = toUnifiedPatch([row('add', 'first', null, 1)], names)

    expect(patch.split('\n')[2]).toBe('@@ -0,0 +1,1 @@')
  })

  it('新版为空时按 git 惯例写 +0,0', () => {
    const patch = toUnifiedPatch([row('remove', 'last', 1, null)], names)

    expect(patch.split('\n')[2]).toBe('@@ -1,1 +0,0 @@')
  })

  it('空行保留前导标记（上下文行是一个空格）', () => {
    const patch = toUnifiedPatch([row('same', '', 1, 1), row('add', '', null, 2)], names)

    expect(patch.split('\n').slice(3)).toEqual([' ', '+'])
  })
})

describe('边界与归一化', () => {
  it('空输入返回空串（调用方据此显示空态）', () => {
    expect(toUnifiedPatch([], names)).toBe('')
    expect(toUnifiedPatch(null as any, names)).toBe('')
  })

  it('两侧都没有行号时返回空串', () => {
    expect(toUnifiedPatch([row('same', 'a', null, null)], names)).toBe('')
  })

  it('CRLF 的尾随 \\r 被剥掉，不污染 patch', () => {
    const patch = toUnifiedPatch([row('same', 'a\r', 1, 1), row('add', 'b\r', null, 2)], names)

    expect(patch).not.toContain('\r')
    expect(patch.split('\n').slice(3)).toEqual([' a', '+b'])
  })

  it('大小写不敏感地识别行类型', () => {
    const patch = toUnifiedPatch([row('ADD', 'x', null, 1)], names)

    expect(patch.split('\n')[3]).toBe('+x')
  })
})

describe('两版是否相同', () => {
  it('只有 same 行即视为相同', () => {
    expect(hasChanges([row('same', 'a', 1, 1)])).toBe(false)
  })

  it('出现 add 或 remove 即视为有变更', () => {
    expect(hasChanges([row('same', 'a', 1, 1), row('add', 'b', null, 2)])).toBe(true)
    expect(hasChanges([row('remove', 'a', 1, null)])).toBe(true)
  })

  it('空列表视为无变更', () => {
    expect(hasChanges([])).toBe(false)
  })
})
