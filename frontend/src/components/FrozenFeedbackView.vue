<template>
  <section class="frozen-feedback" :aria-label="title">
    <div class="feedback-head">
      <strong>{{ title }}</strong>
      <span class="assessment" :data-assessment="feedback?.assessment || 'UNASSESSED'">{{ assessmentText }}</span>
    </div>
    <p v-if="feedback?.comment" class="comment">评论：{{ feedback.comment }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{
  feedback?: { assessment?: string | null; comment?: string | null } | null
  title?: string
}>(), { title: '当时反馈' })
const assessmentText = computed(() => {
  const assessment = props.feedback?.assessment
  return assessment ? ({ ACCURATE: '准确', INACCURATE: '不准确', UNKNOWN: '无法判断' } as Record<string, string>)[assessment] || assessment : '未反馈'
})
</script>

<style scoped lang="less">
.frozen-feedback { padding: 12px var(--result-inset, 16px); border-top: 1px solid var(--line); background: var(--bg); font-size: 13px; }
.feedback-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 12px; }
.assessment { padding: 1px 8px; border-radius: 6px; background: var(--badge-default-bg); color: var(--badge-default-ink); }
.assessment[data-assessment="ACCURATE"] { color: var(--green); background: var(--green-soft); }
.assessment[data-assessment="INACCURATE"] { color: var(--red); background: var(--red-soft); }
.assessment[data-assessment="UNKNOWN"] { color: var(--amber); background: var(--amber-soft); }
.comment { margin: 6px 0 0; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
