<template>
  <a-modal
    :open="open"
    :title="title"
    :footer="null"
    :width="LAUNCH_MODAL_WIDTH"
    :mask-closable="maskClosable"
    wrap-class-name="launch-modal"
    @cancel="onCancel"
  >
    <div class="launch-stack">
      <p v-if="$slots.intro" class="launch-intro muted">
        <slot name="intro" />
      </p>
      <div class="launch-alerts">
        <slot name="alerts" />
      </div>
      <div class="launch-body">
        <slot name="body" />
      </div>
      <div v-if="$slots.footer" class="submit-bar">
        <div v-if="$slots.hint" class="submit-hint muted small">
          <slot name="hint" />
        </div>
        <span class="spacer" />
        <div class="submit-actions">
          <slot name="footer" />
        </div>
      </div>
    </div>
    <slot name="overlay" />
  </a-modal>
</template>

<script lang="ts">
export default { name: 'LaunchModalShell' }
</script>

<script setup lang="ts">
/**
 * 发起类弹窗的共用壳。
 *
 * 统一宽度、遮罩、标题下说明、顶部提示、主体和底部操作栏。
 * 三分区与字段留在 `LaunchFormSections` 和各页面。嵌套弹窗放 `overlay`。
 */
const LAUNCH_MODAL_WIDTH = 'min(1080px, calc(100vw - 48px))'

withDefaults(
  defineProps<{
    /** 审查中心内嵌时由父级控制；路由页保持默认打开。 */
    open?: boolean
    title: string
    /** 为 true 时点击遮罩关闭。默认不因点击遮罩关闭。 */
    maskClosable?: boolean
  }>(),
  { open: true, maskClosable: false }
)

const emit = defineEmits<{
  cancel: []
  'update:open': [value: boolean]
}>()

function onCancel() {
  emit('update:open', false)
  emit('cancel')
}
</script>

<style scoped lang="less">
.launch-stack,
.launch-alerts {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.launch-body {
  min-width: 0;
}

.launch-intro {
  margin: 0;
}

/* 提示全部被 v-if 收起时只剩注释节点，不占 16px 间距。 */
.launch-alerts:not(:has(*)) {
  display: none;
}

.submit-bar,
.submit-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.submit-bar {
  position: sticky;
  bottom: 0;
  z-index: 2;
  margin-top: 0;
  padding: 12px 0;
  background: #fff;
  border-top: 1px solid var(--line);
}

.submit-hint {
  min-width: 0;
}

.spacer {
  flex: 1;
}
</style>

<style lang="less">
.launch-modal .ant-modal {
  top: 32px;
  padding-bottom: 32px;
  max-width: calc(100vw - 48px);
}

.launch-modal .ant-modal-body {
  max-height: calc(100vh - 140px);
  overflow: auto;
}
</style>
