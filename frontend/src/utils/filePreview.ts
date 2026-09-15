/**
 * 文件预览的纯函数：**扩展名 → 语言**、**内容 → 可渲染的 Markdown 围栏**。
 *
 * 刻意不碰 DOM、不碰 marked：这段逻辑最容易出错（围栏提前闭合、语言标签不匹配），
 * 抽成纯函数后可以用最普通的单测覆盖，不必依赖 happy-dom 下并不可信的 DOMPurify。
 */

/**
 * 扩展名 → highlight.js 语言名。
 *
 * 口径与后端 `ReviewableFiles` 的白名单对齐：白名单里的类型在这里都有归属，
 * 拿不到归属的（`.txt`/`.csv`/`.log`/`.rst`/`.adoc`）返回空串 —— 渲染成无语言的代码块即可，
 * 不要瞎猜成某种语言（猜错反而满屏错误着色）。
 */
const EXTENSION_TO_LANGUAGE: Record<string, string> = {
  java: 'java',
  kt: 'kotlin',
  kts: 'kotlin',
  scala: 'scala',
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  vue: 'xml',
  py: 'python',
  go: 'go',
  cs: 'csharp',
  cpp: 'cpp',
  c: 'c',
  h: 'c',
  rb: 'ruby',
  php: 'php',
  sql: 'sql',
  sh: 'bash',
  bat: 'dos',
  ps1: 'powershell',
  r: 'r',
  swift: 'swift',
  dart: 'dart',
  lua: 'lua',
  pl: 'perl',
  m: 'objectivec',
  mm: 'objectivec',
  asm: 'x86asm',
  s: 'x86asm',
  xml: 'xml',
  html: 'xml',
  htm: 'xml',
  jsp: 'xml',
  ftl: 'xml',
  yml: 'yaml',
  yaml: 'yaml',
  properties: 'properties',
  json: 'json',
  toml: 'ini',
  ini: 'ini',
  conf: 'ini',
  env: 'properties',
  gradle: 'groovy',
  md: 'markdown',
  css: 'css',
  less: 'less',
  scss: 'scss',
  sass: 'scss',
  styl: 'stylus'
}

/** 取小写扩展名（不含点）；无扩展名或点号开头返回空串。 */
export function extensionOf(path: string): string {
  const name = path.slice(path.lastIndexOf('/') + 1)
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/** 扩展名对应的语言名；不认识时返回空串（表示"按纯文本渲染"）。 */
export function languageOf(path: string): string {
  return EXTENSION_TO_LANGUAGE[extensionOf(path)] || ''
}

/** HTML 转义（围栏内容里出现 `<`/`&` 时必须先转义，否则等于把仓库内容当 HTML 注入）。 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 选一段**不会与内容冲突**的围栏。
 *
 * 这是本文件存在的首要原因：文件内容里本来就可能出现 ```（`.md` 文件、代码里的模板字符串），
 * 用固定的三个反引号拼接会让围栏**提前闭合**，后半段内容直接变成正文（甚至被当成 HTML）。
 * CommonMark 允许 3 个以上的反引号，只要比内容里最长的一串长即可。
 */
export function fenceFor(content: string): string {
  let longest = 0
  let current = 0
  for (const char of content) {
    if (char === '`') {
      current += 1
      longest = Math.max(longest, current)
    } else {
      current = 0
    }
  }
  return '`'.repeat(Math.max(3, longest + 1))
}

/**
 * 把文件内容包成一个 Markdown 代码块。
 *
 * @param path    文件路径（用于取语言标签）
 * @param content 文件内容（可能已被后端截断）
 */
export function buildFileMarkdown(path: string, content: string | null | undefined): string {
  const text = content ?? ''
  const fence = fenceFor(text)
  const language = languageOf(path)
  // 末尾补一个换行：否则围栏的收尾会和最后一行代码粘在同一行上
  const body = text.endsWith('\n') ? text : `${text}\n`
  return `${fence}${language}\n${body}${fence}\n`
}
