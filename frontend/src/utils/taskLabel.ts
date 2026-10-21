/** 与后端 TaskNames 相同：空名称显示为「审查任务 」加任务 ID。 */
export function taskLabel(name: string | null | undefined, id: string): string {
  const trimmed = name?.trim()
  return trimmed || `审查任务 ${id}`
}

/** 路径只取文件名；没有路径时用报告里的占位文案。 */
export function fileName(path: string | null | undefined): string {
  if (!path) return 'Agent Markdown'
  const parts = path.split('/').filter(Boolean)
  return parts.length ? parts[parts.length - 1] : path
}
