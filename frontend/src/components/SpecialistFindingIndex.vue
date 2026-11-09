<template>
  <aside class="finding-index" aria-label="发现索引">
    <div class="index-heading">
      <h3>发现索引</h3>
      <span>{{ items.length }} 项</span>
    </div>
    <p class="index-summary" aria-live="polite">{{ summary }}</p>
    <div ref="grid" class="index-grid" role="group" aria-label="按编号跳转">
      <button
        v-for="(item, index) in items"
        :key="index"
        type="button"
        class="index-button"
        :class="item.status"
        :aria-current="index === activeIndex ? 'true' : undefined"
        :aria-label="itemDescription(item, index)"
        :title="itemDescription(item, index)"
        @click="emit('select', index)"
      >
        {{ String(index + 1).padStart(2, '0') }}
        <span class="status-symbol" aria-hidden="true">{{ statuses[item.status].symbol }}</span>
        <span v-if="item.dirty" class="draft-dot" aria-hidden="true" />
      </button>
    </div>
    <div class="index-legend" role="group" aria-label="反馈状态图例">
      <span v-for="status in legend" :key="status" class="legend-key">
        <span class="legend-symbol" :class="status" aria-hidden="true">{{ statuses[status].symbol }}</span>
        {{ statuses[status].label }}
      </span>
      <span class="legend-key"><span class="draft-dot" aria-hidden="true" />未保存</span>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const statuses = {
  pending: { label: '未反馈', symbol: '○' },
  accurate: { label: '准确', symbol: '✓' },
  inaccurate: { label: '不准确', symbol: '×' },
  unknown: { label: '无法判断', symbol: '?' },
  comment: { label: '仅评论', symbol: '▤' },
  blocked: { label: '不可反馈', symbol: '—' },
  unavailable: { label: '状态不可用', symbol: '!' }
} as const

export type SpecialistFindingStatus = keyof typeof statuses
export interface SpecialistFindingIndexItem {
  title: string
  status: SpecialistFindingStatus
  dirty?: boolean
}

const props = defineProps<{
  items: SpecialistFindingIndexItem[]
  activeIndex: number
  itemLabel: string
}>()
const emit = defineEmits<{ select: [index: number] }>()

const grid = ref<HTMLDivElement | null>(null)
const extraStatuses = ['blocked', 'unavailable'] as const
const counts = computed(() => {
  const counts: Partial<Record<SpecialistFindingStatus, number>> = {}
  for (const { status } of props.items) counts[status] = (counts[status] || 0) + 1
  return counts
})
const legend = computed<SpecialistFindingStatus[]>(() => [
  'pending', 'accurate', 'inaccurate', 'unknown', 'comment',
  ...extraStatuses.filter(status => counts.value[status])
])
const summary = computed(() => {
  const count = (status: SpecialistFindingStatus) => counts.value[status] || 0
  const saved = count('accurate') + count('inaccurate') + count('unknown') + count('comment')
  return [`已反馈 ${saved}`, `未反馈 ${count('pending')}`, ...extraStatuses
    .filter(status => count(status))
    .map(status => `${statuses[status].label} ${count(status)}`)].join(' · ')
})

function itemDescription(item: SpecialistFindingIndexItem, index: number) {
  return [`${props.itemLabel} ${index + 1}`, item.title, statuses[item.status].label,
    ...(item.dirty ? ['有未保存修改'] : [])].join(' · ')
}

watch([grid, () => props.activeIndex], () => {
  const panel = grid.value
  const current = panel?.querySelector<HTMLButtonElement>('[aria-current="true"]')
  if (!panel || !current) return
  const top = current.offsetTop - 3
  const bottom = top + current.offsetHeight + 6
  if (top < panel.scrollTop) panel.scrollTop = top
  else if (bottom > panel.scrollTop + panel.clientHeight) panel.scrollTop = bottom - panel.clientHeight
}, { flush: 'post' })
</script>

<style scoped>
.finding-index {
  position: sticky;
  top: 88px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: #fff;
}
.index-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px;
}
.index-heading h3 { margin: 0; font-size: 15px; }
.index-heading span,
.index-summary { color: var(--muted); font-size: 12px; }
.index-summary { margin: 4px 0 8px; }
.index-grid {
  position: relative;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(37px, 1fr));
  gap: 7px;
  max-height: 286px;
  overflow: auto;
  padding: 3px;
}
.index-button {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  min-height: 34px;
  padding: 0 2px;
  border: 1px solid var(--status-border);
  border-radius: 7px;
  background: var(--status-bg);
  color: var(--status-ink);
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.index-button:hover { border-color: var(--blue); }
.index-button[aria-current="true"] { outline: 2px solid var(--blue); outline-offset: 1px; }
.index-button:focus-visible { outline: 2px dashed var(--ink); outline-offset: 1px; }
.status-symbol { font-size: 11px; }
.pending, .blocked {
  --status-bg: #f3f5f8;
  --status-border: #cbd5e1;
  --status-ink: #4b5f77;
}
.accurate {
  --status-bg: #e8f6ef;
  --status-border: #9acdb3;
  --status-ink: #1b6344;
}
.inaccurate {
  --status-bg: #fbeceb;
  --status-border: #e2aca7;
  --status-ink: #9a3028;
}
.unknown {
  --status-bg: #fff1d9;
  --status-border: #e5c286;
  --status-ink: #795310;
}
.comment {
  --status-bg: #eeeafa;
  --status-border: #c7b8e6;
  --status-ink: #5a4192;
}
.unavailable {
  --status-bg: #eff4ff;
  --status-border: #b2c5de;
  --status-ink: #3e5876;
}
.index-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 9px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px solid var(--line);
}
.legend-key {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}
.legend-symbol {
  display: inline-grid;
  place-items: center;
  width: 17px;
  height: 17px;
  border: 1px solid var(--status-border);
  border-radius: 4px;
  background: var(--status-bg);
  color: var(--status-ink);
}
.draft-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7c3aa8;
}
.index-button .draft-dot { position: absolute; top: 2px; right: 2px; }
@media (max-width: 900px) {
  .finding-index { position: static; }
  .index-grid { max-height: 115px; }
}
</style>
