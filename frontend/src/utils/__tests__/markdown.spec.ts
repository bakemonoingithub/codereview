import { describe, expect, it } from 'vitest'
import { isHighlightable, renderMarkdown } from '@/utils/markdown'
import { buildFileMarkdown } from '@/utils/filePreview'

/**
 * 共享 Markdown 渲染器（marked + highlight.js）。
 *
 * 断言的是**渲染出来的 HTML 字符串**，不经过 DOMPurify、也不 mount 组件 ——
 * happy-dom 下 DOMPurify 3.4.15 会剥掉允许的标签（C 批已记录），拿它做断言等于测环境。
 * 净化的正确性由浏览器里的人工验证与 `MarkdownView` 自身的调用保证。
 */
describe('renderMarkdown 的代码块高亮', () => {
  it('带语言标签的围栏会走 highlight.js，产出 token span', () => {
    const html = renderMarkdown('```java\nclass A {}\n```\n')

    expect(html).toContain('class="hljs language-java"')
    expect(html).toContain('hljs-keyword')
  })

  it('没有语言标签时按纯文本处理，不加语言 class', () => {
    const html = renderMarkdown('```\nplain text\n```\n')

    expect(html).toContain('class="hljs"')
    expect(html).not.toContain('language-')
  })

  it('未注册的语言退化为纯文本，而不是报错或乱着色', () => {
    expect(isHighlightable('zzz-not-a-language')).toBe(false)

    const html = renderMarkdown('```zzz-not-a-language\n<not html>\n```\n')

    expect(html).toContain('class="hljs"')
    expect(html).toContain('&lt;not html&gt;')
  })

  it('代码块内容里的 HTML 被转义，不会变成真正的标签', () => {
    const html = renderMarkdown('```xml\n<script>alert(1)</script>\n```\n')

    // 注意：hljs 会把 < 拆进自己的 token span，所以「&lt;script&gt; 连续出现」是假的，
    // 真正要断言的是"没有真正的标签"+"确实被转义了"
    expect(html).not.toContain('<script>')
    expect(html).not.toContain('</script>')
    expect(html).toContain('&lt;')
  })

  it('空文本渲染成空串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
  })
})

describe('文件内容 → 代码块 → 渲染（围栏冲突的端到端验证）', () => {
  it('文件内容里本来就有三反引号时，仍然渲染成一个完整代码块', () => {
    const content = '# 标题\n\n```js\nconst a = 1\n```\n\n正文\n'

    const html = renderMarkdown(buildFileMarkdown('README.md', content))

    // 恰好一个 pre/code：围栏没被内容里的 ``` 提前闭合
    expect(html.match(/<pre>/g)?.length).toBe(1)
    expect(html).toContain('```js')
    expect(html).toContain('正文')
  })

  it('Java 文件走 java 高亮，且文件里的 `<` 被转义', () => {
    const html = renderMarkdown(buildFileMarkdown('src/A.java', 'class A { boolean b = 1 < 2; }\n'))

    expect(html).toContain('language-java')
    expect(html).toContain('hljs-keyword')
    expect(html).not.toContain('1 < 2')
    expect(html).toContain('&lt;')
  })
})
