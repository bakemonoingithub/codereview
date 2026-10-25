<template>
  <span class="launch-version">
    <code>{{ loading ? loadingText : (sha || emptyText) }}</code>
    <a-button v-if="refreshText" size="small" :loading="loading" @click="emit('refresh')">
      {{ refreshText }}
    </a-button>
  </span>
</template>

<script lang="ts">
export default { name: 'LaunchVersionPreview' }
</script>

<script setup lang="ts">
/**
 * 分支 HEAD / 固定版本的同一行展示。刷新按钮只在页面传入文案时出现。
 */
withDefaults(
  defineProps<{
    sha?: string
    loading?: boolean
    loadingText?: string
    emptyText?: string
    refreshText?: string
  }>(),
  {
    sha: '',
    loading: false,
    loadingText: '读取中…',
    emptyText: '—',
    refreshText: ''
  }
)

const emit = defineEmits<{ refresh: [] }>()
</script>

<style scoped lang="less">
.launch-version {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
