/** 与后端 TaskNames 相同：空名称显示为「审查任务 」加任务 ID。 */
export function taskLabel(name: string | null | undefined, id: string): string {
  const trimmed = name?.trim()
  return trimmed || `审查任务 ${id}`
}

/**
 * 路径最后一段。`/` 与 `\` 都是分隔符，连续分隔符与末尾分隔符忽略。
 * 去掉两侧空白和开头的 Windows 盘符。与后端 ReviewableFiles.baseName 同一口径。
 */
export function baseName(path: string | null | undefined): string {
  if (!path || !path.trim()) return ''
  const normalized = path.trim().replace(/^[A-Za-z]:/, '')
  const parts = normalized.split(/[/\\]/).filter(Boolean)
  return parts.length ? parts[parts.length - 1] : ''
}

/** 路径只取文件名；没有可识别的文件名时用报告里的占位文案。 */
export function fileName(path: string | null | undefined): string {
  if (!path || !path.trim()) return 'Agent Markdown'
  return baseName(path) || 'Agent Markdown'
}
