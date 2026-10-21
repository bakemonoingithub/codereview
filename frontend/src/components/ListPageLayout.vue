<template>
  <div class="list-page">
    <div class="page-head">
      <div class="page-head-main">
        <h1 class="page-title">{{ title }}</h1>
        <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
      </div>
      <div class="page-head-actions">
        <slot name="actions" />
      </div>
    </div>

    <div v-if="$slots.notice" class="page-notices">
      <slot name="notice" />
    </div>

    <div v-if="card" class="page-card">
      <div v-if="$slots.filters" class="page-filters">
        <div class="page-filters-main">
          <slot name="filters" />
        </div>
        <div v-if="$slots['filters-extra']" class="page-filters-extra">
          <slot name="filters-extra" />
        </div>
      </div>
      <div class="page-card-body">
        <slot />
      </div>
    </div>
    <template v-else>
      <div v-if="$slots.filters" class="page-filters page-filters-plain">
        <slot name="filters" />
      </div>
      <slot />
    </template>

    <div v-if="$slots.footer" class="page-footer">
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 列表页统一骨架：标题、说明、筛选和表格。
 *
 * `card` 为真时，筛选栏和表格在同一张圆角卡片里（原型的列表结构）。
 * `notice` 放在页头下方，`footer` 放在卡片下方，`filters-extra` 在筛选栏右侧。
 */
withDefaults(
  defineProps<{
    title: string
    /** 一句话说明这个页面是干什么的 */
    subtitle?: string
    /** 筛选和默认插槽是否包进同一张卡片 */
    card?: boolean
  }>(),
  { card: true }
)
</script>

<style scoped lang="less">
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 32px;
}

.page-title {
  margin: 0;
  color: var(--ink);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.2;
}

.page-subtitle {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.6;
}

.page-head-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-notices {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}

.page-notices:not(:has(.page-notice)) {
  display: none;
}

.page-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.page-filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--line);
}

.page-filters-plain {
  margin-bottom: 12px;
  padding: 0;
  border-bottom: 0;
}

.page-filters-main,
.page-filters-extra {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.page-filters-main {
  flex: 1;
}

.page-footer {
  margin-top: 18px;
}

@media (max-width: 760px) {
  .page-head {
    flex-direction: column;
  }

  .page-title {
    font-size: 26px;
  }

  .page-filters {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
