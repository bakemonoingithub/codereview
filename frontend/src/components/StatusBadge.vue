<template>
  <span class="status-badge" :class="`is-${tone}`">
    <i class="dot" aria-hidden="true" />
    <slot />
  </span>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    tone?: 'green' | 'amber' | 'blue' | 'red' | 'default'
  }>(),
  { tone: 'default' }
)
</script>

<style scoped lang="less">
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  padding: 3px 10px;
  background: var(--badge-default-bg);
  color: var(--badge-default-ink);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.is-green {
  background: var(--green-soft);
  color: var(--green);
}

.is-amber {
  background: var(--amber-soft);
  color: var(--amber);
}

.is-blue {
  background: var(--soft);
  color: var(--blue);
}

.is-red {
  background: var(--red-soft);
  color: var(--red);
}
</style>
