<template>
  <a-select
    :value="value"
    :options="options"
    :loading="loading"
    :disabled="loading || !versions.length"
    :get-popup-container="getPopupContainer"
    placeholder="选择提示词版本"
    style="width: 100%"
    @update:value="emit('update:value', $event)"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PromptVersion } from '@/api/v2'

const props = defineProps<{
  value?: string
  versions: PromptVersion[]
  currentVersionId?: string
  loading?: boolean
  allowFollowCurrent?: boolean
}>()
const emit = defineEmits<{ 'update:value': [value: string] }>()
const getPopupContainer = (trigger: HTMLElement) => trigger.closest('.ant-modal-body') as HTMLElement || document.body
const options = computed(() => [
  ...(props.allowFollowCurrent ? [{ value: '', label: '跟随提示词当前版本' }] : []),
  ...(props.value && !props.versions.some(version => version.id === props.value)
    ? [{ value: props.value, label: '所选版本不可用', disabled: true }] : []),
  ...props.versions.map(version => ({
    value: version.id,
    label: `v${version.versionNo}${version.id === props.currentVersionId ? ' · 当前版本' : ''} · ${version.createdAt.replace('T', ' ')}`
  }))
])
</script>
