<template>
  <a-modal
    :open="open" :title="`冻结来源 S${ordinal}`" :footer="null"
    width="min(1200px, calc(100vw - 40px))" :style="{ top: '4vh' }"
    wrap-class-name="report-source-modal" destroy-on-close @cancel="close"
  >
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error">
        <template #action><a-button size="small" @click="load">重试</a-button></template>
      </a-alert>
      <template v-else-if="source">
        <TaskDetailMetadata :task-id="source.snapshot.taskId || ''">
          {{ source.snapshot.project?.name || source.snapshot.project?.id || '项目未记录' }} · {{ kindText(source.snapshot.kind) }}
          <template v-if="source.snapshot.target?.path"> · {{ source.snapshot.target.path }}</template>
          <template #extra>结果 {{ source.resultId }} · 冻结于 {{ source.snapshot.frozenAt || '—' }}</template>
        </TaskDetailMetadata>
        <dl class="source-versions" aria-label="来源源码版本">
          <template v-if="sourceVersion.branch">
            <dt>分支</dt><dd><code>{{ sourceVersion.branch }}</code></dd>
          </template>
          <template v-if="sourceVersion.baseSha">
            <dt>修改前版本</dt><dd><code>{{ sourceVersion.baseSha }}</code></dd>
            <dt>修改后版本</dt><dd><code>{{ sourceVersion.headSha || '版本未记录' }}</code></dd>
          </template>
          <template v-else>
            <dt>源码版本</dt><dd><code>{{ sourceVersion.headSha || '版本未记录' }}</code></dd>
          </template>
        </dl>
        <a-alert class="notice" type="info" show-icon message="当前阅读报告生成时的冻结快照，结果、反馈和材料均以当时保存的内容为准。" />

        <div class="reader-tabs" role="tablist" aria-label="冻结来源内容">
          <button type="button" :class="{ active: activeTab === 'result' }" @click="activeTab = 'result'">审查结果</button>
          <button type="button" :class="{ active: activeTab === 'materials' }" @click="activeTab = 'materials'">冻结材料 · {{ materials.length }}</button>
          <button type="button" :class="{ active: activeTab === 'feedback' }" @click="activeTab = 'feedback'">当时反馈</button>
        </div>

        <div v-if="activeTab === 'result'">
          <AgentResultPanel v-if="agentBody" :body="agentBody" :show-download="false" @open-code="openCodeReference" />
          <SpecialistResultView
            v-else-if="specialistResult" :result="specialistResult"
            :task-id="source.snapshot.taskId || ''" read-only @open-code="openCodeReference"
          />
          <a-empty v-else description="冻结快照中没有可浏览的结果" />
        </div>

        <div v-else-if="activeTab === 'materials'">
          <ul v-if="materialGaps.length" class="material-gaps">
            <li v-for="(gap, index) in materialGaps" :key="index">{{ gap.code }}：{{ gap.subject }}，{{ gap.detail }}</li>
          </ul>
          <div v-for="(material, index) in materials" :key="material.metadata.id" class="material-row">
            <div class="material-main">
              <strong>{{ material.metadata.path || '材料' }}</strong>
              <span class="muted small">{{ material.metadata.sha || '版本未记录' }} · {{ sideText(material.metadata.side) }} · 第 {{ material.metadata.startLine }}–{{ material.metadata.endLine }} 行</span>
              <span class="muted small">{{ material.metadata.completeness || '完整性未记录' }}</span>
              <span v-if="material.metadata.diagnostic" class="muted small">{{ material.metadata.diagnostic }}</span>
              <span v-if="!material.complete" class="missing">快照未保存正文</span>
            </div>
            <a-button size="small" @click="openMaterial(index)">阅读冻结材料</a-button>
          </div>
          <a-empty v-if="!materials.length" description="此来源没有保存代码材料" />
        </div>

        <div v-else class="feedback">
          <h3>{{ source.snapshot.kind === 'ISSUE' ? '当时问题反馈' : '当时结果级反馈' }}</h3>
          <p class="feedback-summary">
            <template v-if="source.snapshot.kind === 'ISSUE'">{{ frozenFeedback.total }} 个问题 · </template>
            准确 {{ frozenFeedback.accurate }} · 不准确 {{ frozenFeedback.inaccurate }} ·
            无法判断 {{ frozenFeedback.unknown }} · 未反馈 {{ frozenFeedback.unassessed }}
          </p>
          <p v-if="source.snapshot.kind !== 'ISSUE'">{{ feedbackText(source.snapshot.resultFeedback) }}</p>
          <div v-for="(issue, index) in source.snapshot.issues || []" :key="issue.id || index" class="feedback-item">
            <strong>{{ issue.title || `问题 ${index + 1}` }}</strong>
            <p>{{ feedbackText(issue.feedback) }}</p>
          </div>
        </div>
      </template>
    </a-spin>

    <CodeReferenceModal
      v-if="source && codePayload" v-model:open="codeOpen"
      :task-id="source.snapshot.taskId || ''" :project-id="source.snapshot.project?.id || ''"
      :scope="codePayload.scope" :path="codePayload.path" :sha="codePayload.sha || ''" :side="codePayload.side"
      :references="codePayload.references" :active-start="codePayload.startLine" :active-end="codePayload.endLine"
      :evidence-count="codePayload.evidenceCount || 0" :frozen-materials="materials"
    />
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { reportsApi, type AgentResultView, type MaterialDetail, type ReportSourceSnapshot, type ResultView } from '@/api/v2'
import TaskDetailMetadata from '@/components/TaskDetailMetadata.vue'
import AgentResultPanel from '@/components/AgentResultPanel.vue'
import SpecialistResultView from '@/components/SpecialistResultView.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import { buildCodeReferencePayload, type CodeReference, type CodeReferencePayload } from '@/utils/codeReferences'

