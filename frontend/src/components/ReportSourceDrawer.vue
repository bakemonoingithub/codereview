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
          <a-space wrap class="mb12">
            <a-tag :color="agentStatus === 'ANSWERED' ? 'green' : 'orange'">
              {{ agentStatus === 'ANSWERED' ? '已回答' : '材料不足' }}
            </a-tag>
            <span class="muted small">未解决 {{ agentUnresolved.length }} 项 · 引用 {{ agentCitations.length }} 处</span>
          </a-space>
          <p v-if="agentSummary" class="summary">{{ agentSummary }}</p>
          <a-alert
            v-if="agentStatus !== 'ANSWERED'"
            type="warning"
            show-icon
            class="mb12"
            message="该来源材料不足；报告中必须保留未解决事项，不能整理成全部问题已回答。"
          />
          <MarkdownView :text="agentText" />
          <template v-if="agentUnresolved.length">
            <h3>未解决事项</h3>
            <ul class="small">
              <li v-for="(item, index) in agentUnresolved" :key="index">{{ item }}</li>
            </ul>
          </template>
          <template v-if="agentCitations.length">
            <h3>引用</h3>
            <p v-for="item in agentCitations" :key="item.id" class="small">
              <a-tag color="blue">{{ item.id }}</a-tag>
              {{ item.path }} 行 {{ item.startLine }}–{{ item.endLine }}
              <span class="muted">（材料 {{ item.materialId }}）</span>
            </p>
          </template>
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
              <p v-if="issueDetails(issue).changeRelation"><span class="label">变更关系：</span>{{ issueDetails(issue).changeRelation }}</p>
              <p v-if="issueDetails(issue).evidence?.length" class="muted small">辅助证据：{{ locationsText(issueDetails(issue).evidence) }}</p>
              <p><span class="label">当时反馈：</span>{{ feedbackText(issue.feedback) }}</p>
            </div>
          </template>

          <template v-if="patterns.length">
            <h3>模式候选</h3>
            <div v-for="(pattern, index) in patterns" :key="index" class="issue-card">
              <strong>{{ pattern.name }}</strong>
              <span class="muted small"> · {{ pattern.family }} · {{ pattern.role }}</span>
              <p>{{ pattern.explanation }}</p>
              <p class="muted small">代码依据：{{ locationsText(pattern.evidence) }}</p>
            </div>
          </template>

          <template v-if="observations.length">
            <h3>耦合观察</h3>
            <div v-for="(observation, index) in observations" :key="index" class="issue-card">
              <strong>{{ observation.title }}</strong>
              <p>{{ observation.explanation }}</p>
              <p v-if="observation.suggestion">建议：{{ observation.suggestion }}</p>
              <p class="muted small">代码依据：{{ locationsText(observation.evidence) }}</p>
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
        <ul v-if="materialGaps.length" class="muted small">
          <li v-for="(gap, index) in materialGaps" :key="index">{{ gap.code }}：{{ gap.subject }} {{ gap.detail }}</li>
        </ul>
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
            <CodeViewer
              v-if="material.content"
              :text="material.content"
              :path="material.path || ''"
              :start-line="material.startLine || 1"
            />
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
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { reportsApi, type ReportSourceSnapshot } from '@/api/v2'
import CodeViewer from '@/components/CodeViewer.vue'
import MarkdownView from '@/components/MarkdownView.vue'

const props = defineProps<{ open: boolean; reportId: string; ordinal: number }>()
const emit = defineEmits<{ (event: 'update:open', value: boolean): void }>()

const loading = ref(false)
const error = ref('')
const source = ref<ReportSourceSnapshot | null>(null)
let loadEpoch = 0
onBeforeUnmount(() => { loadEpoch++ })

const issues = computed<any[]>(() => source.value?.snapshot?.issues || [])
const patterns = computed<any[]>(() => source.value?.snapshot?.result?.body?.patterns || [])
const observations = computed<any[]>(() => source.value?.snapshot?.result?.body?.observations || [])
const couplingFacts = computed<any>(() => source.value?.snapshot?.result?.provenance?.couplingFacts || null)
const materials = computed<any[]>(() => source.value?.snapshot?.materials || [])
const materialGaps = computed<any[]>(() => source.value?.snapshot?.result?.provenance?.materialGaps || [])
const limitations = computed<string[]>(() => {
  const body = source.value?.snapshot?.result?.body?.limitations || []
  const coupling = couplingFacts.value?.limitations || []
  return [...body, ...coupling].filter((item: string) => !!item)
})
const agentBody = computed<any>(() => source.value?.snapshot?.result?.body || {})
const agentText = computed(() => {
  const body = source.value?.snapshot?.result?.body
  if (typeof body === 'string') return body
  return body?.markdown || ''
})
const agentStatus = computed(() => agentBody.value.answerStatus || 'UNKNOWN')
const agentSummary = computed(() => agentBody.value.summary || '')
const agentUnresolved = computed<string[]>(() => agentBody.value.unresolved || [])
const agentCitations = computed<any[]>(() => agentBody.value.citations || [])

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
  return locations.map((item) => {
    const material = materials.value.find((entry) => entry.id === item.materialId)
    return `${item.path || material?.path || item.materialId}:${item.startLine}-${item.endLine}（${sideText(item.side || material?.side || '')} ${item.sha || material?.sha || ''}）`
  }).join('、')
}

function issueDetails(issue: any) {
  return source.value?.snapshot?.result?.body?.issues?.[issue.ordinal - 1] || {}
}

function sideText(side: string) {
  return ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || side
}

function shortSha(sha: string) {
  return sha ? sha.slice(0, 12) : 'UNKNOWN'
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
