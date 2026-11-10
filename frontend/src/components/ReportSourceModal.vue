<template>
  <a-modal
    :open="open" :footer="null" :closable="false" :keyboard="!codeOpen" :aria-label="`${sourceTitle} · ${readerState}`"
    width="min(1200px, calc(100vw - 40px))" :style="{ top: '4vh' }"
    wrap-class-name="report-source-modal" destroy-on-close @cancel="close"
  >
    <div class="source-reader">
      <header class="source-header">
        <div class="source-heading">
          <h1>{{ sourceTitle }}</h1><span class="readonly-badge">{{ readerState }}</span>
        </div>
        <button type="button" class="close-button" aria-label="关闭弹窗" @click="close">×</button>
        <p v-if="candidate || source" class="source-subtitle">
          {{ projectName }}
          <template v-if="targetName"> · {{ targetName }}</template>
        </p>
      </header>

      <div v-if="(source || previewResult) && !error" class="reader-tabs" role="tablist" :aria-label="candidate ? '审查结果预览' : '冻结来源内容'">
        <button v-for="tab in tabs" :id="`${readerId}-${tab.key}-tab`" :key="tab.key" type="button" role="tab"
          :class="{ active: activeTab === tab.key }" :aria-selected="activeTab === tab.key"
          :aria-controls="`${readerId}-${tab.key}-panel`" :tabindex="activeTab === tab.key ? 0 : -1"
          @click="selectTab(tab.key)" @keydown="onTabKeydown($event, tab.key)">{{ tab.label }}</button>
      </div>

      <div ref="scroller" class="reader-content">
        <a-spin :spinning="loading">
          <a-alert v-if="error" type="error" show-icon :message="error">
            <template #action><a-button size="small" @click="load">重试</a-button></template>
          </a-alert>
          <template v-else-if="source || previewResult">
            <div v-if="activeTab === 'result'" :id="`${readerId}-result-panel`" role="tabpanel" :aria-labelledby="`${readerId}-result-tab`" tabindex="0">
              <AgentResultPanel v-if="agentBody" :body="agentBody" :show-download="false" @open-code="openCodeReference" />
              <SpecialistReviewResultTab v-else-if="specialistResult" v-model:finding-index="findingIndex"
                :result="specialistResult" :task-id="originalTaskId" read-only report-reader @open-code="openCodeReference">
                <template v-if="source" #feedback="{ ordinal: itemOrdinal }">
                  <FrozenFeedbackView v-if="source.snapshot.kind === 'ISSUE' || usesItemFeedback" :feedback="itemFeedback(itemOrdinal)" />
                </template>
                <template v-if="source" #result-feedback>
                  <section v-if="source.snapshot.kind !== 'ISSUE' && !usesItemFeedback" class="legacy-feedback">
                    <FrozenFeedbackView title="当时整份结果反馈" :feedback="source.snapshot.resultFeedback" />
                    <p class="legacy-note">当时保存的是整份评价，未分配给各条结论。</p>
                  </section>
                </template>
              </SpecialistReviewResultTab>
              <a-empty v-else :description="candidate ? '没有可浏览的审查结果' : '冻结快照中没有可浏览的结果'" />
              <section v-if="source && !specialistResult && source.snapshot.kind !== 'ISSUE' && !usesItemFeedback" class="legacy-feedback">
                <FrozenFeedbackView title="当时整份结果反馈" :feedback="source.snapshot.resultFeedback" />
                <p v-if="source.snapshot.kind !== 'AGENT'" class="legacy-note">当时保存的是整份评价，未分配给各条结论。</p>
              </section>
              <section v-if="!specialistResult && materialGaps.length" class="result-gaps" aria-label="材料缺口">
                <h3>材料缺口 <span class="muted small">· {{ materialGaps.length }} 项</span></h3>
                <ul class="material-gaps"><li v-for="(gap, index) in materialGaps" :key="index">{{ gap.code }}：{{ gap.subject }}，{{ gap.detail }}</li></ul>
              </section>
              <p v-if="source" class="feedback-summary">
                当时反馈汇总 · 准确 {{ frozenFeedback.accurate }} · 不准确 {{ frozenFeedback.inaccurate }} ·
                无法判断 {{ frozenFeedback.unknown }} · 未反馈 {{ frozenFeedback.unassessed }}
              </p>
            </div>

            <div v-else :id="`${readerId}-input-panel`" class="review-input" role="tabpanel" :aria-labelledby="`${readerId}-input-tab`" tabindex="0">
              <section class="input-section" aria-label="来源范围">
                <h3>来源范围</h3>
                <dl class="input-pairs" aria-label="来源源码版本">
                  <dt>审查项目</dt><dd>{{ projectName }}</dd>
                  <dt>审查类型</dt><dd>{{ kindText(candidate?.kind || source?.snapshot.kind) }}</dd>
                  <template v-if="sourceVersion.branch"><dt>分支</dt><dd><code>{{ sourceVersion.branch }}</code></dd></template>
                  <template v-if="sourceVersion.pullRequest && (!candidate || sourceVersion.pullNumber != null)">
                    <dt>源码版本</dt>
                    <dd>
                      <PullSourceVersion :pull-number="sourceVersion.pullNumber" :pull-url="sourceVersion.pullUrl"
                        :merge-base="sourceVersion.mergeBase" :source-head="sourceVersion.sourceHead || sourceVersion.headSha"
                        :target-head="sourceVersion.targetHead" />
                    </dd>
                  </template>
                  <template v-else-if="sourceVersion.baseSha">
                    <dt>{{ sourceVersion.pullRequest ? '共同祖先' : '修改前版本' }}</dt><dd><code>{{ sourceVersion.baseSha }}</code></dd>
                    <dt>{{ sourceVersion.pullRequest ? '源提交' : '修改后版本' }}</dt><dd><code>{{ sourceVersion.headSha || '版本未记录' }}</code></dd>
                  </template>
                  <template v-else><dt>源码版本</dt><dd><code>{{ sourceVersion.headSha || '版本未记录' }}</code></dd></template>
                  <template v-if="targetPath"><dt>当前审查文件</dt><dd><code>{{ targetPath }}</code></dd></template>
                  <template v-if="oldTargetPath && oldTargetPath !== targetPath">
                    <dt>原文件路径</dt><dd><code>{{ oldTargetPath }}</code></dd>
                  </template>
                </dl>
                <p v-if="source" class="input-note">此快照未保存完整的模型、通用规则和本次要求。<template v-if="originalDetailHref">可打开原审查详情查看任务配置。</template></p>
                <p v-else-if="originalDetailHref" class="input-note">可打开原审查详情查看完整任务配置。</p>
              </section>

              <section v-if="source" class="input-section" aria-label="代码材料">
                <h3>冻结代码材料 <span class="muted small">· {{ materials.length }} 份</span></h3>
                <p class="muted small">以下是报告保存的代码材料；材料范围不代表完整任务输入。</p>
                <ul v-if="materialGaps.length" class="material-gaps"><li v-for="(gap, index) in materialGaps" :key="index">{{ gap.code }}：{{ gap.subject }}，{{ gap.detail }}</li></ul>
                <div v-for="(material, index) in materials" :key="material.metadata.id" class="material-row">
                  <div class="material-main">
                    <code>{{ material.metadata.path || '材料路径未记录' }}</code>
                    <span class="muted small">固定版本 {{ material.metadata.sha || '版本未记录' }} · {{ sideText(material.metadata.side) }} · 第 {{ material.metadata.startLine }}–{{ material.metadata.endLine }} 行</span>
                    <span class="muted small">{{ materialContentText(material.metadata.completeness) }}</span>
                    <span v-if="material.metadata.diagnostic" class="muted small">{{ material.metadata.diagnostic }}</span>
                    <span v-if="!material.complete" class="missing">快照未保存正文</span>
                  </div>
                  <a-button size="small" type="link" @click="openMaterial(index)">查看冻结代码 ↗</a-button>
                </div>
                <a-empty v-if="!materials.length" description="此来源没有保存代码材料" />
              </section>

              <section class="input-section" :aria-label="candidate ? '结果记录' : '冻结记录'">
                <h3>{{ candidate ? '结果记录' : '冻结记录' }}</h3>
                <dl class="input-pairs">
                  <template v-if="source"><dt>来源编号</dt><dd>S{{ ordinal }}</dd></template>
                  <template v-if="candidate?.taskName"><dt>审查任务</dt><dd>{{ candidate.taskName }}</dd></template>
                  <dt>任务 ID</dt><dd><code>{{ originalTaskId || '未记录' }}</code></dd>
                  <dt>结果 ID</dt><dd><code>{{ candidate?.resultId || source?.resultId || source?.snapshot.resultId || '未记录' }}</code></dd>
                  <dt>结果发布时间</dt><dd>{{ formatTime(candidate?.resultCreatedAt || source?.snapshot.resultCreatedAt) }}</dd>
                  <template v-if="source"><dt>冻结时间</dt><dd>{{ formatTime(source.snapshot.frozenAt) }}</dd></template>
                </dl>
              </section>
            </div>
          </template>
        </a-spin>
      </div>

      <footer class="reader-footer">
        <a v-if="originalDetailHref" :href="originalDetailHref" target="_blank" rel="noopener noreferrer" class="original-detail">打开原审查详情 ↗</a>
        <span v-if="source?.originalTaskAvailable === false" class="muted small">原审查已删除或不可用</span>
        <span class="spacer" /><a-button @click="close">关闭</a-button>
      </footer>
    </div>

    <CodeReferenceModal
      v-if="(source || previewResult) && codePayload" v-model:open="codeOpen" :task-id="originalTaskId" :project-id="projectId"
      :scope="codePayload.scope" :path="codePayload.path" :sha="codePayload.sha || ''" :side="codePayload.side"
      :references="codePayload.references" :active-start="codePayload.startLine" :active-end="codePayload.endLine"
      :evidence-count="codePayload.evidenceCount || 0" :frozen-materials="source ? materials : undefined"
      :initial-mode="codePayload.sha ? 'full' : 'saved'"
    />
  </a-modal>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { agentTasksApi, reportsApi, tasksApi, type AgentResultView, type MaterialDetail, type ReportCandidate, type ReportSourceSnapshot, type ResultView, type TaskView } from '@/api/v2'
