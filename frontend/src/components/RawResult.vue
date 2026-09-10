<template>
  <a-tabs type="card" size="small">
    <a-tab-pane key="raw" tab="raw-text">
      <pre class="raw-text">{{ text }}</pre>
    </a-tab-pane>
    <a-tab-pane key="markdown" tab="markdown">
      <div class="markdown-body" v-html="renderMarkdown(text)"></div>
    </a-tab-pane>
  </a-tabs>
</template>

<script setup lang="ts">
import { marked } from 'marked'
import DOMPurify from 'dompurify'

defineProps<{ text: string }>()

function renderMarkdown(text: string): string {
  const html = marked.parse(text ?? '') as string
  return DOMPurify.sanitize(html)
}
</script>

<style scoped lang="less">
.raw-text {
  white-space: pre-wrap;
  word-break: break-all;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  padding: 12px;
  max-height: 50vh;
  overflow: auto;
  font-size: 12px;
  margin: 0;
}
.markdown-body {
  max-height: 50vh;
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
