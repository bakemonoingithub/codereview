<template>
  <section class="accuracy-summary" data-task-accuracy>
    <div class="row wrap">
      <strong>{{ title }}</strong>
      <span class="rate" :class="{ unset: percent === null }">{{ percentText }}</span>
      <span class="muted small">
        准确 {{ counts.accurate }} · 不准确 {{ counts.inaccurate }} ·
        无法判断 {{ counts.unknown }} · 未反馈 {{ counts.unassessed }}
      </span>
      <span class="spacer" />
      <span class="muted small">{{ scopeText }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { TaskStatistics } from '@/api/v2'

const props = defineProps<{ specialistType: TaskStatistics['specialistType']; stats: TaskStatistics }>()
const counts = computed(() => props.specialistType === 'ISSUE' ? props.stats.issues : props.stats.results)
const percent = computed(() => {
  const value = counts.value.accuracyPercent
  return typeof value === 'number' && Number.isFinite(value) ? value : null
})
const percentText = computed(() =>
  percent.value === null ? '— · 暂无明确判定' : `${percent.value}%`)
const title = computed(() => (
  { ISSUE: '本任务问题判定准确率', PATTERN: '本任务模式结果判定准确率', COUPLING: '本任务耦合结果判定准确率' }
)[props.specialistType] || '本任务判定准确率')
const scopeText = computed(() => props.specialistType === 'ISSUE'
  ? '仅统计本任务已完成结果中的问题'
  : '仅统计本任务已完整发布的模式／耦合结果')
</script>

<style scoped lang="less">
.accuracy-summary {
  padding: 13px 16px;
  margin-bottom: 16px;
  background: var(--notice);
  border: 1px solid var(--notice-border);
  border-radius: 14px;
  color: var(--notice-ink);
  font-size: 12px;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.wrap {
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.muted {
  color: var(--muted);
}
.small {
  font-size: 12px;
}
.rate {
  color: var(--ink);
  font-size: 18px;
  font-weight: 700;
}
.rate.unset {
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
}
</style>
