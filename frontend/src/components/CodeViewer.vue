<template>
  <div v-if="text" class="code-viewer">
    <div class="code-gutter" aria-hidden="true">
      <span v-for="n in lineCount" :key="n">{{ startLine + n - 1 }}</span>
    </div>
    <pre class="hljs"><code v-html="html"></code></pre>
  </div>
  <pre v-else class="code-viewer empty">（空文件）</pre>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { languageOf } from '@/utils/filePreview'
import { highlightSource } from '@/utils/markdown'

const props = withDefaults(defineProps<{
  text?: string
  path?: string
  language?: string
  startLine?: number
}>(), {
  text: '',
  path: '',
  language: '',
  startLine: 1
})

const html = computed(() => highlightSource(props.text, props.language || languageOf(props.path)))
const lineCount = computed(() => props.text ? props.text.split('\n').length : 0)
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
  line-height: 1.7;
}

.code-viewer.empty {
  padding: 12px 16px;
  color: #94a3b8;
}

.code-gutter {
  position: sticky;
  left: 0;
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
  padding: 0 12px;
  min-width: 3.2em;
}

.code-viewer pre {
  margin: 0;
  padding: 12px 16px;
  background: transparent;
  flex: 1;
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
