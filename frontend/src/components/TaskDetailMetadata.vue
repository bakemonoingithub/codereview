<template>
  <div class="task-metadata">
    <p><slot /></p>
    <p v-if="showId" :title="taskId">{{ idLabel }} {{ taskId }}</p>
    <p v-if="$slots.extra"><slot name="extra" /></p>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ taskId: string; idLabel?: string; showId?: boolean }>(), { idLabel: '任务 ID', showId: true })
</script>

<style scoped>
.task-metadata p {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
</style>
