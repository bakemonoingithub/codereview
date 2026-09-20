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
          <a-tag>{{ shortSha(headerSha) }}</a-tag>
          <a-tag v-if="headerBranch">{{ headerBranch }}</a-tag>
        </a-space>
        <a-button type="text" class="close-btn" @click="emit('update:open', false)">
          <CloseOutlined />
        </a-button>
      </div>
    </template>

    <a-alert
      v-if="error"
      type="error"
      show-icon
      class="viewer-error"
      message="完整审查记录加载失败"
      description="列表接口不返回完整结果（单条可达 MB 级），需要按 id 拉详情；请关闭后重试。"
    />

    <!-- 标记加载失败与"本来没有标记"必须区分：否则界面上"一个标记都没有"看不出是接口挂了 -->
    <a-alert
      v-if="marksError"
      type="warning"
      show-icon
      class="viewer-error"
      message="标记（误报/已采纳）加载失败"
      description="下面展示的是不含标记的结果；关闭弹窗后重开可重试。"
    />

    <ReviewConfigSnapshot
      v-if="open && displayRecord"
      :record="displayRecord"
      :project-id="projectId"
      :marks="marks"
      :marks-loading="loading"
      :strategies="strategies"
    />
    <!-- 详情还在路上：给一个明确的加载壳，而不是一屏"—"让人以为这条记录是空的 -->
    <div v-else-if="open" class="viewer-loading">
      <a-spin size="large" tip="正在载入审查记录…" />
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import { getReview, listMarks, type IssueMark, type ReviewRecord, type ReviewRecordRow } from '@/api/review'
import ReviewConfigSnapshot from '@/components/ReviewConfigSnapshot.vue'
import { shortSha } from '@/utils/reviewResult'

/**
 * 「审查记录-查看」= 全屏模态框，复用「代码审查」的布局与结果渲染，整体只读。
 *
 * **取数在这里**：调用方只给 `record-id`（点的是列表行），完整记录与标记由本组件自己拉。
 * 理由有两条 —— 列表接口刻意不返回 `resultJson`（单条可达 MB 级），必须按 id 取详情；
 * 而「审查记录」页签与「报告-选择审查记录」都要用这个弹窗，取数留在调用方就会写两遍。
 *
 * 不复用页签内的 SplitPane，是为了**不动页签里正在进行的审查**：
 * 查看历史记录不该覆盖当前审查结果，弹窗关掉即回到原状态。
 * `destroy-on-close` 保证关掉就卸载 —— DiffViewer 的 patch 缓存随之释放。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    /** 要查看的记录 id；空串表示未选 */
    recordId: string
    projectId: string
    /** 列表行，带 strategyName；详情接口不返回策略名，靠它补上 */
    row?: ReviewRecordRow | null
    strategies?: { value: string; label: string }[]
  }>(),
  {
    row: null,
    strategies: () => []
  }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

const record = ref<ReviewRecord | null>(null)
const marks = ref<IssueMark[]>([])
const loading = ref(false)
const error = ref(false)
/** 标记单独失败：与"记录本身没加载出来"区分开，也与"确实没有标记"区分开 */
const marksError = ref(false)

// 供测试断言内部状态：弹窗内容由 antd Modal 渲染在 body 上，
// 用 wrapper.text() 断不到（Modal 在测试里被 stub 时插槽根本不渲染）
defineExpose({ record, marks, loading, error })

/** 列表行的策略名优先（详情接口没有这个字段） */
const displayRecord = computed<ReviewRecord | null>(() => {
  if (!record.value) {
    return null
  }
  if (!props.row || props.row.id !== record.value.id) {
    return record.value
  }
  return { ...props.row, ...record.value }
})

// 记录尚未到位时，标题先用列表行里的 sha/分支，避免弹窗顶上一片空白
const headerSha = computed(() => record.value?.commitSha || props.row?.commitSha || '')
const headerBranch = computed(() => record.value?.branch || props.row?.branch || '')

watch(
  () => [props.open, props.recordId] as const,
  async ([open, id], previous) => {
    const previousId = previous?.[1]
    if (!open || !id || id === previousId) {
      return
    }
    // 切到另一条记录时先清空：宁可转圈，也不要显示上一条的残留数据
    record.value = null
    marks.value = []
    error.value = false
    marksError.value = false
    loading.value = true
    try {
      const [full, recordMarks] = await Promise.all([
        getReview(id),
        // 只读弹窗：标记拉不到不该让整个详情失败，但也**不能当成"没有标记"**
        listMarks(id).catch(() => {
          marksError.value = true
          return [] as IssueMark[]
        })
      ])
      record.value = full
      marks.value = recordMarks
    } catch {
      error.value = true
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)
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
.viewer-error {
  margin-bottom: 12px;
}
.viewer-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 60vh;
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