import AgentResultPanel from '@/components/AgentResultPanel.vue'
import SpecialistReviewResultTab from '@/components/SpecialistReviewResultTab.vue'
import FrozenFeedbackView from '@/components/FrozenFeedbackView.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import PullSourceVersion from '@/components/PullSourceVersion.vue'
import { buildCodeReferencePayload, type CodeReference, type CodeReferencePayload } from '@/utils/codeReferences'

const props = defineProps<{ open: boolean; reportId?: string; ordinal?: number; candidate?: ReportCandidate | null }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const source = ref<ReportSourceSnapshot | null>(null)
const previewResult = ref<ResultView | AgentResultView | null>(null)
const previewTask = ref<TaskView | null>(null)
let previewTaskRequested = false
const tabs = [{ key: 'result', label: '审查结果' }, { key: 'input', label: '输入与配置' }] as const
type ReaderTab = typeof tabs[number]['key']
const activeTab = ref<ReaderTab>('result')
const findingIndex = ref(0)
const scroller = ref<HTMLElement | null>(null)
const codeOpen = ref(false)
const codePayload = ref<CodeReferencePayload | null>(null)
let loadEpoch = 0
onBeforeUnmount(() => { loadEpoch++ })

const readerId = computed(() => props.candidate ? `preview-${props.candidate.resultId}` : `source-${props.reportId}-${props.ordinal}`)
const sourceTitle = computed(() => {
  if (props.candidate) return `预览 · ${kindText(props.candidate.kind)}`
  return source.value ? `S${props.ordinal} · ${kindText(source.value.snapshot.kind)}` : `来源 S${props.ordinal}`
})
const readerState = computed(() => props.candidate ? '当前结果 · 只读' : '报告快照 · 只读')
const projectId = computed<string>(() => props.candidate?.projectId || source.value?.snapshot.project?.id || '')
const projectName = computed<string>(() => props.candidate?.projectName || source.value?.snapshot.project?.name || projectId.value || '项目未记录')
const originalTaskId = computed<string>(() => props.candidate?.taskId || source.value?.snapshot?.taskId || source.value?.snapshot?.task?.id || '')
const targetId = computed<string | null>(() => props.candidate?.targetId || source.value?.snapshot?.targetId || null)
const originalDetailHref = computed(() => originalTaskId.value && source.value?.originalTaskAvailable !== false ? router.resolve({
  name: 'review-detail', params: { id: originalTaskId.value },
  query: targetId.value ? { targetId: targetId.value } : {}
}).href : null)
const targetPath = computed<string | null>(() => props.candidate?.path || source.value?.snapshot?.target?.path || null)
const oldTargetPath = computed<string | null>(() => props.candidate?.oldPath || source.value?.snapshot?.target?.oldPath || null)
const targetName = computed(() => targetPath.value?.split(/[\\/]/).pop() || '')
const sourceVersion = computed(() => {
  const snapshot = source.value?.snapshot
  const candidate = props.candidate
  const task = snapshot?.task || previewTask.value
  const baseSha = candidate?.baseSha || snapshot?.target?.baseSha || task?.baseSha
  return {
    branch: candidate?.branch || task?.branch,
    baseSha,
    headSha: candidate?.headSha || snapshot?.target?.headSha || snapshot?.task?.commitSha || previewTask.value?.headSha,
    pullRequest: (candidate?.sourceKind || task?.sourceKind) === 'PR',
    pullNumber: task?.pullNumber,
    pullUrl: typeof task?.pullUrl === 'string' && /^https?:\/\//i.test(task.pullUrl) ? task.pullUrl : undefined,
    mergeBase: task?.mergeBase || baseSha,
    sourceHead: task?.sourceHead,
    targetHead: task?.targetHead
  }
})
const usesItemFeedback = computed(() => {
  const snapshot = source.value?.snapshot
  return snapshot?.snapshotVersion >= 2 && ['PATTERN', 'COUPLING'].includes(snapshot?.kind)
})
function itemFeedback(ordinal: number) {
  const snapshot = source.value?.snapshot
  const subjects = snapshot?.kind === 'ISSUE' ? snapshot.issues : snapshot?.items
  return subjects?.find((item: any) => item.ordinal === ordinal &&
    (snapshot?.kind === 'ISSUE' || item.kind === snapshot?.kind))?.feedback ?? null
}
const frozenFeedback = computed(() => {
  const snapshot = source.value?.snapshot
  const feedbacks = snapshot?.kind === 'ISSUE'
    ? (snapshot.issues || []).map((issue: any) => issue.feedback)
    : usesItemFeedback.value ? (snapshot?.items || []).filter((item: any) => item.kind === snapshot?.kind).map((item: any) => item.feedback)
      : [snapshot?.resultFeedback]
  const counts = { accurate: 0, inaccurate: 0, unknown: 0, unassessed: 0 }
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
const materialGaps = computed<NonNullable<ResultView['provenance']['materialGaps']>>(() => {
  const gaps = source.value?.snapshot?.result?.provenance?.materialGaps ?? previewResult.value?.provenance?.materialGaps
  return Array.isArray(gaps) ? gaps : []
})
const agentBody = computed<AgentResultView['body'] | null>(() => {
  const preview = previewResult.value
  if (preview && !('kind' in preview)) return preview.body
  const snapshot = source.value?.snapshot
  if (snapshot?.kind !== 'AGENT') return null
  const body = snapshot.result?.body
  const data = body && typeof body === 'object' ? body : {}
  return {
    title: data.title || 'Agent 分析结果', summary: data.summary || '', answerStatus: data.answerStatus || 'UNKNOWN',
    markdown: typeof body === 'string' ? body : data.markdown || '',
    citations: Array.isArray(data.citations) ? data.citations : [], unresolved: Array.isArray(data.unresolved) ? data.unresolved : []
  }
})
const specialistResult = computed<ResultView | null>(() => {
  const preview = previewResult.value
  if (preview && 'kind' in preview) return preview
  const snapshot = source.value?.snapshot
  const result = snapshot?.result
  if (!snapshot || snapshot.kind === 'AGENT' || !result?.body || !result?.provenance) return null
  return {
    id: snapshot.resultId, targetId: snapshot.targetId, kind: snapshot.kind,
    schemaVersion: snapshot.schemaVersion, completeness: snapshot.completeness, createdAt: snapshot.resultCreatedAt,
    body: result.body, issues: (snapshot.issues || []).map((issue: any) => ({ ...issue, locations: issue.locations || [] })),
    items: snapshot.items || [], provenance: result.provenance
  } as ResultView
})

watch(() => [props.open, props.reportId, props.ordinal, props.candidate] as const, ([open, reportId, ordinal, candidate]) => {
  loadEpoch++
  source.value = null
  previewResult.value = null
  previewTask.value = null
  previewTaskRequested = false
  findingIndex.value = 0
  codePayload.value = null
  codeOpen.value = false
  selectTab('result')
  error.value = ''
  loading.value = false
  if (open && (candidate || (reportId && ordinal && ordinal > 0))) void load()
}, { immediate: true, flush: 'sync' })

function selectTab(tab: ReaderTab) {
  activeTab.value = tab
  if (scroller.value) scroller.value.scrollTop = 0
  if (tab === 'input' && props.candidate?.sourceKind === 'PR' && !previewTaskRequested) {
    void loadPreviewTask(props.candidate.taskId)
  }
}
async function loadPreviewTask(taskId: string) {
  const epoch = loadEpoch
  previewTaskRequested = true
  try {
    const task = await tasksApi.detail(taskId)
    if (epoch === loadEpoch) previewTask.value = task
  } catch {
    // Keep the candidate's saved versions visible when task details are unavailable.
  }
}
async function onTabKeydown(event: KeyboardEvent, tab: ReaderTab) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const nextTab = event.key === 'Home' ? 'result' : event.key === 'End' ? 'input' : tab === 'result' ? 'input' : 'result'
  const tablist = (event.currentTarget as HTMLElement).parentElement
  selectTab(nextTab)
  await nextTick()
  tablist?.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus()
}
async function load() {
  const epoch = ++loadEpoch
  const { candidate, reportId, ordinal } = props
  loading.value = true
  error.value = ''
  try {
    if (candidate) {
      if (candidate.kind !== 'AGENT' && !candidate.targetId) throw new Error('这条结果没有可查看的文件目标')
      const result = candidate.kind === 'AGENT'
        ? await agentTasksApi.result(candidate.taskId)
        : await tasksApi.result(candidate.taskId, candidate.targetId!)
      if (epoch === loadEpoch) previewResult.value = result
    } else if (reportId && ordinal) {
      const loaded = await reportsApi.source(reportId, ordinal)
      if (epoch === loadEpoch) source.value = loaded
    }
  } catch (failure) {
    if (epoch === loadEpoch) error.value = (failure as Error).message || (candidate ? '审查结果读取失败' : '冻结来源读取失败')
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
  codePayload.value = source.value ? {
    ...buildCodeReferencePayload(frozenReference(value), value.references.map(frozenReference), value.scope),
    evidenceCount: value.evidenceCount
  } : value
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
function materialContentText(completeness: string) {
  return ({ COMPLETE: '所选范围的完整内容', DECLARATION_SUMMARY: '声明摘要（仅保留类型声明和方法签名，已省略方法体）',
    FRAGMENT: '代码片段（仅包含文件中的部分代码）', PARTIAL: '部分内容（所选范围未完整返回）',
    UNKNOWN: '内容是否完整尚未确认' } as Record<string, string>)[completeness] || '材料内容未说明'
}
function formatTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 19) : '未记录' }
</script>

<style lang="less">
.report-source-modal .ant-modal-body { padding: 0; }
.report-source-modal .ant-modal { padding-bottom: 0; }
</style>
<style scoped lang="less">
.source-reader { display: flex; flex-direction: column; height: min(900px, 92vh); min-height: 0; color: var(--ink); }
.source-header { flex: none; display: grid; grid-template-columns: minmax(0, 1fr) auto; column-gap: 12px; align-items: start; padding: 20px 28px 14px; border-bottom: 1px solid var(--line); }
.source-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 12px; min-height: 32px; }
.source-heading h1 { margin: 0; font-size: 22px; font-weight: 700; }
.readonly-badge { padding: 1px 8px; border-radius: 6px; font-size: 12px; color: var(--badge-default-ink); background: var(--badge-default-bg); white-space: nowrap; }
.close-button { border: 0; background: none; color: var(--faint); font-size: 25px; line-height: 1; cursor: pointer; width: 32px; height: 32px; border-radius: 7px; }
.source-subtitle { margin: 4px 0 0; color: var(--muted); font-size: 13px; overflow-wrap: anywhere; }
.reader-tabs { display: flex; flex: none; gap: 28px; padding: 0 28px; border-bottom: 1px solid var(--line); }
.reader-tabs button { padding: 12px 0; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--muted); cursor: pointer; }
.reader-tabs button.active { border-bottom-color: var(--blue); color: var(--blue); font-weight: 600; }
.reader-content { flex: 1; min-height: 0; padding: 24px 28px; overflow: auto; overscroll-behavior: contain; }
.feedback-summary { margin: 16px 0 0; color: var(--muted); font-size: 12px; }
.legacy-feedback, .result-gaps { margin-top: 20px; border-top: 1px solid var(--line); }
.legacy-feedback :deep(.frozen-feedback) { border: 0; }
.legacy-note { padding: 0 16px 12px; margin: 0; background: var(--bg); color: var(--muted); font-size: 12px; }
.result-gaps { padding-top: 16px; }
.result-gaps h3 { margin: 0; font-size: 15px; }
.input-section + .input-section { margin-top: 24px; padding-top: 24px; border-top: 1px solid var(--line); }
.input-section h3 { margin: 0 0 16px; font-size: 15px; }
.input-pairs { display: grid; grid-template-columns: 125px minmax(0, 1fr); gap: 12px 20px; margin: 0; font-size: 13px; }
.input-pairs dt { color: var(--muted); }
.input-pairs dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.input-pairs code { padding: 3px 7px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); }
code { font-family: var(--font-mono); font-size: 12px; }
.input-note { margin: 16px 0 0; color: var(--muted); font-size: 12px; }
.material-row { display: flex; align-items: center; gap: 20px; padding: 14px 0; border-bottom: 1px solid var(--line); }
.material-row:last-child { border: 0; }
.material-main { display: flex; flex: 1; flex-direction: column; gap: 5px; min-width: 0; overflow-wrap: anywhere; }
.material-row :deep(.ant-btn) { flex: none; }
.material-gaps { padding-left: 20px; color: var(--muted); font-size: 12px; }
.missing { color: var(--amber); font-size: 12px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.spacer { flex: 1; }
.reader-footer { display: flex; align-items: center; flex: none; flex-wrap: wrap; gap: 12px; padding: 12px 28px; border-top: 1px solid var(--line); }
.original-detail { padding: 6px 14px; border: 1px solid var(--control-border); border-radius: 9px; color: var(--ink); font-size: 13px; }
.original-detail:hover { color: var(--blue); border-color: var(--focus-border); }
.reader-tabs button:focus-visible, .close-button:focus-visible, .original-detail:focus-visible { outline: 2px solid var(--focus-border); outline-offset: 3px; }
@media (max-width: 700px) {
  .source-header, .reader-content { padding: 16px; }
  .reader-tabs { padding: 0 16px; }
  .reader-footer { padding: 10px 16px; }
  .input-pairs { grid-template-columns: 90px minmax(0, 1fr); gap: 10px; }
  .material-row { align-items: flex-start; flex-direction: column; gap: 8px; }
}
</style>
