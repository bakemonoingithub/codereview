<template>
  <a-alert
    v-if="message"
    class="load-error"
    type="error"
    show-icon
  >
    <template #message>{{ message }}</template>
    <template #action>
      <a-button size="small" @click="emit('retry')">重试</a-button>
    </template>
  </a-alert>
</template>

<script setup lang="ts">
/**
 * 列表/面板级「加载失败 + 重试」提示。
 *
 * `message` 为空时不渲染任何东西 —— 调用方只管把错误塞进来，不用自己 v-if。
 * 统一样式：红色图标、失败原因和右侧重试按钮，和代码浏览等页面级错误条一致。
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
