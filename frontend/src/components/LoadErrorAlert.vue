<template>
  <a-alert v-if="message" type="error" show-icon class="load-error" :message="message">
    <template #action>
      <a-button size="small" @click="emit('retry')">重试</a-button>
    </template>
  </a-alert>
</template>

<script setup lang="ts">
/**
 * 列表/面板级"加载失败 + 重试"提示。
 *
 * 存在的理由：多处 async 原先完全没有 catch（失败后页面一片空白且没有任何提示），
 * 或 `catch {}` 只吞错。统一成一个组件，既保证文案与样式一致，
 * 也避免"哪一页忘了加错误态"这种漏。
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
</style>
