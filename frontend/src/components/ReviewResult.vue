<template>
  <div class="review-result" :class="{ 'is-readonly': readonly }">
    <div v-if="!record" class="placeholder">{{ placeholderText }}</div>
    <template v-else>
      <div v-if="running" class="mb8">
        <a-progress :percent="record.progress" status="active" />
        <p class="summary">
          {{ record.status === 0 ? '排队中…' : '审查执行中…' }}
          <span v-if="elapsedText" class="elapsed">· {{ elapsedText }}</span>
        </p>
        <!--
          轮询因连续失败停止时给出恢复入口：原先失败即静默停止，
          界面永远停在最后一次进度上，用户只能刷新页面。
        -->
        <a-alert v-if="pollError" type="warning" show-icon :message="`进度获取失败：${pollError}`">
          <template #action>
            <a-button size="small" @click="$emit('resume-poll')">继续等待</a-button>
          </template>
        </a-alert>
      </div>
      <div class="result-head">
        <a-alert
          v-if="!running"
          :type="statusAlert.type"
          :message="statusAlert.text"
          show-icon
          class="flex1"
        />
        <a-tag v-if="durationText" class="duration">{{ durationText }}</a-tag>
        <a-button v-if="showRetry" :loading="retrying" @click="$emit('retry')">重审失败单元</a-button>
      </div>
      <!--
        失败原因单独一条：后端把它放在 error_message（有界列），不再往 result_json 里塞，
        所以"失败"这件事第一次有了解释——否则界面上只有一条"失败"，看不出是网关、凭据还是范围问题。
      -->
      <a-alert
        v-if="record.errorMessage"
        type="error"
        show-icon
        class="mb8"
        :message="`失败原因：${record.errorMessage}`"
      />
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
            <a-tooltip v-if="unitKindTip(u.unit.kind)" :title="unitKindTip(u.unit.kind)">
              <span class="unit-meta">{{ u.unit.kind }} · 行 {{ u.unit.lines }}</span>
            </a-tooltip>
            <span v-else class="unit-meta">{{ u.unit.kind }} · 行 {{ u.unit.lines }}</span>
          </a-space>
          <div v-if="u.status === 'failed'" class="error-text">{{ u.error }}</div>
          <template v-else>
            <p v-if="u.summary" class="summary">{{ u.summary }}</p>
            <a-table
              v-if="u.issues?.length"
              :data-source="issueRows(u)"
              row-key="__key"
              size="small"
              :pagination="false"
            >
              <a-table-column title="级别" data-index="severity" width="80">
                <template #default="{ text }">
                  <a-tooltip v-if="severityTip(text)" :title="severityTip(text)">
                    <span>{{ text }}</span>
                  </a-tooltip>
                  <span v-else>{{ text }}</span>
                </template>
              </a-table-column>
              <a-table-column title="行" data-index="line" width="60" />
              <a-table-column title="文件" data-index="file" width="140" ellipsis>
                <template #default="{ text }">{{ text || u.path }}</template>
              </a-table-column>
              <a-table-column title="问题" data-index="title" ellipsis />
              <a-table-column title="建议" data-index="suggestion" ellipsis />
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
          <a-table-column title="级别" data-index="severity" width="80">
            <template #default="{ text }">
              <a-tooltip v-if="severityTip(text)" :title="severityTip(text)">
                <span>{{ text }}</span>
              </a-tooltip>
              <span v-else>{{ text }}</span>
            </template>
          </a-table-column>
          <a-table-column title="行" data-index="line" width="60" />
          <a-table-column title="问题" data-index="message" ellipsis />
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
import { formatDuration, formatElapsed } from '@/utils/duration'
import { severityTip, unitKindTip } from '@/utils/enums'
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
    /** 非空表示进度轮询已停止（连续失败），界面据此给出「继续等待」 */
    pollError?: string
  }>(),
  {
    marks: () => [],
    retrying: false,
    readonly: false,
    pollError: ''
  }
)

const emit = defineEmits<{
  (e: 'retry'): void
  (e: 'mark', unitPath: string, issueIndex: number, markValue: number): void
  (e: 'resume-poll'): void
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

/**
 * 已完成审查的耗时。
 *
 * 直接支撑验收指标 8（"记录完整耗时"）：在这之前前端从不展示耗时，
 * 只能靠现场秒表或查库。缺时间戳时返回空串而不是"—"，避免状态条旁边
 * 多出一个没有信息量的破折号。
 */
const durationText = computed(() => {
  if (!props.record || running.value) {
    return ''
  }
  const text = formatDuration(props.record.startedAt, props.record.finishedAt)
  return text === '—' ? '' : `耗时 ${text}`
})

/**
 * 进行中的已耗时。
 *
 * 依赖 `props.record` 的引用变化触发重算 —— 页签里每 2 秒轮询会换一个新对象，
 * 所以这里显示的是"跟着进度一起跳的数"，而不是冻住的值。
 */
const elapsedText = computed(() => {
  if (!props.record || !running.value) {
    return ''
  }
  const text = formatElapsed(props.record.startedAt)
  return text === '—' ? '' : `已耗时 ${text}`
})

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

/**
 * 问题行加稳定 key。
 *
 * 原先直接 `row-key="title"`：同名问题会让 key 重复，控制台刷 Vue 的重复 key 告警。
 * 用「单元路径 + 序号」而不是 title：同一单元内序号必然唯一（title 可能重复）。
 * 注意不能只靠行号 —— 无法定位行号的问题正是没有 line 的那一批。
 */
function issueRows(unit: any): any[] {
  return (unit.issues || []).map((issue: any, index: number) => ({
    ...issue,
    __key: `${unit.path ?? ''}#${index}`
  }))
}
</script>

<style scoped lang="less">
.review-result {
  .summary {
    color: #666;
  }
  .elapsed {
    color: #999;
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
