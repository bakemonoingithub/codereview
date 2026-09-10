<template>
  <div class="diff-viewer">
    <DiffView
      v-if="renderablePatch"
      :data="diffData"
      :extend-data="extendData"
      :diff-view-mode="viewMode"
      diff-view-theme="light"
      diff-view-wrap
      :diff-view-font-size="12"
      :diff-view-highlight="highlight"
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
    /** 提交视图：变更文件路径。提示词对比可不传，改用 oldFileName/newFileName */
    path?: string
    patch?: string | null
    status?: string | null
    /** 两侧展示名（组件用它当列头）；传了即视为"调用方已备好带头补全的 patch" */
    oldFileName?: string
    newFileName?: string
    /** 默认 unified —— 保持提交视图的既有行为不变 */
    mode?: 'unified' | 'split'
    /**
     * 只控制**语法**高亮（highlight.js）。
     * 行内**字符级差异**高亮由库内部始终开启，与本 prop 无关。
     * 纯文本场景显式传 false：既无语法可高亮，又能省掉构建语法 AST 的开销
     * （该 prop 不传时组件会渲染成纯文本却仍构建 AST）。
     */
    highlight?: boolean
    /** 按新文件行号锚定的评论（同一条会被聚合到该行） */
    comments?: Array<{ line?: number | null; data: any }>
    emptyText?: string
  }>(),
  {
    path: '',
    patch: null,
    status: 'modified',
    oldFileName: '',
    newFileName: '',
    mode: 'unified',
    highlight: false,
    comments: () => [],
    emptyText: '该文件无可用 diff'
  }
)

/**
 * 提交视图：GitHub 的 patch 从 `@@` 开始、不含 `--- a/` `+++ b/` 头，需按变更类型补齐
 * ——解析器找不到 `+++` 会解析出 0 个 hunk、视图全空白。
 * 提示词对比：调用方已用 `toUnifiedPatch` 生成带头补全的 patch，原样使用。
 */
const renderablePatch = computed(() => {
  if (props.oldFileName || props.newFileName) {
    return (props.patch || '').trim()
  }
  return toRenderablePatch(props.path, props.patch, props.status)
})

// 本版本里 Split 与 SplitGitHub 行为完全等价，取后者：单条通栏 @@ 带、一套展开控件，噪音最小
const viewMode = computed(() => (props.mode === 'split' ? DiffModeEnum.SplitGitHub : DiffModeEnum.Unified))

const diffData = computed(() => ({
  hunks: [renderablePatch.value],
  // 仅在给了展示名时附带文件信息；提交视图保持与既有行为完全一致（不传 fileName）
  ...(props.oldFileName || props.newFileName
    ? {
        oldFile: { fileName: props.oldFileName, fileLang: 'plaintext' },
        newFile: { fileName: props.newFileName, fileLang: 'plaintext' }
      }
    : {})
}))

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
