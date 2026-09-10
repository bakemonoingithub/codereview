<template>
  <div>
    <a-empty v-if="!patterns.length" description="未识别到设计模式" />
    <a-card v-for="(p, i) in patterns" :key="i" size="small" style="margin-bottom: 8px">
      <div class="pattern-header">
        <span class="pattern-name">{{ p.name }}</span>
        <a-tag :color="confidenceColor(p.confidence)">{{ p.confidence || '未知' }}</a-tag>
        <a-tag v-if="p.misused" color="red">疑似误用</a-tag>
      </div>
      <div class="line">参与类：<a-tag v-for="c in p.participants" :key="c">{{ shortName(c) }}</a-tag></div>
      <div class="line">判定依据：{{ p.evidence }}</div>
      <div v-if="p.suggestion" class="line suggestion">建议：{{ p.suggestion }}</div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ result: any }>()

const patterns = computed(() => props.result?.patterns || [])

function confidenceColor(c: string) {
  if (c === '高') return 'green'
  if (c === '中') return 'orange'
  return 'default'
}

function shortName(fqcn: string) {
  const i = fqcn.lastIndexOf('.')
  return i >= 0 ? fqcn.slice(i + 1) : fqcn
}
</script>

<style scoped lang="less">
.pattern-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.pattern-name {
  font-weight: bold;
}
.line {
  color: #666;
  margin-top: 4px;
}
.suggestion {
  color: #1677ff;
}
</style>
