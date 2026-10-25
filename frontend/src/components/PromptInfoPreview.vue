<template>
  <section class="prompt-info-preview">
    <div class="section-heading">{{ title }}</div>
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error">
        <template #action>
          <a-button size="small" :loading="loading" @click="emit('retry')">重试</a-button>
        </template>
      </a-alert>
      <template v-if="prompt">
        <strong class="item-title">{{ prompt.name }} · {{ formatPromptVersion(displayVersion?.versionNo) }}</strong>
        <p v-if="displayVersion?.createdAt" class="description">创建时间：{{ displayVersion.createdAt.replace('T', ' ') }}</p>
        <p v-if="prompt.description" class="description">{{ prompt.description }}</p>
        <div v-if="prompt.tags.length" class="tags">
          <a-tag v-for="tag in prompt.tags" :key="tag">{{ tag }}</a-tag>
        </div>
        <pre class="prompt-content" :class="{ expanded }">{{ displayVersion?.content || '所选提示词版本不可用。' }}</pre>
        <a-button
          v-if="(displayVersion?.content?.length || 0) > 360"
          type="link"
          size="small"
          @click="expanded = !expanded"
        >{{ expanded ? '收起正文' : '展开全文' }}</a-button>
      </template>
      <span v-else-if="!error" class="muted">
        {{ loading ? '正在读取提示词…' : selected ? '提示词详情暂不可用' : '未关联提示词' }}
      </span>
    </a-spin>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PromptVersion, PromptView } from '@/api/v2'
import { formatPromptVersion } from '@/utils/promptVersion'

const props = withDefaults(defineProps<{
  prompt: PromptView | null
  version?: PromptVersion | null
  title?: string
  selected?: boolean
  loading?: boolean
  error?: string
}>(), {
  title: '提示词',
  selected: false,
  loading: false,
  error: ''
})
const emit = defineEmits<{ retry: [] }>()
const displayVersion = computed(() => props.version === undefined ? props.prompt?.currentVersion : props.version)
const expanded = ref(false)
watch(() => [props.prompt?.id, displayVersion.value?.id], () => { expanded.value = false })
</script>

<style scoped lang="less">
.prompt-info-preview {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}
.section-heading {
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 12px;
}
.item-title {
  display: block;
  overflow-wrap: anywhere;
}
.description {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 12px;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.tags :deep(.ant-tag) {
  margin-inline-end: 0;
}
.prompt-content {
  max-height: 180px;
  margin: 8px 0 0;
  padding: 9px 10px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--bg);
  color: var(--text, #1f2937);
  font: inherit;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.prompt-content.expanded {
  max-height: 440px;
}
.muted {
  color: var(--muted);
  font-size: 12px;
}
</style>
