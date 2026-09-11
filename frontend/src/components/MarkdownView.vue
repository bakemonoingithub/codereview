<template>
  <div class="markdown-body" v-html="html"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染（marked + DOMPurify）。
 *
 * 抽出来的原因：这段逻辑原先只在 `RawResult` 里，而"报告预览"用 `<pre>` 直出原文 ——
 * 于是报告里显示的是 `**加粗**`、`# 标题` 这样的源码，而不是排版后的正文。
 *
 * `html` 用 computed 而不是模板里直接调函数：模板里调用会在**每次渲染**都重跑
 * marked + DOMPurify，长报告/大文本上很浪费。
 */
const props = defineProps<{
  text?: string | null
}>()

const html = computed(() => {
  const raw = props.text ?? ''
  if (!raw) {
    return ''
  }
  return DOMPurify.sanitize(marked.parse(raw) as string)
})
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
