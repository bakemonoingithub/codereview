<template>
  <div v-if="text" ref="root" class="code-viewer">
    <div class="code-gutter" aria-hidden="true">
      <span v-for="line in lineNumbers" :key="line" :class="gutterClass(line)">
        <span class="n">{{ line }}</span>
      </span>
    </div>
    <div class="code-main">
      <div class="range-layer" aria-hidden="true">
        <span
          v-for="range in visibleRanges"
          :key="range.key"
          class="range"
          :class="{ active: range.active }"
          :style="range.style"
        ></span>
      </div>
      <pre class="hljs"><code v-html="html"></code></pre>
    </div>
  </div>
  <pre v-else class="code-viewer empty">（空文件）</pre>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { languageOf } from '@/utils/filePreview'
import { highlightSource } from '@/utils/markdown'
import type { CodeRange } from '@/utils/codeReferences'

/**
 * 行高与顶部内边距是范围高亮层的坐标基准，必须与 <style> 里的
 * `.code-gutter span` / `.code-main pre` 保持一致；两处改动要一起改。
 */
const LINE_HEIGHT = 22
const TOP_PADDING = 12

const props = withDefaults(defineProps<{
  text?: string
  path?: string
  language?: string
  startLine?: number
  ranges?: CodeRange[]
  activeRange?: CodeRange | null
}>(), {
  text: '',
  path: '',
  language: '',
  startLine: 1,
  ranges: () => [],
  activeRange: null
})

const root = ref<HTMLElement | null>(null)
const html = computed(() => highlightSource(props.text, props.language || languageOf(props.path)))
const lineCount = computed(() => props.text ? props.text.split('\n').length : 0)
const lineNumbers = computed(() => Array.from({ length: lineCount.value }, (_, index) => props.startLine + index))
const lastLine = computed(() => props.startLine + Math.max(lineCount.value - 1, 0))

const visibleRanges = computed(() => {
  if (!lineCount.value) return []
  const result: Array<{ key: string; active: boolean; style: Record<string, string> }> = []
  props.ranges.forEach((raw, index) => {
    const start = Math.max(props.startLine, Math.min(raw.startLine, raw.endLine))
    const end = Math.min(lastLine.value, Math.max(raw.startLine, raw.endLine))
    if (start > end) return
    const active = !!props.activeRange
      && raw.startLine === props.activeRange.startLine
      && raw.endLine === props.activeRange.endLine
    result.push({
      key: `${start}-${end}-${index}`,
      active,
      style: {
        top: `${TOP_PADDING + (start - props.startLine) * LINE_HEIGHT}px`,
        height: `${(end - start + 1) * LINE_HEIGHT}px`
      }
    })
  })
  // 当前范围画在最后，避免与同问题的其他范围重叠时被盖住。
  return result.sort((a, b) => Number(a.active) - Number(b.active))
})

function isHighlighted(line: number): boolean {
  return props.ranges.some((range) => line >= Math.min(range.startLine, range.endLine)
    && line <= Math.max(range.startLine, range.endLine))
}

function isCurrent(line: number): boolean {
  if (!props.activeRange) return false
  const start = Math.min(props.activeRange.startLine, props.activeRange.endLine)
  const end = Math.max(props.activeRange.startLine, props.activeRange.endLine)
  return line >= start && line <= end
}

function gutterClass(line: number) {
  return {
    'is-hl': isHighlighted(line),
    'is-cur': isCurrent(line),
    'is-start': !!props.activeRange && line === props.activeRange.startLine
  }
}

/**
 * 把某一行滚到代码区顶部。调用方传入真正的滚动容器，避免 scrollIntoView
 * 连带滚动弹窗外的页面。行号是源码行号，不是当前视图内的序号。
 */
function scrollToLine(line: number, container: HTMLElement | null, instant = false) {
  if (!root.value || !container || !lineCount.value) return
  const clamped = Math.min(Math.max(line, props.startLine), lastLine.value)
  const top = root.value.getBoundingClientRect().top
    - container.getBoundingClientRect().top
    + container.scrollTop
    + TOP_PADDING
    + (clamped - props.startLine) * LINE_HEIGHT
  const target = Math.max(0, top)
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  // 短距离平滑便于跟住视线，跨越大段代码时直接跳，避免等一段长动画。
  const behavior = instant || reduceMotion || Math.abs(target - container.scrollTop) > 900 ? 'auto' : 'smooth'
  if (typeof container.scrollTo === 'function') {
    container.scrollTo({ top: target, behavior })
  } else {
    container.scrollTop = target
  }
}

defineExpose({ scrollToLine })
</script>

<style scoped lang="less">
.code-viewer {
  display: flex;
  align-items: flex-start;
  min-width: max-content;
  margin: 0;
  background: #fff;
  font-family: Consolas, 'Cascadia Code', ui-monospace, monospace;
  font-size: 13px;
  line-height: 22px;
}

.code-viewer.empty {
  padding: 12px 16px;
  color: #94a3b8;
}

.code-gutter {
  position: sticky;
  left: 0;
  z-index: 2;
  flex: none;
  padding: 12px 0;
  background: #f8fafc;
  border-right: 1px solid #e2e8f0;
  color: #94a3b8;
  text-align: right;
  user-select: none;
}

.code-gutter span {
  display: block;
  height: 22px;
  padding: 0 12px;
  line-height: 22px;
  min-width: 3.2em;
}

.code-gutter .n {
  display: inline-block;
  min-width: 20px;
  line-height: 18px;
  border-radius: 5px;
}

.code-gutter span.is-hl {
  background: #eef4ff;
  box-shadow: inset 2px 0 0 #a9c4f4;
  color: #4f6f9f;
}

.code-gutter span.is-cur {
  background: #dce9ff;
  box-shadow: inset 3px 0 0 var(--blue);
  color: #1d4ed8;
  font-weight: 650;
}

.code-gutter span.is-start .n {
  padding: 0 5px;
  background: var(--blue);
  color: #fff;
}

.code-main {
  position: relative;
  flex: 1;
  min-width: max-content;
}

.range-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

/* 高亮层只负责底色；正文仍由上面的 <pre> 负责，保留选择与复制。 */
.range {
  position: absolute;
  left: 0;
  right: 0;
  display: block;
  background: #eef4ff;
}

.range.active {
  background: #dce9ff;
}

.code-main pre {
  position: relative;
  z-index: 1;
  margin: 0;
  padding: 12px 16px;
  background: transparent;
  overflow: visible;
}

.code-viewer :deep(code) {
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
  background: transparent;
  padding: 0;
  white-space: pre;
}
</style>
