/**
 * 英文枚举 → 中文释义（C4）。
 *
 * **口径（与负责人确认）**：界面**保留英文枚举**（与后端 `AnalyzerTypes`、与日志逐字对应，
 * 便于对文档/排查），但一律用 tooltip 给出中文 —— 否则评审组看到 `MAJOR`、`diff-method`
 * 不知道是什么；而 `PatternResult` 的置信度本来就是中文，两套风格并存更显杂乱。
 *
 * 约定：
 * - 认不出的值**原样返回**，不猜、不吞（后端新增枚举时界面不会被"翻译"成错的词）；
 * - `*Tip()` 返回空串表示"没有可补充的释义"，调用方据此决定要不要挂 tooltip。
 */

/** 问题级别。LLM 侧是 MAJOR|MINOR|INFO；SonarQube 侧还有 BLOCKER|CRITICAL —— 两套都要认 */
const SEVERITY_TEXT: Record<string, string> = {
  BLOCKER: '阻断（必须改）',
  CRITICAL: '严重',
  MAJOR: '重要',
  MINOR: '次要',
  INFO: '提示'
}

export function severityText(severity?: string | null): string {
  const key = (severity || '').trim().toUpperCase()
  return SEVERITY_TEXT[key] || ''
}

/** 例：`MAJOR · 重要`；无释义时返回空串 */
export function severityTip(severity?: string | null): string {
  const text = severityText(severity)
  return text ? `${severity} · ${text}` : ''
}

/** 变更类型：与 `utils/changedFiles` 的 STATUS_META 同口径 */
const CHANGE_TYPE_TEXT: Record<string, string> = {
  added: '新增',
  modified: '修改',
  removed: '删除',
  renamed: '重命名'
}

export function changeTypeText(type?: string | null): string {
  return CHANGE_TYPE_TEXT[(type || '').trim()] || ''
}

/** 例：`added · 新增`；无释义时返回空串 */
export function changeTypeTip(type?: string | null): string {
  const text = changeTypeText(type)
  return text ? `${type} · ${text}` : ''
}

/** 审查单元粒度：取值来自后端 Chunker / DiffUnitBuilder，认不出的原样显示 */
const UNIT_KIND_TEXT: Record<string, string> = {
  'diff-method': '按方法切分（变更行落在方法体内）',
  'diff-hunk': '按变更块切分（该段未做方法级补全）',
  'full-file': '整个文件（无 diff 时回退全文）',
  merged: '多文件合并成一次调用',
  class: '按类切分',
  method: '按方法切分',
  file: '整个文件'
}

export function unitKindText(kind?: string | null): string {
  return UNIT_KIND_TEXT[(kind || '').trim()] || ''
}

/** 例：`diff-method · 按方法切分…`；无释义时返回空串 */
export function unitKindTip(kind?: string | null): string {
  const text = unitKindText(kind)
  return text ? `${kind} · ${text}` : ''
}
