<template>
  <a-drawer
    :open="open"
    :title="`冻结来源 S${ordinal}`"
    width="min(780px, calc(100vw - 32px))"
    destroy-on-close
    @close="close"
  >
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error">
        <template #action><a-button size="small" @click="load">重试</a-button></template>
      </a-alert>
      <template v-else-if="source">
        <a-descriptions :column="2" size="small" bordered class="mb12">
          <a-descriptions-item label="结果" :span="2">
            {{ source.resultId }}
          </a-descriptions-item>
          <a-descriptions-item label="类型">{{ kindText(source.snapshot.kind) }}</a-descriptions-item>
          <a-descriptions-item label="任务">{{ source.snapshot.taskId }}</a-descriptions-item>
          <a-descriptions-item label="项目">{{ source.snapshot.project?.name || source.snapshot.project?.id }}</a-descriptions-item>
          <a-descriptions-item label="版本">{{ shortSha(source.snapshot.target?.headSha) }}</a-descriptions-item>
          <a-descriptions-item label="文件" :span="2">
            {{ source.snapshot.target?.path || 'Agent Markdown' }}
          </a-descriptions-item>
        </a-descriptions>

        <a-alert
          type="info"
          show-icon
          class="mb12"
          message="以下内容来自生成时的冻结快照；后续修改反馈、配置或源码不会替换这里。"
        />

        <template v-if="source.snapshot.kind === 'AGENT'">
          <h3>结果正文</h3>
          <MarkdownView :text="agentText" />
        </template>
        <template v-else>
          <h3>结果结论</h3>
          <p class="summary">{{ source.snapshot.result?.body?.summary || '无摘要' }}</p>

          <template v-if="issues.length">
            <h3>问题</h3>
            <div v-for="issue in issues" :key="issue.id || issue.ordinal" class="issue-card">
              <div class="row wrap">
                <a-tag :color="severityColor(issue.severity)">{{ issue.severity }}</a-tag>
                <strong>{{ issue.title }}</strong>
                <span class="muted small">{{ issue.category }}</span>
              </div>
              <p class="muted small">{{ locationsText(issue.locations) }}</p>
              <p><span class="label">原因：</span>{{ issue.reason || '—' }}</p>
              <p><span class="label">建议：</span>{{ issue.suggestion || '—' }}</p>
              <p><span class="label">当时反馈：</span>{{ feedbackText(issue.feedback) }}</p>
            </div>
          </template>

          <template v-if="patterns.length">
            <h3>模式候选</h3>
            <div v-for="(pattern, index) in patterns" :key="index" class="issue-card">
              <strong>{{ pattern.name }}</strong>
              <span class="muted small"> · {{ pattern.family }} · {{ pattern.role }}</span>
              <p>{{ pattern.explanation }}</p>
            </div>
          </template>

          <template v-if="observations.length">
            <h3>耦合观察</h3>
            <div v-for="(observation, index) in observations" :key="index" class="issue-card">
              <strong>{{ observation.title }}</strong>
              <p>{{ observation.explanation }}</p>
              <p v-if="observation.dependencyIds?.length" class="muted small">
                依赖：{{ observation.dependencyIds.join('、') }}
              </p>
            </div>
          </template>

          <template v-if="couplingFacts">
            <h3>耦合事实</h3>
            <p>
              仓库内已解析出向依赖 {{ couplingFacts.resolvedOutgoingCount ?? 0 }} 个，
              未可靠定位类型 {{ couplingFacts.unresolvedTypeCount ?? 0 }} 个。
            </p>
            <ul class="dependencies">
              <li v-for="edge in couplingFacts.edges || []" :key="edge.id">
                {{ edge.sourceType }} → {{ edge.targetType }}（{{ edge.relation }}，{{ edge.targetPath }}:{{ edge.line }}）
              </li>
            </ul>
          </template>

          <p v-if="limitations.length" class="muted small">
            限制：{{ limitations.join('；') }}
          </p>
        </template>

        <h3>当时结果级反馈</h3>
        <p>{{ feedbackText(source.snapshot.resultFeedback) }}</p>

        <h3>冻结材料</h3>
        <a-collapse v-if="materials.length" ghost>
          <a-collapse-panel
            v-for="(material, index) in materials"
            :key="material.id || index"
            :header="`${material.path || '材料'} · 行 ${material.startLine || 1}-${material.endLine || material.startLine || 1}`"
          >
            <div class="row wrap mb8">
              <code class="muted small">{{ material.sha }}</code>
              <span class="muted small">{{ sideText(material.side) }}</span>
              <span class="muted small">{{ material.completeness }}</span>
            </div>
            <pre v-if="material.content" class="code">{{ numbered(material.content, material.startLine || 1) }}</pre>
            <a-alert v-else type="warning" show-icon message="保存的代码正文缺失，不能用当前源码替换。" />
            <p v-if="material.diagnostic" class="muted small">{{ material.diagnostic }}</p>
          </a-collapse-panel>
        </a-collapse>
        <p v-else class="muted small">此来源没有保存代码材料。</p>
      </template>
    </a-spin>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { reportsApi, type ReportSourceSnapshot } from '@/api/v2'
