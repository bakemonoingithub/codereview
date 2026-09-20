// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import MarkdownView from '@/components/MarkdownView.vue'

/**
 * Markdown 渲染（C6）。
 *
 * 抽出来的原因：这段逻辑原先只在 RawResult 里，而报告预览用 `<pre>` 直出原文 ——
 * 报告里显示的是 `**加粗**`、`# 标题` 源码，而不是排版后的正文。
 *
 * ⚠️ **本文件刻意不断言两件事**（在 happy-dom 下断言不了，见下方说明）：
 * 1. `<h1>`/`<h2>` 这类标题标签是否保留；
 * 2. XSS 清洗是否生效。
 *
 * 原因：DOMPurify 在本测试环境里判定不可信 —— 实测
 * `sanitize('<h1>x</h1>')` → `"x"`（**剥掉了合法标签**），
 * `sanitize('<script>y</script>')` → `"y"`（**留下了脚本文本**），
 * 而 happy-dom 自身的解析器是正常的（`innerHTML` 能正确得到 h1/script 节点）。
 * 也就是说这是「DOMPurify × happy-dom」的组合问题，不是我们代码的问题；
 * 清洗效果只能在真实浏览器里验收（已记入待办）。
 *
 * 注：`utils/markdown` 改成**动态 import**（按需加载 highlight.js 与 35 种语言，
 * 不让它拖累审查详情页首屏），所以渲染是异步的 —— 挂载后必须 flush 一次。
 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** 渲染是异步的（动态 import）：多转几轮宏任务，避免"还没渲染完就断言"的假失败 */
async function settle() {
  for (let i = 0; i < 5; i++) {
    await flush()
    await nextTick()
  }
}

async function mountView(props: Record<string, unknown>, expected?: string) {
  const wrapper = mount(MarkdownView, { props })
  if (expected !== undefined) {
    // 渲染是异步的：轮询等待，避免"还没渲染完就断言"的假失败
    await vi.waitFor(() => expect(wrapper.text()).toContain(expected), { timeout: 3000 })
  } else {
    await settle()
  }
  return wrapper
}

describe('MarkdownView', () => {
  it('把 Markdown 语法渲染成 DOM，而不是原样显示标记', async () => {
    const wrapper = await mountView({ text: '段落里的 **重点** 与 *斜体*' }, '重点')

    expect(wrapper.find('strong').text()).toBe('重点')
    expect(wrapper.find('em').text()).toBe('斜体')
    expect(wrapper.text()).not.toContain('**重点**')
  })

  it('表格与列表的正文内容都渲染出来（标签是否保留受环境影响，见文件头说明）', async () => {
    const wrapper = await mountView(
      { text: '| 文件 | 问题 |\n| --- | --- |\n| A.java | 空指针 |\n\n- 第一项\n- 第二项' },
      '空指针'
    )

    // 只断言"内容进到了正文里"：具体标签是否被 DOMPurify 保留在 happy-dom 下不可信
    expect(wrapper.text()).toContain('空指针')
    expect(wrapper.text()).toContain('第一项')
    expect(wrapper.text()).toContain('第二项')
  })

  it('空值与 undefined 不报错', async () => {
    expect((await mountView({ text: '' })).html()).toContain('markdown-body')
    expect((await mountView({})).text()).toBe('')
  })

  it('渲染容器带 markdown-body 类（样式依赖它）', async () => {
    const wrapper = await mountView({ text: '正文' }, '正文')

    expect(wrapper.find('.markdown-body').exists()).toBe(true)
  })

  it('text 变化后重新渲染（异步渲染也不能丢掉后续更新）', async () => {
    const wrapper = await mountView({ text: '第一版' }, '第一版')

    await wrapper.setProps({ text: '第二版 **加粗**' })
    await vi.waitFor(() => expect(wrapper.text()).toContain('第二版'), { timeout: 3000 })

    expect(wrapper.text()).not.toContain('第一版')
  })
})
