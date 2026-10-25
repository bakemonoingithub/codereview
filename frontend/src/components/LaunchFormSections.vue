<template>
  <a-form layout="vertical" class="launch-form" @submit.prevent>
    <div class="launch-sections">
      <a-card v-for="(key, index) in sectionKeys" :key="key" class="launch-section">
        <template #title>{{ index + 1 }}. {{ titles[key] }}</template>
        <slot :name="key" />
      </a-card>
    </div>
  </a-form>
</template>

<script lang="ts">
export default { name: 'LaunchFormSections' }
</script>

<script setup lang="ts">
/**
 * 三个发起弹窗共用的分区骨架：固定「目标 / 范围或材料 / 补充」三张卡片。
 * 标题按页面配置，卡片里的字段由具名插槽填入。
 */
interface LaunchSectionTitles {
  goal: string
  scope: string
  extra: string
}

defineProps<{ titles: LaunchSectionTitles }>()

const sectionKeys = ['goal', 'scope', 'extra'] as const
</script>

<style scoped lang="less">
.launch-sections {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.launch-form :deep(.field-hint) {
  margin: 6px 0 0;
}
</style>
