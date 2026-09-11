<template>
  <a-tabs type="card" size="small">
    <a-tab-pane key="raw" tab="原文">
      <pre class="raw-text">{{ text }}</pre>
    </a-tab-pane>
    <a-tab-pane key="markdown" tab="Markdown">
      <MarkdownView :text="text" />
    </a-tab-pane>
  </a-tabs>
</template>

<script setup lang="ts">
import MarkdownView from '@/components/MarkdownView.vue'

defineProps<{ text: string }>()
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
</style>
