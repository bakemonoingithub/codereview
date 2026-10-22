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
.report-markdown :deep(h2) {
  margin: 0 0 12px;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink);
}
.report-markdown :deep(h3) {
  margin: 22px 0 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}
.report-markdown :deep(h4) {
  margin: 20px 0 10px;
  font-size: 14px;
  font-weight: 650;
  color: #24446d;
}
.report-markdown :deep(p) {
  margin: 0 0 13px;
  font-size: 13px;
  line-height: 1.95;
  overflow-wrap: anywhere;
}
.report-markdown :deep(ul) {
  padding-left: 20px;
  font-size: 13px;
}
.report-markdown :deep(code) {
  background: #f1f5f9;
  border: 1px solid var(--control-border);
  padding: 2px 5px;
  border-radius: 6px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: #334155;
}
.report-markdown :deep(pre) {
  background: var(--preview);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px;
  overflow: auto;
}
.report-markdown :deep(pre code) {
  background: none;
  border: 0;
  padding: 0;
}
.report-markdown :deep(ul),
.report-markdown :deep(ol) {
  padding-left: 1.5em;
}
.report-markdown :deep(blockquote) {
  margin: 18px 0;
  padding: 10px 16px;
  background: var(--bg);
  border-left: 3px solid #cbd5e1;
  color: var(--muted);
  font-size: 12px;
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
  background: var(--soft);
  color: var(--blue);
  border: 0;
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
}
.report-markdown :deep(.report-citation:hover) {
  background: #dbe6fe;
}
</style>
