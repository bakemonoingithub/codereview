<template>
  <div class="report-markdown" v-html="html" @click="onClick" />
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import DOMPurify from 'dompurify'

const props = defineProps<{ text?: string | null; sourceCount?: number }>()
const emit = defineEmits<{ (event: 'citation', ordinal: number): void }>()

const html = ref('')
let renderEpoch = 0

watch(
  () => props.text,
  async (text) => {
    const epoch = ++renderEpoch
    const raw = text ?? ''
    if (!raw) {
      html.value = ''
      return
    }
    const { renderMarkdown } = await import('@/utils/markdown')
    if (epoch !== renderEpoch) return
    const safe = DOMPurify.sanitize(renderMarkdown(raw), {
      ADD_ATTR: ['data-report-source']
    })
    html.value = linkCitations(safe)
  },
  { immediate: true }
)

/**
 * 只替换正文文本节点中的 [S1]，不碰代码块。
 * 引用按钮使用受控的 data 属性，点击后再由页面读取冻结来源；不把来源地址拼进 HTML。
 */
function linkCitations(safe: string): string {
  const container = document.createElement('div')
  container.innerHTML = safe
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  while (walker.nextNode()) {
    nodes.push(walker.currentNode as Text)
  }
  for (const node of nodes) {
    if (node.parentElement?.closest('pre, code')) continue
    const value = node.nodeValue || ''
    if (!/\[S\d+]/.test(value)) continue
    const fragment = document.createDocumentFragment()
    let last = 0
    const pattern = /\[S(\d+)]/g
    let match: RegExpExecArray | null
    while ((match = pattern.exec(value)) !== null) {
      if (match.index > last) fragment.append(value.slice(last, match.index))
      const anchor = document.createElement('a')
      anchor.href = '#'
      anchor.className = 'report-citation'
      anchor.dataset.reportSource = match[1]
      anchor.setAttribute('role', 'button')
      anchor.textContent = match[0]
      fragment.append(anchor)
      last = match.index + match[0].length
    }
    if (last < value.length) fragment.append(value.slice(last))
    node.replaceWith(fragment)
  }
  return container.innerHTML
}

function onClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const citation = target?.closest('[data-report-source]') as HTMLElement | null
  if (!citation) return
  event.preventDefault()
  const ordinal = Number(citation.dataset.reportSource)
  if (Number.isInteger(ordinal) && ordinal > 0) {
    emit('citation', ordinal)
  }
}
</script>

<style scoped lang="less">
.report-markdown {
  font-size: 14px;
  line-height: 1.8;
  word-break: break-word;
}
.report-markdown :deep(h1),
.report-markdown :deep(h2),
.report-markdown :deep(h3),
.report-markdown :deep(h4) {
  margin: 1.1em 0 0.45em;
  font-weight: 600;
  color: #1f3b63;
}
.report-markdown :deep(h1) {
  font-size: 22px;
  margin-top: 0;
}
.report-markdown :deep(h2) {
  font-size: 18px;
  padding-bottom: 6px;
  border-bottom: 1px solid #eef2f7;
}
.report-markdown :deep(h3) {
  font-size: 15px;
}
.report-markdown :deep(p) {
  margin: 0.55em 0;
}
.report-markdown :deep(code) {
  background: #f5f5f5;
  padding: 1px 4px;
  border-radius: 3px;
  font-family: monospace;
  font-size: 12px;
}
.report-markdown :deep(pre) {
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  padding: 10px;
  overflow: auto;
}
.report-markdown :deep(pre code) {
  background: none;
  padding: 0;
}
.report-markdown :deep(ul),
.report-markdown :deep(ol) {
  padding-left: 1.5em;
}
.report-markdown :deep(blockquote) {
  border-left: 3px solid #ddd;
  margin: 0.4em 0;
  padding-left: 0.8em;
  color: #666;
}
.report-markdown :deep(table) {
  border-collapse: collapse;
  margin: 0.5em 0;
}
.report-markdown :deep(th),
.report-markdown :deep(td) {
  border: 1px solid #eee;
  padding: 4px 8px;
}
.report-markdown :deep(.report-citation) {
  display: inline-block;
  margin: 0 1px;
  padding: 0 4px;
  border-radius: 3px;
  background: #eaf2ff;
  color: #1d5fbf;
  font-size: 12px;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
}
.report-markdown :deep(.report-citation:hover) {
  background: #d8e7ff;
}
</style>
