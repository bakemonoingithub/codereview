<template>
  <div class="diff-viewer">
    <DiffView
      v-if="renderablePatch"
      :data="{ hunks: [renderablePatch] }"
      :extend-data="extendData"
      :diff-view-mode="DiffModeEnum.Unified"
      diff-view-theme="light"
      diff-view-wrap
      :diff-view-font-size="12"
      :diff-view-highlight="false"
    >
      <template #extend="{ lineNumber, data }">
        <div class="diff-extend">
          <slot name="extend" :line-number="lineNumber" :items="data" />
        </div>
      </template>
    </DiffView>
    <a-empty v-else :description="emptyText" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { DiffModeEnum, DiffView } from '@git-diff-view/vue'
import '@git-diff-view/vue/styles/diff-view.css'
import { buildExtendData, toRenderablePatch } from '@/utils/patch'

const props = withDefaults(
  defineProps<{
    path: string
    patch?: string | null
    status?: string | null
    /** 按新文件行号锚定的评论（同一条会被聚合到该行） */
    comments?: Array<{ line?: number | null; data: any }>
    emptyText?: string
  }>(),
  {
    patch: null,
    status: 'modified',
    comments: () => [],
    emptyText: '该文件无可用 diff'
  }
)

// GitHub 的 patch 不含 --- / +++ 头，这里按变更类型补齐（diff 解析器需要完整头）
const renderablePatch = computed(() => toRenderablePatch(props.path, props.patch, props.status))

const extendData = computed(() => buildExtendData(props.comments || []))
</script>

<style scoped lang="less">
.diff-viewer {
  :deep(.diff-view-wrap) {
    border: 1px solid #f0f0f0;
    border-radius: 4px;
  }
}
.diff-extend {
  padding: 4px 8px;
  background: #fffbe6;
  border-left: 3px solid #faad14;
}
</style>
