import { describe, expect, it } from 'vitest'
import { buildFileMarkdown, escapeHtml, extensionOf, fenceFor, languageOf } from '@/utils/filePreview'

/**
 * 文件预览的纯函数。
 *
 * 重点在两处**极易写错**的地方：
 * ① 围栏冲突 —— 文件内容里本来就有 ```（`.md`、模板字符串）时，用固定三反引号会让围栏提前闭合，
 *    后半段内容跑出代码块；
 * ② 语言标签 —— 猜错（或不认识的扩展名硬套一个语言）会满屏错误着色，不如按纯文本渲染。
 */
describe('extensionOf / languageOf', () => {
  it('取小写扩展名，点号开头不算扩展名', () => {
    expect(extensionOf('src/A.java')).toBe('java')
    expect(extensionOf('src/A.JAVA')).toBe('java')
    expect(extensionOf('.gitignore')).toBe('')
    expect(extensionOf('Makefile')).toBe('')
    expect(extensionOf('a/b/c.d/e')).toBe('')
  })

  it('白名单里的常见类型都有语言归属', () => {
    expect(languageOf('src/A.java')).toBe('java')
    expect(languageOf('a/b/x.ts')).toBe('typescript')
    expect(languageOf('pom.xml')).toBe('xml')
    expect(languageOf('app.yml')).toBe('yaml')
    expect(languageOf('build.gradle')).toBe('groovy')
    expect(languageOf('Dockerfile.txt')).toBe('')
  })

  it('不认识或无扩展名时返回空串（按纯文本渲染，不瞎猜）', () => {
    expect(languageOf('data.csv')).toBe('')
    expect(languageOf('run.log')).toBe('')
    expect(languageOf('doc.rst')).toBe('')
    expect(languageOf('Makefile')).toBe('')
  })
})

describe('fenceFor', () => {
  it('普通内容用三个反引号', () => {
    expect(fenceFor('class A {}')).toBe('```')
  })

  it('内容里已有三反引号时自动加长，避免围栏提前闭合', () => {
    expect(fenceFor('```\ncode\n```')).toBe('````')
  })

  it('内容里有更长的反引号串时继续加长', () => {
    expect(fenceFor('`````')).toBe('``````')
    // 不连续的反引号不算一串
    expect(fenceFor('`a`b`c')).toBe('```')
    expect(fenceFor('a\n```\nb\n````\n')).toBe('`````')
  })
})

describe('buildFileMarkdown', () => {
  it('带语言标签的围栏，且内容原样在内', () => {
    const md = buildFileMarkdown('src/A.java', 'class A {}\n')

    expect(md).toBe('```java\nclass A {}\n```\n')
  })

  it('未知扩展名不给语言标签', () => {
    expect(buildFileMarkdown('notes.unknown', 'x\n')).toBe('```\nx\n```\n')
  })

  it('内容缺少结尾换行时补一个，避免收尾围栏粘在最后一行上', () => {
    expect(buildFileMarkdown('a.md', '# title')).toBe('```markdown\n# title\n```\n')
  })

  it('内容里含三反引号时整体仍然合法（用更长的围栏包住）', () => {
    const md = buildFileMarkdown('README.md', '```js\nconst a = 1\n```\n')

    expect(md.startsWith('````markdown\n')).toBe(true)
    expect(md.endsWith('````\n')).toBe(true)
    expect(md).toContain('```js')
  })

  it('内容里的 HTML 不会被当成 HTML（转义交给渲染器，这里只保证不被围栏拆散）', () => {
    const md = buildFileMarkdown('a.html', '<script>alert(1)</script>\n')

    expect(md).toBe('```xml\n<script>alert(1)</script>\n```\n')
  })

  it('空内容也给一个合法代码块', () => {
    expect(buildFileMarkdown('empty.txt', '')).toBe('```\n\n```\n')
    expect(buildFileMarkdown('empty.txt', null)).toBe('```\n\n```\n')
  })
})

describe('escapeHtml', () => {
  it('转义会破坏 HTML 结构的字符', () => {
    expect(escapeHtml('<a href="x">&</a>')).toBe('&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;')
  })
})