const props = defineProps<{ open: boolean; reportId: string; ordinal: number }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const loading = ref(false)
const error = ref('')
const source = ref<ReportSourceSnapshot | null>(null)
const activeTab = ref('result')
const codeOpen = ref(false)
const codePayload = ref<CodeReferencePayload | null>(null)
let loadEpoch = 0
onBeforeUnmount(() => { loadEpoch++ })

const sourceVersion = computed(() => {
  const snapshot = source.value?.snapshot
  return {
    branch: snapshot?.task?.branch,
    baseSha: snapshot?.target?.baseSha || snapshot?.task?.baseSha,
    headSha: snapshot?.target?.headSha || snapshot?.task?.commitSha
  }
})
const frozenFeedback = computed(() => {
  const snapshot = source.value?.snapshot
  const feedbacks = snapshot?.kind === 'ISSUE'
    ? (snapshot.issues || []).map((issue: any) => issue.feedback) : [snapshot?.resultFeedback]
  const counts = { total: feedbacks.length, accurate: 0, inaccurate: 0, unknown: 0, unassessed: 0 }
  for (const feedback of feedbacks) {
    if (feedback?.assessment === 'ACCURATE') counts.accurate++
    else if (feedback?.assessment === 'INACCURATE') counts.inaccurate++
    else if (feedback?.assessment === 'UNKNOWN') counts.unknown++
    else counts.unassessed++
  }
  return counts
})

