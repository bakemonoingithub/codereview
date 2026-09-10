<template>
  <div class="review-result" :class="{ 'is-readonly': readonly }">
    <div v-if="!record" class="placeholder">{{ placeholderText }}</div>
    <template v-else>
      <div v-if="running" class="mb8">
        <a-progress :percent="record.progress" status="active" />
        <p class="summary">{{ record.status === 0 ? '排队中…' : '审查执行中…' }}</p>
      </div>
      <div class="result-head">
        <a-alert
          v-if="!running"
          :type="statusAlert.type"
          :message="statusAlert.text"
          show-icon
          class="flex1"
        />
        <a-button v-if="showRetry" :loading="retrying" @click="$emit('retry')">重审失败单元</a-button>
      </div>
      <p v-if="parsed.summary" class="summary">{{ parsed.summary }}</p>

      <DiffReviewResult
        v-if="resultType === 'diff-review'"
        :project-id="projectId"
        :commit-sha="record.commitSha || ''"
        :record-id="record.id"
        :result="parsed"
        :marks="marks"
        :readonly="readonly"
        @mark="onMark"
      />

      <a-collapse v-else-if="resultType === 'llm-review' && parsed.units?.length">
        <a-collapse-panel v-for="(u, i) in parsed.units" :key="i" :header="unitTitle(u)">
          <a-space style="margin-bottom: 8px">
            <a-tag :color="u.status === 'success' ? 'green' : 'red'">
              {{ u.status === 'success' ? '成功' : '失败' }}
            </a-tag>
            <span class="unit-meta">{{ u.unit.kind }} · 行 {{ u.unit.lines }}</span>
          </a-space>
          <div v-if="u.status === 'failed'" class="error-text">{{ u.error }}</div>
          <template v-else>
            <p v-if="u.summary" class="summary">{{ u.summary }}</p>
            <a-table
              v-if="u.issues?.length"
              :data-source="u.issues"
              row-key="title"
              size="small"
              :pagination="false"
            >
              <a-table-column title="级别" data-index="severity" width="80" />
              <a-table-column title="行" data-index="line" width="60" />
              <a-table-column title="文件" data-index="file" width="140">
                <template #default="{ text }">{{ text || u.path }}</template>
              </a-table-column>
              <a-table-column title="问题" data-index="title" />
              <a-table-column title="建议" data-index="suggestion" />
            </a-table>
            <RawResult v-else-if="u.raw" :text="u.raw" />
          </template>
        </a-collapse-panel>
      </a-collapse>

      <CouplingResult v-else-if="resultType === 'coupling'" :result="parsed" />
      <PatternResult v-else-if="resultType === 'design-pattern'" :result="parsed" />
      <RawResult v-else-if="resultType === 'raw'" :text="parsed.raw" />

      <div v-else-if="resultType === 'api-review'">
        <a-space v-if="parsed.resultUrl" style="margin-bottom: 8px">
          <a-tag :color="parsed.triggered ? 'green' : 'red'">
            {{ parsed.triggered ? '已触发' : '触发失败' }}
          </a-tag>
          <a :href="parsed.resultUrl" target="_blank" rel="noopener">查看 SonarQube 结果</a>
        </a-space>
        <p v-if="parsed.triggerError" class="error-text">触发失败：{{ parsed.triggerError }}</p>
        <a-table
          v-if="parsed.issues?.length"
          :data-source="parsed.issues"
          row-key="key"
          size="small"
          :pagination="false"
          style="margin-top: 8px"
        >
          <a-table-column title="级别" data-index="severity" width="80" />
          <a-table-column title="行" data-index="line" width="60" />
          <a-table-column title="问题" data-index="message" />
        </a-table>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { IssueMark, ReviewRecord } from '@/api/review'
import {
  canRetry,
  inferResultType,
  isRunning,
  parseResultJson,
  recordStatusAlert
} from '@/utils/reviewResult'
import CouplingResult from '@/components/CouplingResult.vue'
import PatternResult from '@/components/PatternResult.vue'
import RawResult from '@/components/RawResult.vue'
import DiffReviewResult from '@/components/DiffReviewResult.vue'

/**
 * 审查结果区（原"代码审查"tab 右栏）。
 *
 * 抽成组件是为了让**触发审查**与**查看历史记录**共用同一份渲染：
 * 只读弹窗只需传 `readonly`，任何写操作入口（重审失败单元、issue 标记）都会随之关闭，
 * 不必复制模板 —— 复制模板必然导致两处对同一结果的渲染随时间漂移。
 */
const props = withDefaults(
  defineProps<{
    /** 空值 = 尚未触发，显示占位 */
    record: ReviewRecord | null
    projectId: string
    marks?: IssueMark[]
    retrying?: boolean
    /** 只读（查看历史记录）：关闭全部写操作入口 */
    readonly?: boolean
  }>(),
  {
    marks: () => [],
    retrying: false,
    readonly: false
  }
)

const emit = defineEmits<{
  (e: 'retry'): void
  (e: 'mark', unitPath: string, issueIndex: number, markValue: number): void
}>()

/**
 * 记录为空有两义：页签里"还没触发过"，与只读弹窗里"完整记录还在路上"。
 * 后者若显示"尚未触发审查"，会让人以为这条记录是空的。
 */
const placeholderText = computed(() => (props.readonly ? '正在载入审查记录…' : '尚未触发审查'))

const parsed = computed<any>(() => parseResultJson(props.record?.resultJson))
const resultType = computed(() => inferResultType(parsed.value))
const running = computed(() => (props.record ? isRunning(props.record.status) : false))
const statusAlert = computed(() => recordStatusAlert(props.record?.status ?? 0))
/** 执行中不显示重审；只读查看历史记录时也不显示 —— 重审入口在"审查记录"列表里 */
const showRetry = computed(() => !props.readonly && !!props.record && canRetry(props.record.status))

function onMark(unitPath: string, issueIndex: number, markValue: number) {
  // 只读态下按钮已 disabled；这里再兜一层，防止后续改动误把点击透传出去
  if (props.readonly) {
    return
  }
  emit('mark', unitPath, issueIndex, markValue)
}

function unitTitle(u: any) {
  return `${u.path} · ${u.unit.name}`
}
</script>

<style scoped lang="less">
.review-result {
  .summary {
    color: #666;
  }
  .unit-meta {
    color: #999;
  }
  .error-text {
    color: #cf1322;
    white-space: pre-wrap;
  }
  .placeholder {
    color: #999;
  }
  .result-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }
  .flex1 {
    flex: 1;
  }
  .mb8 {
    margin-bottom: 8px;
  }
}
</style>
