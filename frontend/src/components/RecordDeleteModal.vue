<template>
  <a-modal :open="open" :title="kind === 'review' ? '删除这条审查记录？' : '删除这份报告？'"
    ok-text="确认删除" cancel-text="取消" :ok-button-props="{ danger: true }"
    :confirm-loading="busy" :cancel-button-props="{ disabled: busy }" :closable="!busy"
    :mask-closable="!busy" :keyboard="!busy" @ok="emit('confirm')" @cancel="!busy && emit('cancel')">
    <p class="record-name">{{ name }}</p>
    <template v-if="kind === 'review'">
      <p>删除后，该审查及其结果不再展示，也不能再选入新报告。</p>
      <p>已经使用此结果生成的报告保留其冻结内容。</p>
    </template>
    <template v-else>
      <p>删除后，该报告不再展示，也无法继续生成或下载。</p>
      <p>来源审查记录保留，可用于生成其他报告。</p>
    </template>
    <a-alert v-if="error" type="error" show-icon :message="error" />
  </a-modal>
</template>

<script setup lang="ts">
defineProps<{ open: boolean; kind: 'review' | 'report'; name: string; busy: boolean; error: string }>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<style scoped>
.record-name { padding: 12px; border-radius: 6px; background: var(--bg, #f5f7fb); overflow-wrap: anywhere; font-weight: 600; }
</style>
