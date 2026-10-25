/**
 * 问题/结果引用的公共形状与分组规则。
 *
 * 一处引用始终属于某个固定版本侧（FILE / BEFORE / AFTER）；同一文件的定义
 * 不能只看 path，必须同时比较 sha 与 side，否则前后侧行号会被混到一起。
 */
export interface CodeRange {
  startLine: number
  endLine: number
}

export interface CodeReference extends CodeRange {
  materialId?: string
  /** 可选引用标识，例如 Agent 正文中的 E1。 */
  label?: string
  path: string
  sha?: string
  side: string
}

export interface CodeReferencePayload extends CodeReference {
  /** 当前问题/结果在同一文件、同一版本侧的全部引用（含证据），已去重排序。 */
  references: CodeReference[]
  evidenceCount?: number
  scope: 'issue' | 'result'
}

/** 是否属于同一文件的同一版本侧。 */
export function sameFileSide(a: CodeReference, b: CodeReference): boolean {
  return a.path === b.path && (a.sha || '') === (b.sha || '') && a.side === b.side
}

function normalizeRange<T extends CodeRange>(range: T): T {
  return {
    ...range,
    startLine: Math.min(range.startLine, range.endLine),
    endLine: Math.max(range.startLine, range.endLine)
  }
}

/**
 * 去重并按起始行排序。
 *
 * 去重键包含文件与版本侧：不同文件的同号行不能互相吞掉；同一范围的
 * location 与 evidence 重复出现时只保留一份。
 */
export function dedupeReferences(references: CodeReference[]): CodeReference[] {
  const seen = new Set<string>()
  const result: CodeReference[] = []
  for (const raw of references) {
    if (!raw || !raw.path) continue
    const ref = normalizeRange(raw)
    const key = [ref.path, ref.sha || '', ref.side, ref.startLine, ref.endLine].join('|')
    if (seen.has(key)) continue
    seen.add(key)
    result.push(ref)
  }
  return result.sort((a, b) => a.startLine - b.startLine || a.endLine - b.endLine)
}

/** 统一构建同一文件、同一固定版本侧的阅读上下文，保留点击处的材料。 */
export function buildCodeReferencePayload(
  target: CodeReference,
  all: CodeReference[],
  scope: CodeReferencePayload['scope'],
  evidence: CodeReference[] = []
): CodeReferencePayload {
  const selected = normalizeRange(target)
  const references = dedupeReferences(all.filter((ref) => sameFileSide(ref, selected)))
  const index = references.findIndex((ref) => ref.startLine === selected.startLine && ref.endLine === selected.endLine)
  if (index >= 0) references[index] = selected
  else {
    references.push(selected)
    references.sort((a, b) => a.startLine - b.startLine || a.endLine - b.endLine)
  }
  const evidenceCount = dedupeReferences(evidence.filter((ref) => sameFileSide(ref, selected))).length
  return { ...selected, references, evidenceCount, scope }
}

/** 当前行是否落在某个范围内（首尾包含）。 */
export function inRange(line: number, range: CodeRange): boolean {
  return line >= range.startLine && line <= range.endLine
}
