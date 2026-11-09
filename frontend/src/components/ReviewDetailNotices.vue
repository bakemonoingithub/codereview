<template>
  <section
    v-if="loadError || status || pullVersionNote || dataError || $slots.summary"
    class="review-detail-notices"
    aria-label="任务提示"
  >
    <a-alert v-if="loadError" type="error" banner show-icon :message="loadError">
      <template #action><a-button size="small" @click="emit('retry')">重试</a-button></template>
    </a-alert>
    <a-alert
      v-if="status"
      :type="status.type"
      banner
      show-icon
      :message="status.message"
      :description="status.description"
    />
    <a-alert v-if="pullVersionNote" type="warning" banner show-icon :message="pullVersionNote" />
    <a-alert v-if="dataError" type="warning" banner show-icon>
      <template #message>{{ dataError }}</template>
    </a-alert>
    <a-alert v-if="$slots.summary" type="info" banner show-icon>
      <template #message><slot name="summary" /></template>
    </a-alert>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  loadError?: string
  status?: { type: 'info' | 'warning' | 'error'; message: string; description?: string }
  pullVersionNote?: string
  dataError?: string
}>()

const emit = defineEmits<{ (e: 'retry'): void }>()
</script>

<style scoped>
.review-detail-notices {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  margin-bottom: 16px;
  overflow-wrap: anywhere;
}
</style>
