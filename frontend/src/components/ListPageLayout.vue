<template>
  <div class="list-page">
    <div class="page-head">
      <div class="page-head-main">
        <h2 class="page-title">{{ title }}</h2>
        <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
      </div>
      <div class="page-head-actions">
        <slot name="actions" />
      </div>
    </div>

    <div v-if="$slots.filters" class="page-filters">
      <slot name="filters" />
    </div>

    <slot />
  </div>
</template>

<script setup lang="ts">
/**
 * 列表页统一骨架：页面标题 + 右侧主操作 + 左侧筛选区 + 内容。
 *
 * 抽出来的原因：四个列表页原先"新建按钮一会儿最左一会儿最右、容器一会儿 class
 * 一会儿内联 style、全都没有页面标题" —— 页面上没有标题，用户只能靠侧边栏猜自己在哪。
 * 四处各写一份必然再次漂移，所以统一到这里。
 *
 * 插槽：
 * - `actions`：页面主操作（如"新建项目"），固定在标题右侧；
 * - `filters`：筛选/搜索区，仅在需要时渲染（用 `$slots.filters` 判断，不留空行）；
 * - 默认插槽：表格等内容。
 */
defineProps<{
  title: string
  /** 一句话说明这个页面是干什么的，帮助"第一次用的人"快速定位 */
  subtitle?: string
}>()
</script>

<style scoped lang="less">
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  color: rgba(0, 0, 0, 0.88);
}
.page-subtitle {
  margin: 4px 0 0;
  color: #8c8c8c;
  font-size: 12px;
  line-height: 18px;
}
.page-head-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-filters {
  margin-bottom: 12px;
}
</style>
