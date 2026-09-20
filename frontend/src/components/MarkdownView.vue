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
  max-height: 55vh;
  overflow: auto;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 0.5em 0 0.3em;
  font-weight: 600;
}
.markdown-body :deep(p) {
  margin: 0.4em 0;
}
.markdown-body :deep(code) {
  background: #f5f5f5;
  padding: 1px 4px;
  border-radius: 3px;
  font-family: monospace;
  font-size: 12px;
}
.markdown-body :deep(pre) {
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  padding: 10px;
  overflow: auto;
}
.markdown-body :deep(pre code) {
  background: none;
  padding: 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 1.5em;
}
.markdown-body :deep(blockquote) {
  border-left: 3px solid #ddd;
  margin: 0.4em 0;
  padding-left: 0.8em;
  color: #666;
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
