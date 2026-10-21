<template>
  <div v-if="message" class="load-error">
    <PageNotice variant="red">
      <span class="load-error-message">{{ message }}</span>
      <a-button type="link" size="small" class="quiet-retry" @click="emit('retry')">重试</a-button>
    </PageNotice>
  </div>
</template>

<script setup lang="ts">
import PageNotice from '@/components/PageNotice.vue'

/**
 * 列表/面板级「加载失败 + 重试」提示。
 *
 * `message` 为空时不渲染任何东西 —— 调用方只管把错误塞进来，不用自己 v-if。
 */
defineProps<{
  message?: string
}>()

const emit = defineEmits<{
  (e: 'retry'): void
}>()
</script>

<style scoped lang="less">
.load-error {
  margin-bottom: 12px;
}

.quiet-retry {
  height: auto;
  padding: 0;
  color: var(--notice-red-ink);
  font-weight: 600;
}
</style>