import MarkdownView from '@/components/MarkdownView.vue'

const props = defineProps<{ open: boolean; reportId: string; ordinal: number }>()
const emit = defineEmits<{ (event: 'update:open', value: boolean): void }>()

const loading = ref(false)
const error = ref('')
const source = ref<ReportSourceSnapshot | null>(null)
let loadEpoch = 0

const issues = computed<any[]>(() => source.value?.snapshot?.issues || [])
const patterns = computed<any[]>(() => source.value?.snapshot?.result?.body?.patterns || [])
const observations = computed<any[]>(() => source.value?.snapshot?.result?.body?.observations || [])
const couplingFacts = computed<any>(() => source.value?.snapshot?.result?.provenance?.couplingFacts || null)
const materials = computed<any[]>(() => source.value?.snapshot?.materials || [])
const limitations = computed<string[]>(() => {
  const body = source.value?.snapshot?.result?.body?.limitations || []
  const coupling = couplingFacts.value?.limitations || []
  return [...body, ...coupling].filter((item: string) => !!item)
})
const agentText = computed(() => {
  const body = source.value?.snapshot?.result?.body
  return typeof body === 'string' ? body : ''
})

watch(
  () => [props.open, props.reportId, props.ordinal] as const,
  ([open]) => {
    loadEpoch++
    source.value = null
    error.value = ''
    if (open && props.ordinal > 0) void load()
  },
  { immediate: true, flush: 'sync' }
)

async function load() {
  const epoch = ++loadEpoch
  loading.value = true
  error.value = ''
  try {
    const loaded = await reportsApi.source(props.reportId, props.ordinal)
    if (epoch === loadEpoch) source.value = loaded
  } catch (failure) {
    if (epoch === loadEpoch) error.value = (failure as Error).message || '冻结来源读取失败'
  } finally {
    if (epoch === loadEpoch) loading.value = false
  }
}

function close() {
  loadEpoch++
  emit('update:open', false)
}

function kindText(kind: string) {
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度', AGENT: 'Agent' } as Record<string, string>)[kind] || kind
}

function severityColor(severity: string) {
  return ({ HIGH: 'red', MEDIUM: 'orange', LOW: 'blue' } as Record<string, string>)[severity] || 'default'
}

function feedbackText(feedback: any) {
  if (!feedback || !feedback.assessment) {
    return feedback?.comment ? `未判定（${feedback.comment}）` : '未反馈'
  }
  const label = ({ ACCURATE: '准确', INACCURATE: '不准确', UNKNOWN: '无法判断' } as Record<string, string>)[feedback.assessment] || feedback.assessment
  return feedback.comment ? `${label}（${feedback.comment}）` : label
}

function locationsText(locations: any[]) {
  if (!locations?.length) return '位置未保存'
  return locations.map((item) => `${item.path || ''}:${item.startLine}-${item.endLine}`).join('、')
}

function sideText(side: string) {
  return ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || side
}

function shortSha(sha: string) {
  return sha ? sha.slice(0, 12) : 'UNKNOWN'
}

function numbered(text: string, startLine: number) {
  return text.split('\n').map((line, index) =>
    `${String(startLine + index).padStart(6, ' ')} | ${line}`).join('\n')
}
</script>

<style scoped lang="less">
.mb12 {
  margin-bottom: 12px;
}
.mb8 {
  margin-bottom: 8px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wrap {
  flex-wrap: wrap;
}
.muted {
  color: #69798d;
}
.small {
  font-size: 12px;
}
.summary {
  font-size: 14px;
  line-height: 1.7;
  background: #f7f9fc;
  border: 1px solid #e3e9f0;
  border-radius: 6px;
  padding: 10px 12px;
}
.issue-card {
  padding: 10px 12px;
  margin-bottom: 10px;
  border: 1px solid #eef2f7;
  border-radius: 6px;
}
.issue-card p {
  margin: 4px 0;
  line-height: 1.6;
}
.label {
  color: #4d5d70;
  font-weight: 500;
}
.dependencies {
  padding-left: 1.3em;
  line-height: 1.7;
}
.code {
  max-height: 45vh;
  overflow: auto;
  margin: 0;
  padding: 10px;
  background: #f6f8fc;
  border: 1px solid #e3e9f0;
  border-radius: 6px;
  font: 12px/1.75 Consolas, 'Cascadia Code', monospace;
  white-space: pre;
}
</style>