const materials = computed<MaterialDetail[]>(() => (source.value?.snapshot?.materials || []).map((material: any) => ({
  metadata: {
    id: material.id || material.materialId || '', path: material.path || '', sha: material.sha || '',
    side: material.side || 'FILE', startLine: material.startLine ?? 1, endLine: material.endLine ?? material.startLine ?? 1,
    role: material.role || '', disposition: material.disposition || '', completeness: material.completeness || '',
    diagnostic: material.diagnostic || null, contentHash: material.contentHash || '', byteSize: material.byteSize || 0,
    sent: material.sent === true
  },
  content: typeof material.content === 'string' ? material.content : '',
  complete: typeof material.content === 'string'
})))
const materialGaps = computed<any[]>(() => source.value?.snapshot?.result?.provenance?.materialGaps || [])
const agentBody = computed<AgentResultView['body'] | null>(() => {
  const snapshot = source.value?.snapshot
  if (snapshot?.kind !== 'AGENT') return null
  const body = snapshot.result?.body
  const data = body && typeof body === 'object' ? body : {}
  return {
    title: data.title || 'Agent 分析结果', summary: data.summary || '', answerStatus: data.answerStatus || 'UNKNOWN',
    markdown: typeof body === 'string' ? body : data.markdown || '',
    citations: Array.isArray(data.citations) ? data.citations : [],
    unresolved: Array.isArray(data.unresolved) ? data.unresolved : []
  }
})
const specialistResult = computed<ResultView | null>(() => {
  const snapshot = source.value?.snapshot
  const result = snapshot?.result
  if (!snapshot || snapshot.kind === 'AGENT' || !result?.body || !result?.provenance) return null
  return {
    id: snapshot.resultId, targetId: snapshot.targetId, kind: snapshot.kind,
    schemaVersion: snapshot.schemaVersion, completeness: snapshot.completeness, createdAt: snapshot.resultCreatedAt,
    body: result.body, issues: (snapshot.issues || []).map((issue: any) => ({ ...issue, locations: issue.locations || [] })),
    provenance: result.provenance
  } as ResultView
})

watch(() => [props.open, props.reportId, props.ordinal] as const, ([open]) => {
  loadEpoch++
  source.value = null
  codePayload.value = null
  codeOpen.value = false
  activeTab.value = 'result'
  error.value = ''
  loading.value = false
  if (open && props.ordinal > 0) void load()
}, { immediate: true, flush: 'sync' })

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
  codeOpen.value = false
  emit('update:open', false)
}

function frozenReference(reference: CodeReference): CodeReference {
  const material = materials.value.find((item) => item.metadata.id === reference.materialId)?.metadata
  return material ? { ...reference, path: material.path, sha: material.sha || undefined, side: material.side } : reference
}

function openCodeReference(value: CodeReferencePayload) {
  codePayload.value = {
    ...buildCodeReferencePayload(frozenReference(value), value.references.map(frozenReference), value.scope),
    evidenceCount: value.evidenceCount
  }
  codeOpen.value = true
}

function openMaterial(index: number) {
  const references: CodeReference[] = materials.value.map((material, position) => ({
    ...material.metadata, materialId: material.metadata.id, label: `材料 ${position + 1}`
  }))
  const target = references[index]
  if (target) openCodeReference(buildCodeReferencePayload(target, references, 'result'))
}

function kindText(kind: string) {
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度', AGENT: 'Agent 分析' } as Record<string, string>)[kind] || kind
}
function sideText(side: string) {
  return ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || side
}
function feedbackText(feedback: any) {
  const label = feedback?.assessment
    ? ({ ACCURATE: '准确', INACCURATE: '不准确', UNKNOWN: '无法判断' } as Record<string, string>)[feedback.assessment] || feedback.assessment
    : '未反馈'
  return feedback?.comment ? `${label}（${feedback.comment}）` : label
}
</script>

<style lang="less">
.report-source-modal .ant-modal-body { max-height: 78vh; overflow: auto; overscroll-behavior: contain; }
</style>
<style scoped lang="less">
.notice { margin: 14px 0; }
.source-versions { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 6px 12px; margin: 14px 0; font-size: 13px; line-height: 1.8; }
.source-versions dt { color: var(--muted); }
.source-versions dd { margin: 0; overflow-wrap: anywhere; }
.reader-tabs { display: flex; gap: 24px; margin-bottom: 20px; border-bottom: 1px solid var(--line); }
.reader-tabs button { padding: 12px 0; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--muted); cursor: pointer; }
.reader-tabs button.active { border-bottom-color: var(--blue); color: var(--blue); font-weight: 600; }
.material-row { display: flex; align-items: center; gap: 18px; padding: 16px; margin-bottom: 10px; border: 1px solid var(--line); border-radius: 10px; }
.material-main { display: flex; flex: 1; flex-direction: column; gap: 5px; min-width: 0; overflow-wrap: anywhere; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.missing { color: var(--amber); font-size: 12px; }
.material-gaps { color: var(--muted); font-size: 12px; }
.feedback-item { margin: 12px 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; }
@media (max-width: 700px) { .material-row { align-items: flex-start; flex-direction: column; } }
</style>
