<template>
  <div class="markdown-body" v-html="html"></div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染（marked + highlight.js + DOMPurify）。
 *
 * 抽出来的原因：这段逻辑原先只在 `RawResult` 里，而"报告预览"用 `<pre>` 直出原文 ——
 * 于是报告里显示的是 `**加粗**`、`# 标题` 这样的源码，而不是排版后的正文。
 *
 * 渲染器本身在 `utils/markdown.ts`（按需注册语言、接管代码块以做语法高亮）；
 * 这里只负责净化与展示。
 *
 * **`utils/markdown` 改成动态 import**：它静态引入 highlight.js 核心 + 35 种语言
 * （约 200 kB，gzip 60 kB），而它只被这里用到 —— 静态引入会把这份体积塞进
 * `ProjectDetail`（审查详情页）的首屏 chunk，哪怕用户根本没打开报告。改成打开时才拉，
 * 首屏只多一次按需请求。
 *
 * 渲染结果放 `ref` 而不是 computed：模板里调用会在**每次渲染**重跑
 * marked + highlight.js + DOMPurify，长报告/大文本上很浪费。
 */
const props = defineProps<{
  text?: string | null
}>()

const html = ref('')

watch(
  () => props.text,
  async (text) => {
    const raw = text ?? ''
    if (!raw) {
      html.value = ''
      return
    }
    const { renderMarkdown } = await import('@/utils/markdown')
    html.value = DOMPurify.sanitize(renderMarkdown(raw))
  },
  { immediate: true }
)
</script>

<style scoped lang="less">
.markdown-body {
  overflow: auto;
  font-size: 13px;
  line-height: 1.95;
  word-break: break-word;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2) {
  margin: 4px 0 14px;
  font-size: 20px;
  font-weight: 700;
  color: var(--ink);
}
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 24px 0 10px;
  font-size: 15px;
  font-weight: 650;
  color: var(--ink);
}
.markdown-body :deep(p),
.markdown-body :deep(li) {
  margin: 10px 0;
  font-size: 13px;
}
.markdown-body :deep(code) {
  background: #f1f5f9;
  border: 1px solid var(--control-border);
  padding: 2px 5px;
  border-radius: 6px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: #334155;
  overflow-wrap: anywhere;
}
.markdown-body :deep(pre) {
  background: var(--preview);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px;
  overflow: auto;
}
.markdown-body :deep(pre code) {
  background: none;
  border: 0;
  padding: 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 1.5em;
}
.markdown-body :deep(blockquote) {
  margin: 18px 0;
  padding: 10px 16px;
  background: var(--bg);
  border-left: 3px solid #cbd5e1;
  color: var(--muted);
  font-size: 12px;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 0.5em 0;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #eee;
  padding: 4px 8px;
}
</style>
