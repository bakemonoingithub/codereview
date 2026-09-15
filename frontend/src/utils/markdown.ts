import hljs from 'highlight.js/lib/core'
import { Marked } from 'marked'
import { escapeHtml } from './filePreview'
// 主题 CSS 必须引：hljs 只产出 `hljs-*` 的 class，颜色全在主题里；
// 不引就等于"高亮白做"（span 有 class 但没有任何样式规则）
import 'highlight.js/styles/github.css'

import java from 'highlight.js/lib/languages/java'
import kotlin from 'highlight.js/lib/languages/kotlin'
import scala from 'highlight.js/lib/languages/scala'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python from 'highlight.js/lib/languages/python'
import go from 'highlight.js/lib/languages/go'
import csharp from 'highlight.js/lib/languages/csharp'
import cpp from 'highlight.js/lib/languages/cpp'
import c from 'highlight.js/lib/languages/c'
import ruby from 'highlight.js/lib/languages/ruby'
import php from 'highlight.js/lib/languages/php'
import sql from 'highlight.js/lib/languages/sql'
import bash from 'highlight.js/lib/languages/bash'
import dos from 'highlight.js/lib/languages/dos'
import powershell from 'highlight.js/lib/languages/powershell'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import less from 'highlight.js/lib/languages/less'
import scss from 'highlight.js/lib/languages/scss'
import stylus from 'highlight.js/lib/languages/stylus'
import json from 'highlight.js/lib/languages/json'
import yaml from 'highlight.js/lib/languages/yaml'
import ini from 'highlight.js/lib/languages/ini'
import properties from 'highlight.js/lib/languages/properties'
import groovy from 'highlight.js/lib/languages/groovy'
import markdown from 'highlight.js/lib/languages/markdown'
import diff from 'highlight.js/lib/languages/diff'
import swift from 'highlight.js/lib/languages/swift'
import dart from 'highlight.js/lib/languages/dart'
import lua from 'highlight.js/lib/languages/lua'
import perl from 'highlight.js/lib/languages/perl'
import objectivec from 'highlight.js/lib/languages/objectivec'
import x86asm from 'highlight.js/lib/languages/x86asm'
import r from 'highlight.js/lib/languages/r'

/**
 * 共享的 Markdown 渲染器（marked + highlight.js）。
 *
 * 为什么不用 `marked-highlight`：marked 18 的 renderer 已经以 token 形式暴露 `code({text, lang})`，
 * 直接接管即可，少一个依赖、也少一个 peer 版本区间要跟（marked 主版本升得很快）。
 *
 * 为什么**按需注册语言**：全量 highlight.js 大约 1MB；这里只注册白名单里会出现的那些类型，
 * 体积可控，而且未注册的语言会自然退化成"纯文本代码块"而不是报错。
 *
 * 注意：`hljs.highlight` 的返回值是**已转义**的 HTML；未注册语言走 `escapeHtml` 自己转义。
 * 两处都不能漏 —— 文件内容里的 `<` 必须变成 `&lt;`，否则等于把仓库内容当 HTML 注入。
 */
const LANGUAGES: Record<string, unknown> = {
  java, kotlin, scala, javascript, typescript, python, go, csharp, cpp, c,
  ruby, php, sql, bash, dos, powershell, xml, css, less, scss, stylus,
  json, yaml, ini, properties, groovy, markdown, diff,
  swift, dart, lua, perl, objectivec, x86asm, r
}

for (const [name, definition] of Object.entries(LANGUAGES)) {
  hljs.registerLanguage(name, definition as never)
}

/** 已注册的语言名（供 filePreview 判断"这个扩展名能不能高亮"）。 */
export function isHighlightable(language: string): boolean {
  return !!language && hljs.getLanguage(language) !== undefined
}

export const marked = new Marked({
  gfm: true,
  breaks: false,
  renderer: {
    code({ text, lang }) {
      const language = lang && hljs.getLanguage(lang) ? lang : ''
      const body = language ? hljs.highlight(text, { language }).value : escapeHtml(text)
      const className = language ? `hljs language-${language}` : 'hljs'
      return `<pre><code class="${className}">${body}</code></pre>\n`
    }
  }
})

/** 渲染 Markdown 为 HTML（**未净化**，调用方必须过 DOMPurify）。 */
export function renderMarkdown(text: string | null | undefined): string {
  if (!text) {
    return ''
  }
  return marked.parse(text) as string
}
