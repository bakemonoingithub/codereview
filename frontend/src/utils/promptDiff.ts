/**
 * 提示词版本对比：把后端返回的行列表转成 unified diff 文本。
 *
 * 后端 `GET /prompts/{id}/versions/diff` 给的是扁平行列表
 * （`{type: same|add|remove, text, oldLine, newLine}`，由 `common/TextDiff` 的 LCS 算出），
 * 而 `@git-diff-view/vue` 需要 unified diff 文本，故在此转换。
 *
 * ⚠️ 两条硬约束（由组件的解析器行为决定，改动前务必看这里）：
 * 1. **必须带 `--- a/…` 与 `+++ b/…` 头**：`parseDiffHeader` 找不到 `+++` 会返回 null，
 *    结果是解析出 0 个 hunk、视图全空白；而正文里若有以 `++` 开头的行（渲染成 `+++ …`），
 *    无头时还会被误认成头行并抛 `Invalid hunk header format`。带上头同时也消除了这个崩溃风险。
 * 2. **单个 hunk 覆盖全文**：按设计不折叠未变更区域，让用户看到完整两版。
 */

export interface PromptDiffRow {
  /** same | add | remove */
  type: string
  text: string
  oldLine?: number | null
  newLine?: number | null
}

export interface UnifiedPatchNames {
  /** 仅用于 patch 头（如 `prompt-v3`，会拼成 `a/prompt-v3`），不要放中文或空格 */
  oldName: string
  newName: string
}

/** 该行列表里是否存在变更（两版是否不同） */
export function hasChanges(rows: PromptDiffRow[]): boolean {
  return (rows || []).some((row) => {
    const type = (row.type || '').toLowerCase()
    return type === 'add' || type === 'remove'
  })
}

/** 行尾换行符归一化：源文件若是 CRLF，行文本会带尾随 \r，直接拼进 patch 会污染解析 */
function stripTrailingLineBreaks(text: string): string {
  return (text ?? '').replace(/[\r\n]+$/, '')
}

export function toUnifiedPatch(rows: PromptDiffRow[], names: UnifiedPatchNames): string {
  const list = rows || []
  if (!list.length) {
    return ''
  }

  // 新侧行号非空 → 该行存在于新版；旧侧同理。同一个 same 行两边都算。
  const oldCount = list.filter((r) => r.oldLine !== null && r.oldLine !== undefined).length
  const newCount = list.filter((r) => r.newLine !== null && r.newLine !== undefined).length
  if (oldCount === 0 && newCount === 0) {
    return ''
  }

  const lines: string[] = [
    `--- a/${names.oldName}`,
    `+++ b/${names.newName}`,
    // 空侧按 git 惯例写 0,0（如"旧版正文为空"的情形），否则从第 1 行起
    `@@ -${oldCount === 0 ? 0 : 1},${oldCount} +${newCount === 0 ? 0 : 1},${newCount} @@`
  ]
  for (const row of list) {
    const text = stripTrailingLineBreaks(row.text)
    const type = (row.type || '').toLowerCase()
    if (type === 'add') {
      lines.push(`+${text}`)
    } else if (type === 'remove') {
      lines.push(`-${text}`)
    } else {
      lines.push(` ${text}`)
    }
  }
  return lines.join('\n')
}
