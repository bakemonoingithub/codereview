<template>
  <a-empty class="empty-guide">
    <template #description>
      <div class="guide-title">{{ title }}</div>
      <div v-if="hint" class="guide-hint">{{ hint }}</div>
    </template>
    <a-button v-if="actionText" type="primary" @click="emit('action')">{{ actionText }}</a-button>
  </a-empty>
</template>

<script setup lang="ts">
/**
 * 列表空态引导。
 *
 * 原先四个列表页在"没有任何数据"时只有一行"暂无数据"，用户看不到下一步该做什么；
 * 更糟的是**依赖链断在半路**：新建策略必须选模型，而模型下拉为空时只弹一句
 * "请选择模型"，没有任何地方能点进去创建模型。
 */
defineProps<{
  /** 主文案：说清"这儿还没有什么" */
  title: string
  /** 补充说明：为什么需要它 / 下一步做什么 */
  hint?: string
  /** 有主操作时显示按钮（如"新建项目"） */
  actionText?: string
}>()

const emit = defineEmits<{
  (e: 'action'): void
}>()
</script>

<style scoped lang="less">
.guide-title {
  margin-bottom: 4px;
  color: rgba(0, 0, 0, 0.65);
}
.guide-hint {
  margin-bottom: 12px;
  color: #8c8c8c;
  font-size: 12px;
  line-height: 18px;
}
</style>
