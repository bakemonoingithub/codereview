<template>
  <div class="page-notice" :class="`is-${variant}`" role="note">
    <div class="page-notice-text">
      <slot />
    </div>
    <button
      v-if="closable"
      type="button"
      class="page-notice-close"
      aria-label="关闭"
      @click="emit('close')"
    >
      ×
    </button>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'info' | 'amber' | 'red'
    closable?: boolean
  }>(),
  {
    variant: 'info',
    closable: false
  }
)

const emit = defineEmits<{
  (e: 'close'): void
}>()
</script>

<style scoped lang="less">
.page-notice {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--notice-border);
  border-radius: 14px;
  padding: 13px 16px;
  background: var(--notice);
  color: var(--notice-ink);
  font-size: 12px;
  line-height: 1.6;
}

.page-notice-text {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.is-amber {
  border-color: var(--notice-amber-border);
  background: var(--notice-amber-bg);
  color: var(--notice-amber-ink);
}

.is-red {
  border-color: var(--notice-red-border);
  background: var(--notice-red-bg);
  color: var(--notice-red-ink);
}

.page-notice-close {
  flex: none;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: inherit;
  font-size: 16px;
  line-height: 1;
  padding: 0 2px;
  cursor: pointer;
}
</style>
