<template>
  <code class="input-version" :title="`${label} · 目标提交 ${targetHead || '目标提交未记录'}`">{{ label }}</code>
  <a v-if="pullUrl" class="pull-link" :href="pullUrl" target="_blank" rel="noreferrer">打开 PR</a>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  pullNumber?: number | null
  pullUrl?: string | null
  mergeBase?: string | null
  sourceHead?: string | null
  targetHead?: string | null
}>()

const label = computed(() => {
  const ancestor = props.mergeBase?.slice(0, 12) || '共同祖先未记录'
  const source = props.sourceHead?.slice(0, 12) || '源提交未记录'
  return `PR #${props.pullNumber || '?'} · ${ancestor}（共同祖先）→ ${source}（源提交）`
})
</script>

<style scoped>
.input-version {
  padding: 3px 7px;
  border-radius: 5px;
  background: var(--bg);
  box-decoration-break: clone;
  -webkit-box-decoration-break: clone;
}
.pull-link {
  margin-inline-start: 8px;
  white-space: nowrap;
}
</style>
