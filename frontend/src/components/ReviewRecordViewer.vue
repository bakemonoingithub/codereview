<template>
  <a-modal
    :open="open"
    :footer="null"
    :closable="false"
    :width="'100vw'"
    wrap-class-name="record-viewer-modal"
    destroy-on-close
    @cancel="emit('update:open', false)"
  >
    <template #title>
      <div class="viewer-title">
        <a-space :size="8">
          <span class="title-text">审查记录详情（只读）</span>
          <a-tag>{{ shortSha(record?.commitSha) }}</a-tag>
          <a-tag v-if="record?.branch">{{ record.branch }}</a-tag>
        </a-space>
        <a-button type="text" class="close-btn" @click="emit('update:open', false)">
          <CloseOutlined />
        </a-button>
      </div>
    </template>

    <ReviewConfigSnapshot
      v-if="open && record"
      :record="record"
      :project-id="projectId"
      :marks="marks"
      :marks-loading="marksLoading"
      :strategies="strategies"
    />
  </a-modal>
</template>

<script setup lang="ts">
import { CloseOutlined } from '@ant-design/icons-vue'
import type { IssueMark, ReviewRecord } from '@/api/review'
import ReviewConfigSnapshot from '@/components/ReviewConfigSnapshot.vue'
import { shortSha } from '@/utils/reviewResult'

/**
 * 「审查记录-查看」= 全屏模态框，复用「代码审查」的布局与结果渲染，整体只读。
 *
 * 不复用页签内的 SplitPane，是为了**不动页签里正在进行的审查**：
 * 查看历史记录不该覆盖当前审查结果，弹窗关掉即回到原状态。
 * `destroy-on-close` 保证关掉就卸载 —— DiffViewer 的 patch 缓存随之释放。
 */
withDefaults(
  defineProps<{
    open: boolean
    record: ReviewRecord | null
    projectId: string
    marks?: IssueMark[]
    marksLoading?: boolean
    strategies?: { value: string; label: string }[]
  }>(),
  {
    marks: () => [],
    marksLoading: false,
    strategies: () => []
  }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()
</script>

<style scoped lang="less">
.viewer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 24px;
}
.title-text {
  font-weight: 600;
}
.close-btn {
  margin-right: 8px;
}
</style>

<style lang="less">
/* 全屏模态框：铺满视口、去掉圆角与内边距，内容区自己负责滚动 */
.record-viewer-modal {
  top: 0;
  padding-bottom: 0;

  .ant-modal {
    top: 0;
    max-width: 100vw;
    margin: 0;
    padding-bottom: 0;
  }
  .ant-modal-content {
    display: flex;
    flex-direction: column;
    height: 100vh;
    border-radius: 0;
    padding: 0;
  }
  .ant-modal-header {
    flex: none;
    margin-bottom: 0;
    padding: 12px 16px;
    border-bottom: 1px solid #f0f0f0;
    border-radius: 0;
  }
  .ant-modal-body {
    flex: 1;
    min-height: 0;
    overflow: hidden;
    padding: 12px 16px;
  }
}
</style>
