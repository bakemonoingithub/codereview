<template>
  <a-modal
    :open="open"
    title="选择 PR"
    :width="760"
    ok-text="确认选择"
    cancel-text="取消"
    :ok-button-props="{ disabled: draft == null }"
    @ok="confirm"
    @cancel="close"
  >
    <p class="muted">从当前项目仓库选择未合并的 PR。已加载列表可按标题或编号筛选。</p>
    <a-input
      v-model:value="query"
      allow-clear
      placeholder="筛选标题、分支，或输入 PR 编号"
    />
    <a-alert v-if="error" type="error" show-icon :message="error" class="gap">
      <template #action>
        <a-button size="small" @click="emit('retry')">重试</a-button>
      </template>
    </a-alert>
    <a-spin :spinning="loading">
      <div v-if="shown.length" class="list">
        <button
          v-for="item in shown"
          :key="item.number"
          type="button"
          class="row"
          :class="{ active: draft === item.number }"
          @click="draft = item.number"
        >
          <span class="number">#{{ item.number }}</span>
          <span class="main">
            <strong>{{ item.title || '未命名 PR' }}</strong>
            <small>{{ item.sourceBranch || '源分支未知' }} → {{ item.targetBranch || '目标分支未知' }}</small>
          </span>
          <span class="side">{{ item.author || '作者未知' }}</span>
        </button>
      </div>
      <a-empty v-else description="当前没有可选择的 PR" />
    </a-spin>
    <div class="foot">
      <span class="muted">已显示 {{ shown.length }} 条</span>
      <a-button v-if="hasMore" type="link" :loading="loadingMore" @click="emit('load-more')">加载更多</a-button>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PullRequestItem } from '@/api/v2'

const props = defineProps<{
  open: boolean
  items: PullRequestItem[]
  selected: number | null
  hasMore: boolean
  loading: boolean
  loadingMore: boolean
  error: string
}>()
const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'confirm', number: number): void
  (event: 'load-more'): void
  (event: 'retry'): void
}>()

const query = ref('')
const draft = ref<number | null>(null)
watch(() => props.open, (open) => {
  if (open) {
    query.value = ''
    draft.value = props.selected
  }
})
const shown = computed(() => {
  const text = query.value.trim().toLowerCase()
  if (!text) return props.items
  return props.items.filter((item) =>
    (`#${item.number} ${item.number} ${item.title || ''} ${item.author || ''} ${item.sourceBranch || ''} ${item.targetBranch || ''}`)
      .toLowerCase().includes(text))
})

function confirm() {
  if (draft.value == null) return
  emit('confirm', draft.value)
  emit('update:open', false)
}
function close() {
  emit('update:open', false)
}
</script>

<style scoped>
.muted { color: #667085; font-size: 12px; }
.gap { margin: 12px 0; }
.list { max-height: 360px; overflow: auto; margin-top: 12px; border: 1px solid #e6ebf2; border-radius: 8px; }
.row { display: flex; width: 100%; gap: 12px; align-items: center; text-align: left; padding: 10px 12px; border: 0; border-bottom: 1px solid #eef2f6; background: white; cursor: pointer; }
.row.active { background: #eef4ff; }
.number { color: #2458c5; font-family: ui-monospace, monospace; }
.main { flex: 1; min-width: 0; }
.main strong, .main small { display: block; }
.main small, .side { color: #667085; font-size: 12px; }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
</style>
