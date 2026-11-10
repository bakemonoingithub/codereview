<template>
  <div class="specialist-result-tab" :class="{ 'with-finding-layout': usesFindingLayout, 'report-reader-result': reportReader }">
    <a-alert v-if="!complete" type="warning" show-icon class="incomplete"
      message="文件未完成：以下内容来自已成功的片段，不能作为完整文件结论或报告材料。" />
    <section class="conclusion" aria-label="专项总结">
      <div class="eyebrow">{{ kindText }} · {{ complete ? '文件结论' : '成功片段' }}</div>
      <h2 :class="{ 'conclusion-title': complete }">{{ complete ? '审查结论' : '已取得的分析内容' }}</h2>
      <p class="summary-text">{{ result.body.summary || '本次结果未提供摘要，请结合明细与材料边界阅读。' }}</p>
      <div class="metrics">
        <span>{{ complete ? '共' : '已取得' }} <b>{{ entries.length }}</b> {{ usesFindingLayout ? '项发现' : '条' + itemLabel }}</span>
        <template v-if="result.kind === 'ISSUE'">
          <span v-for="metric in severityCounts" :key="metric.severity">{{ severityText(metric.severity) }}严重度 <b>{{ metric.count }}</b></span>
        </template>
      </div>
    </section>

    <component
      :is="usesFindingLayout ? 'details' : 'section'"
      v-if="result.kind === 'COUPLING' && result.provenance.couplingFacts"
      class="facts"
    >
      <summary v-if="usesFindingLayout" class="disclosure-title">
        文件级依赖事实 <span class="muted">· 程序解析 · 已定位 {{ result.provenance.couplingFacts.resolvedOutgoingCount }} 条</span>
      </summary>
      <template v-else><div class="eyebrow">程序解析</div><h3>文件级依赖事实</h3></template>
      <div class="metrics"><span>已解析出向依赖 <b>{{ result.provenance.couplingFacts.resolvedOutgoingCount }}</b></span><span>未可靠定位类型 <b>{{ result.provenance.couplingFacts.unresolvedTypeCount }}</b></span><span>解析覆盖率 <b>{{ Math.round(result.provenance.couplingFacts.coverage * 100) }}%</b></span></div>
      <p class="muted version">对应版本 {{ result.provenance.couplingFacts.sha }} · {{ sideText(result.provenance.couplingFacts.side) }}；未解析类型不计为零依赖，不包含全仓入向关系。</p>
      <div v-if="result.provenance.couplingFacts.edges.length" class="table-scroll"><table><thead><tr><th>编号</th><th>来源类型 → 目标类型</th><th>关系</th><th>目标声明文件</th><th>本文件引用行</th></tr></thead><tbody>
        <tr v-for="edge in result.provenance.couplingFacts.edges" :key="edge.id"><td>{{ edge.id }}</td><td>{{ edge.sourceType }} → {{ edge.targetType }}</td><td>{{ relationText(edge.relation) }}</td><td>{{ edge.targetPath }}</td><td>{{ edge.line }}</td></tr>
      </tbody></table></div>
    </component>
    <h3 v-if="result.kind === 'COUPLING' && !usesFindingLayout" class="section-title">模型关系解读 <span class="muted">· {{ entries.length }} 条</span></h3>
    <a-alert
      v-if="showFindingIndex && result.hasLegacyFeedback"
      type="info"
      show-icon
      class="incomplete"
      message="历史整份结果反馈保留在历史记录中。索引仅显示逐项反馈。"
    />
    <div :class="{ 'finding-layout': showFindingIndex }">
      <div :class="{ 'finding-reader': usesFindingLayout && entries.length }">
        <div v-if="navigationEnabled && entries.length" ref="findingToolbar" class="finding-toolbar">
          <span class="finding-position" aria-live="polite">{{ itemLabel }} {{ currentIndex + 1 }} / {{ entries.length }}</span>
          <div class="finding-actions">
            <a-button size="small" :disabled="currentIndex === 0" @click="selectFinding(currentIndex - 1)">← 上一项</a-button>
            <a-button size="small" :disabled="currentIndex === entries.length - 1" @click="selectFinding(currentIndex + 1)">下一项 →</a-button>
          </div>
        </div>
        <section v-if="usesFindingLayout && !entries.length" class="finding-empty" aria-label="发现">
          <h3>{{ emptyFindingTitle }}</h3>
          <p>{{ emptyFindingText }}</p>
        </section>
        <template v-if="result.kind === 'ISSUE'">
          <a-empty v-if="!usesFindingLayout && !entries.length" description="基于本次材料未发现可说明的问题。未发现问题不等于完整验证了依赖实现或业务正确性。" />
          <article v-for="{ entry: issue, index } in visibleEntries(result.body.issues)" :key="subjectId(index + 1) || index" class="finding" :data-severity="issue.severity">
            <header class="finding-head">
              <div class="meta"><span v-if="!navigationEnabled">问题 {{ number(index + 1) }}</span><a-tag :color="severityColor(issue.severity)">{{ severityText(issue.severity) }}严重度</a-tag><a-tag>{{ categoryText(issue.category) }}</a-tag></div>
              <h3>{{ issue.title }}</h3>
            </header>
            <div class="finding-content">
              <section class="primary-copy"><h4>问题说明</h4><p>{{ issue.reason }}</p></section>
              <section v-if="issue.changeRelation" class="change"><h4>与本次变更的关系</h4><p>{{ issue.changeRelation }}</p></section>
              <section v-if="issue.suggestion" class="suggestion"><h4>修改建议</h4><p>{{ issue.suggestion }}</p></section>
              <section class="references"><h4>问题位置</h4>
                <a-button v-for="(location, i) in issue.locations" :key="i" class="reference" :disabled="!resolveReference(location)" @click="openIssueLocation(issue, location)">{{ modelLocationLabel(location) }} · {{ resolveReference(location) ? '查看代码 ↗' : '材料不可用' }}</a-button>
                <p v-if="!issue.locations?.length" class="muted">未提供问题位置</p>
              </section>
              <section v-if="issue.evidence?.length" class="references"><h4>辅助证据</h4>
                <a-button v-for="(evidence, i) in issue.evidence" :key="i" class="reference" :disabled="!resolveReference(evidence)" @click="openIssueLocation(issue, evidence)">{{ modelLocationLabel(evidence) }} · {{ resolveReference(evidence) ? '查看代码 ↗' : '材料不可用' }}</a-button>
              </section>
            </div>
            <slot name="feedback" :ordinal="index + 1" :subject-id="subjectId(index + 1)">
              <FeedbackEditor v-if="!readOnly && complete && subjectId(index + 1)" :task-id="taskId" subject-kind="ISSUE" :subject-id="subjectId(index + 1)!" subject-text="此问题" conclusion @saved="emit('feedback-saved')" />
            </slot>
          </article>
        </template>

        <template v-else-if="result.kind === 'PATTERN'">
          <a-empty v-if="!usesFindingLayout && !entries.length" description="未识别到有依据的模式候选" />
          <article v-for="{ entry: pattern, index } in visibleEntries(result.body.patterns)" :key="subjectId(index + 1) || index" class="finding pattern">
            <header class="finding-head"><div class="meta"><span v-if="!navigationEnabled">候选 {{ number(index + 1) }}</span><a-tag color="purple">{{ familyText(pattern.family) }}</a-tag></div><h3>{{ pattern.name }}</h3></header>
            <div class="finding-content">
              <section v-if="pattern.role" class="role"><h4>目标承担的角色</h4><p>{{ pattern.role }}</p></section>
              <section class="primary-copy"><h4>模式说明</h4><p>{{ pattern.explanation }}</p></section>
              <section class="references"><h4>代码依据</h4>
                <a-button v-for="(evidence, i) in pattern.evidence" :key="i" class="reference" :disabled="!resolveReference(evidence)" @click="openPatternEvidence(pattern, evidence)">{{ modelLocationLabel(evidence) }} · {{ resolveReference(evidence) ? '查看代码 ↗' : '材料不可用' }}</a-button>
                <p v-if="!pattern.evidence?.length" class="muted">未提供代码依据</p>
              </section>
            </div>
            <slot name="feedback" :ordinal="index + 1" :subject-id="subjectId(index + 1)">
              <FeedbackEditor v-if="!readOnly && complete && subjectId(index + 1)" :task-id="taskId" subject-kind="PATTERN" :subject-id="subjectId(index + 1)!" subject-text="此模式候选" conclusion @saved="emit('feedback-saved')" />
            </slot>
          </article>
        </template>

        <template v-else-if="result.kind === 'COUPLING'">
          <a-empty v-if="!usesFindingLayout && !entries.length" description="未形成有依据的关系解释" />
          <article v-for="{ entry: observation, index } in visibleEntries(result.body.observations)" :key="subjectId(index + 1) || index" class="finding coupling">
            <header class="finding-head"><div v-if="!navigationEnabled" class="meta">解读 {{ number(index + 1) }}</div><h3>{{ observation.title }}</h3></header>
            <div class="finding-content">
              <section class="primary-copy"><h4>关系说明</h4><p>{{ observation.explanation }}</p></section>
              <section v-if="observation.dependencyIds?.length">
                <h4>关联依赖</h4>
                <div class="dependencies">
                  <template v-for="dependency in observation.dependencyIds.map(dependencyView)" :key="dependency.id">
                    <details v-if="usesFindingLayout && dependency.edge" class="dependency-details">
                      <summary :title="dependency.fullLabel">
                        <span>{{ dependency.shortLabel }}</span>
                        <small>· {{ relationText(dependency.edge.relation) }}</small>
                      </summary>
                      <p class="dependency-full-name">{{ dependency.fullLabel }}</p>
                    </details>
                    <a-tag v-else color="blue">{{ dependency.legacyLabel }}</a-tag>
                  </template>
                </div>
              </section>
              <section v-if="observation.suggestion" class="suggestion"><h4>调整建议</h4><p>{{ observation.suggestion }}</p></section>
              <section class="references"><h4>代码依据</h4>
                <a-button v-for="(evidence, i) in observation.evidence" :key="i" class="reference" :disabled="!resolveReference(evidence)" @click="openObservationEvidence(observation, evidence)">{{ modelLocationLabel(evidence) }} · {{ resolveReference(evidence) ? '查看代码 ↗' : '材料不可用' }}</a-button>
                <p v-if="!observation.evidence?.length" class="muted">未提供代码依据</p>
              </section>
            </div>
            <slot name="feedback" :ordinal="index + 1" :subject-id="subjectId(index + 1)">
              <FeedbackEditor v-if="!readOnly && complete && subjectId(index + 1)" :task-id="taskId" subject-kind="COUPLING" :subject-id="subjectId(index + 1)!" subject-text="此依赖解读" conclusion @saved="emit('feedback-saved')" />
            </slot>
          </article>
        </template>
        <p v-if="navigationEnabled && !readOnly && entries.length && (!complete || !subjectId(currentIndex + 1))" class="finding-unavailable">
          {{ complete ? '此条目缺少反馈标识，暂不可反馈。' : '片段结果仅供阅读。只有完整文件结果支持反馈。' }}
        </p>
      </div>
      <SpecialistFindingIndex
        v-if="showFindingIndex"
        :items="indexItems"
        :active-index="currentIndex"
        :item-label="itemLabel"
        @select="selectFinding"
      />
    </div>
    <a-alert v-if="!navigationEnabled && !readOnly && complete && entries.length && entries.some((_, index) => !subjectId(index + 1))" type="warning" show-icon message="部分结论缺少稳定标识，暂不能反馈。请完成条目索引校验后重新读取。" />
    <slot name="result-feedback" />
    <component :is="navigationEnabled && !reportReader ? 'details' : 'aside'" v-if="limitations.length" class="limitations">
      <h3 v-if="reportReader">{{ limitationsTitle }} <span class="muted">· {{ limitations.length }} 项</span></h3>
      <summary v-else-if="navigationEnabled" class="disclosure-title">审查边界与材料缺失 <span class="muted">· {{ limitations.length }} 项</span></summary>
      <h4 v-else>审查边界与材料缺失</h4>
      <ul><li v-for="(limitation, index) in limitations" :key="index">{{ limitation }}</li></ul>
    </component>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { ResultView } from '@/api/v2'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import SpecialistFindingIndex, { type SpecialistFindingIndexItem } from '@/components/SpecialistFindingIndex.vue'
import { feedbackDraftKey, readFeedbackDraft } from '@/components/feedbackDrafts'
import { buildCodeReferencePayload, type CodeReference, type CodeReferencePayload } from '@/utils/codeReferences'

interface ModelLocation {
  materialId: string
  startLine: number
  endLine: number
}

type BodyIssue = NonNullable<ResultView['body']['issues']>[number]
type BodyPattern = NonNullable<ResultView['body']['patterns']>[number]
type BodyObservation = NonNullable<ResultView['body']['observations']>[number]

const props = withDefaults(defineProps<{
  result: ResultView
  taskId: string
  readOnly?: boolean
  reportReader?: boolean
  navigateFindings?: boolean
  findingIndex?: number
}>(), { readOnly: false, reportReader: false, navigateFindings: false, findingIndex: 0 })
const emit = defineEmits<{
  (event: 'open-code', value: CodeReferencePayload): void
  (event: 'feedback-saved'): void
  (event: 'update:findingIndex', index: number): void
}>()

const kindText = computed(() => ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度' } as Record<string, string>)[props.result.kind] || props.result.kind)

const scopeLimitations = computed(() => [
  ...(props.result.body.limitations || []),
  ...(props.result.provenance.couplingFacts?.limitations || [])
])
const materialGaps = computed(() => (props.result.provenance.materialGaps || [])
  .map(gap => `${gap.code}：${gap.subject}，${gap.detail}`))
const limitations = computed(() => [...new Set([...scopeLimitations.value, ...materialGaps.value])])
const limitationsTitle = computed(() => {
  if (!materialGaps.value.length) return '审查边界'
  return scopeLimitations.value.length ? '审查边界与材料缺口' : '材料缺口'
})
function sideText(side: string): string {
  return ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || side
}
const dependencyEdges = computed(() => new Map(
  props.result.provenance.couplingFacts?.edges.map(edge => [edge.id, edge])
))
const dependencyNames = computed(() => {
  const fullNames = new Set([...dependencyEdges.value.values()].flatMap(edge => [edge.sourceType, edge.targetType]))
  const suffixCounts = new Map<string, number>()
  const suffixesByName = new Map<string, string[]>()
  for (const name of fullNames) {
    const parts = name.split('.')
    const suffixes = parts.map((_, index) => parts.slice(index).join('.'))
    suffixesByName.set(name, suffixes)
    for (const suffix of suffixes) suffixCounts.set(suffix, (suffixCounts.get(suffix) ?? 0) + 1)
  }
  const labels = new Map<string, string>()
  for (const [name, suffixes] of suffixesByName) {
    labels.set(name, suffixes.reverse().find(suffix => suffixCounts.get(suffix) === 1) ?? name)
  }
  return labels
})

function dependencyView(id: string) {
  const edge = dependencyEdges.value.get(id)
  if (!edge) return { id, legacyLabel: id }
  const fullLabel = `${edge.sourceType} → ${edge.targetType}`
  const legacyLabel = `${fullLabel}（${edge.relation}）`
  if (!usesFindingLayout.value) return { id, legacyLabel }
  const label = (name: string) => dependencyNames.value.get(name) ?? name
  return { id, edge, fullLabel, shortLabel: `${label(edge.sourceType)} → ${label(edge.targetType)}`, legacyLabel }
}

/** 把结果里的 materialId 位置还原成固定版本坐标；材料缺失时不发射事件。 */
function resolveReference(location: ModelLocation): CodeReference | null {
  const material = props.result.provenance.materials?.find((item) => item.id === location.materialId)
  if (!material) return null
  return {
    materialId: material.id,
    path: material.path,
    sha: material.sha,
    side: material.side,
    startLine: location.startLine,
    endLine: location.endLine
  }
}

/**
 * 一次点击只阅读同一个文件、同一版本侧的引用；同问题其他文件的引用
 * 关闭弹窗后再从对应链接打开。
 */
function openReference(
  location: ModelLocation,
  all: ModelLocation[],
  scope: 'issue' | 'result',
  evidence: ModelLocation[] = []
) {
  const target = resolveReference(location)
  if (!target) return

  const references = all
    .map(resolveReference)
    .filter((ref): ref is CodeReference => !!ref)
  const evidenceReferences = evidence
    .map(resolveReference)
    .filter((ref): ref is CodeReference => !!ref)

  emit('open-code', buildCodeReferencePayload(target, references, scope, evidenceReferences))
}

function openIssueLocation(issue: BodyIssue, location: ModelLocation) {
  openReference(location, [...issue.locations, ...(issue.evidence || [])], 'issue', issue.evidence || [])
}

function openPatternEvidence(pattern: BodyPattern, location: ModelLocation) {
  openReference(location, pattern.evidence, 'result')
}

function openObservationEvidence(observation: BodyObservation, location: ModelLocation) {
  openReference(location, observation.evidence, 'result')
}

function locationLabel(location: ModelLocation): string {
  return `${location.materialId.slice(0, 8)}… : ${location.startLine}-${location.endLine}`
}

function modelLocationLabel(location: ModelLocation): string {
  const material = props.result.provenance.materials?.find((item) => item.id === location.materialId)
  return material
    ? `${material.path} : ${location.startLine}-${location.endLine} · ${sideText(material.side)}`
    : locationLabel(location)
}

function severityColor(severity: string): string {
  return severity === 'HIGH' ? 'red' : severity === 'MEDIUM' ? 'orange' : 'default'
}

function categoryText(category: string): string {
  return ({
    CORRECTNESS: '正确性',
    SECURITY: '安全',
    RELIABILITY: '可靠性',
    PERFORMANCE: '性能',
    MAINTAINABILITY: '可维护性',
    RULE: '规则'
  } as Record<string, string>)[category] || category
}

const complete = computed(() => props.result.completeness === 'COMPLETE')
const entries = computed(() => props.result.kind === 'ISSUE' ? props.result.body.issues || []
  : props.result.kind === 'PATTERN' ? props.result.body.patterns || [] : props.result.body.observations || [])
const itemLabel = computed(() => ({ ISSUE: '问题', PATTERN: '模式候选', COUPLING: '依赖解读' })[props.result.kind])
const navigationEnabled = computed(() => props.reportReader ? entries.value.length > 1 : props.navigateFindings && !props.readOnly)
const usesFindingLayout = computed(() => props.reportReader || navigationEnabled.value)
const showFindingIndex = computed(() => !props.reportReader && navigationEnabled.value && entries.value.length > 0)
const emptyFindingTitle = computed(() => ({
  ISSUE: '基于本次材料未发现可说明的问题',
  PATTERN: '未识别到有依据的模式候选',
  COUPLING: '未形成有依据的依赖解读'
})[props.result.kind])
const emptyFindingText = computed(() => {
  if (!complete.value) return '已取得的片段中没有可逐项阅读的发现。'
  if (props.result.kind === 'ISSUE') return '未发现问题不等于完整验证了依赖实现或业务正确性。'
  if (props.result.provenance.couplingFacts) return '可展开文件级依赖事实，查看程序解析结果。'
  return '可结合文件结论了解本次分析范围。'
})
const currentIndex = computed(() => Math.max(0, Math.min(props.findingIndex, entries.value.length - 1)))
const findingToolbar = ref<HTMLElement | null>(null)
const assessments = { ACCURATE: 'accurate', INACCURATE: 'inaccurate', UNKNOWN: 'unknown' } as const
const indexItems = computed<SpecialistFindingIndexItem[]>(() => entries.value.map((entry, index) => {
  const title = 'title' in entry ? entry.title : entry.name
  const id = subjectId(index + 1)
  if (!complete.value || !id) return { title, status: 'blocked' }

  const draft = readFeedbackDraft(feedbackDraftKey(props.taskId, props.result.kind, id))
  const saved = props.result.itemFeedback?.[id]
  // A recent save can finish after a refresh or after the editor has been unmounted.
  const baseline = draft && draft.revision >= (saved?.revision ?? 0) ? draft.baseline : undefined
  const assessment = baseline ? baseline.assessment : saved?.assessment
  const hasComment = baseline ? !!baseline.comment.trim() : saved?.hasComment
  const status = assessment ? assessments[assessment] : hasComment ? 'comment'
    : baseline || props.result.itemFeedback ? 'pending' : 'unavailable'
  return { title, status, dirty: draft?.dirty }
}))

function visibleEntries<T>(items: T[] = []) {
  if (!navigationEnabled.value) return items.map((entry, index) => ({ entry, index }))
  return items.slice(currentIndex.value, currentIndex.value + 1)
    .map(entry => ({ entry, index: currentIndex.value }))
}

async function selectFinding(index: number) {
  if (index < 0 || index >= entries.value.length || index === currentIndex.value) return
  emit('update:findingIndex', index)
  await nextTick()
  const toolbar = findingToolbar.value
  if (!toolbar) return
  if (props.reportReader) {
    toolbar.scrollIntoView({ block: 'nearest' })
    return
  }
  const bounds = toolbar.getBoundingClientRect()
  const visibleTop = Number.parseFloat(window.getComputedStyle(toolbar).scrollMarginTop) || 0
  if (bounds.top < visibleTop || bounds.bottom > window.innerHeight) toolbar.scrollIntoView({ block: 'start' })
}

const severityCounts = computed(() => ['HIGH', 'MEDIUM', 'LOW'].map(severity => ({ severity,
  count: (props.result.body.issues || []).filter(issue => issue.severity === severity).length })))
function subjectId(ordinal: number): string | undefined {
  return props.result.kind === 'ISSUE'
    ? props.result.issues.find(issue => issue.ordinal === ordinal)?.id
    : props.result.items?.find(item => item.kind === props.result.kind && item.ordinal === ordinal)?.id
}
function severityText(value: string): string {
  return ({ HIGH: '高', MEDIUM: '中', LOW: '低' } as Record<string, string>)[value] || value
}
function familyText(value: string): string {
  return ({ CREATIONAL: '创建型', STRUCTURAL: '结构型', BEHAVIORAL: '行为型', OTHER: '其他' } as Record<string, string>)[value] || value || '分类未记录'
}
function relationText(value: string): string {
  return ({ EXTENDS: '继承', IMPLEMENTS: '实现', FIELD: '字段', PARAMETER: '参数', RETURN: '返回值',
    INHERITANCE: '继承', IMPLEMENTATION: '实现', METHOD_CALL: '方法调用', INSTANTIATION: '实例化' } as Record<string, string>)[value] || value
}
function number(value: number): string { return String(value).padStart(2, '0') }
</script>

<style scoped lang="less">
.specialist-result-tab { color: var(--ink); min-width: 0; overflow-wrap: anywhere; }
h2, h3, h4, p { margin: 0; }
p { white-space: pre-wrap; line-height: 1.9; }
h2 { font-size: 20px; margin: 8px 0 12px; }
h3 { font-size: 17px; line-height: 1.6; }
h4 { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.eyebrow, .meta { font-size: 12px; color: var(--muted); }
.conclusion { background: var(--notice); border: 1px solid var(--notice-border); border-radius: 14px; padding: 22px 24px; margin-bottom: 24px; }
.summary-text { font-size: 14px; }
.metrics { display: flex; flex-wrap: wrap; gap: 10px 24px; margin-top: 18px; font-size: 12px; color: var(--muted); }
.metrics b { color: var(--ink); font-size: 18px; margin: 0 4px; }
.finding { border: 1px solid var(--line); border-top: 3px solid var(--line); border-radius: 12px; margin: 20px 0; overflow: hidden; }
.finding[data-severity="HIGH"] { border-top-color: #c33e43; }
.finding[data-severity="MEDIUM"] { border-top-color: #ca8a27; }
.finding.pattern { border-top-color: #8767b3; }
.finding.coupling { border-top-color: #458b7c; }
.finding-head { padding: 18px 22px; border-bottom: 1px solid var(--line); }
.meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 8px; }
.finding-content { padding: 22px; font-size: 14px; }
.specialist-result-tab:not(.with-finding-layout) .primary-copy { display: contents; }
.specialist-result-tab:not(.with-finding-layout) .primary-copy > p { margin-bottom: 20px; }
.finding-content section { margin-top: 20px; }
.change, .role { padding: 14px 16px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; }
.suggestion { border-left: 3px solid var(--notice-border); background: var(--notice); border-radius: 0 8px 8px 0; padding: 14px 16px; }
.reference { display: block; width: 100%; height: auto; padding: 10px 14px; text-align: left; white-space: normal; overflow-wrap: anywhere; margin: 8px 0 0; line-height: 1.8; font-size: 12px; color: var(--blue); background: var(--bg); }
.dependencies { display: flex; flex-wrap: wrap; gap: 8px; }
.dependencies :deep(.ant-tag) { white-space: normal; }
.facts { padding: 20px 22px; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; margin-bottom: 24px; }
.version { margin-top: 14px; font-size: 12px; }
.table-scroll { overflow-x: auto; margin-top: 18px; }
table { border-collapse: collapse; min-width: 640px; width: 100%; font-size: 12px; }
th, td { padding: 10px 12px; border-bottom: 1px solid var(--line); text-align: left; }
th { color: var(--muted); font-weight: 500; }
.section-title { margin-top: 24px; }
.muted { color: var(--muted); }
.limitations { margin-top: 24px; padding: 16px 20px; border-radius: 10px; background: var(--notice-amber-bg); border: 1px solid var(--notice-amber-border); color: var(--notice-amber-ink); }
.limitations h4 { color: inherit; }
.limitations ul { padding-left: 20px; margin-bottom: 0; font-size: 13px; line-height: 1.8; white-space: pre-wrap; }
.incomplete { margin-bottom: 18px; }
.with-finding-layout {
  container-type: inline-size;
  --result-radius: 10px;
  --result-inset: 14px;
  --result-gap: 12px;
}
.with-finding-layout > .conclusion {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 4px 12px;
  padding: 12px var(--result-inset);
  margin-bottom: var(--result-gap);
  border-radius: var(--result-radius);
}
.with-finding-layout > .conclusion .eyebrow,
.with-finding-layout .summary-text { grid-column: 1 / -1; }
.with-finding-layout > .conclusion h2 { margin: 0; font-size: 17px; line-height: 1.45; }
.with-finding-layout > .conclusion .conclusion-title { font-weight: 700; }
.with-finding-layout > .conclusion .metrics { grid-column: 2; grid-row: 2; margin: 0; }
.with-finding-layout .summary-text { grid-row: 3; }
.with-finding-layout p { line-height: 1.65; }
.with-finding-layout .metrics { gap: 6px 12px; margin-top: 8px; }
.with-finding-layout .metrics b { font-size: inherit; margin: 0 2px; }
.with-finding-layout > details {
  padding: 0 var(--result-inset);
  border: 1px solid var(--line);
  border-radius: var(--result-radius);
  background: var(--bg);
  color: var(--ink);
}
.with-finding-layout .facts { margin-bottom: var(--result-gap); }
.with-finding-layout .limitations { margin-top: var(--result-gap); }
.with-finding-layout > details[open] { padding-bottom: 12px; }
.disclosure-title { cursor: pointer; padding: 10px 0; font-weight: 600; }
.disclosure-title .muted { font-size: 12px; font-weight: 400; }
.finding-layout { display: grid; grid-template-columns: minmax(0, 1fr) 214px; align-items: start; gap: var(--result-gap); }
.finding-layout .finding-index { border-radius: var(--result-radius); padding: 12px var(--result-inset); }
.finding-reader { min-width: 0; border: 1px solid var(--line); border-radius: var(--result-radius); }
.finding-reader .finding { margin: 0; border: 0; border-radius: 0 0 var(--result-radius) var(--result-radius); }
.finding-reader .finding-head { padding: 12px var(--result-inset) 0; border: 0; }
.finding-reader .finding-head h3 { font-weight: 700; }
.finding-reader .finding-head::after {
  content: '';
  display: block;
  margin-top: 10px;
  border-top: 2px solid #bdc9d7;
}
.finding-reader .meta { margin-bottom: 4px; }
.finding-reader .finding-content { display: grid; gap: var(--result-gap); padding: 12px var(--result-inset); }
.finding-reader .finding-content section { margin: 0; padding: 0; border: 0; background: none; }
.finding-reader .finding-content > section + section::before {
  content: '';
  display: block;
  width: 40px;
  margin-bottom: 8px;
  border-top: 2px solid #c7d1de;
}
.finding-reader h4 { margin: 0 0 3px; font-weight: 600; }
.finding-reader .reference {
  width: fit-content;
  max-width: 100%;
  padding: 0;
  margin: 3px 0 0;
  border: 0;
  background: none;
  box-shadow: none;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.finding-reader .reference:disabled { color: var(--muted); text-decoration: none; }
.finding-reader .dependencies { align-items: flex-start; }
.dependency-details {
  min-width: 0;
  max-width: 100%;
  border: 1px solid var(--notice-border);
  border-radius: 8px;
  background: var(--notice);
}
.dependency-details summary { padding: 3px 8px; cursor: pointer; font-size: 12px; }
.dependency-details small { margin-inline-start: 4px; color: var(--muted); font-size: inherit; }
.dependency-full-name {
  padding: 5px 8px;
  border-top: 1px solid var(--notice-border);
  font-size: 12px;
}
.finding-reader :deep(.feedback.conclusion) { padding: 12px var(--result-inset); background: var(--bg); }
.report-reader-result .finding-toolbar { scroll-margin-top: var(--result-gap); }
.report-reader-result .limitations { padding: 12px var(--result-inset); border-radius: var(--result-radius); }
.report-reader-result .limitations h3 { font-size: 15px; }
.report-reader-result .limitations h3 .muted { font-size: 12px; font-weight: 400; }
.report-reader-result .limitations ul { margin-top: 6px; }
.finding-empty {
  padding: 14px var(--result-inset);
  border: 1px dashed var(--line);
  border-radius: var(--result-radius);
  background: var(--bg);
}
.finding-empty h3 { font-size: 14px; font-weight: 600; }
.finding-empty p { color: var(--muted); font-size: 13px; margin-top: 3px; }
.finding-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding: 9px var(--result-inset);
  border-radius: var(--result-radius) var(--result-radius) 0 0;
  border-bottom: 1px solid var(--line);
  background: var(--bg);
  scroll-margin-top: 88px;
}
.finding-position { color: var(--ink); font-weight: 650; font-size: 13px; font-variant-numeric: tabular-nums; }
.finding-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.finding-unavailable { color: var(--muted); font-size: 12px; border-top: 1px solid var(--line); padding: 10px 16px; }
@container (max-width: 630px) {
  .finding-layout { grid-template-columns: minmax(0, 1fr); }
  .finding-layout .finding-index { grid-row: 1; position: static; }
}
@container (max-width: 520px) {
  .with-finding-layout > .conclusion { grid-template-columns: minmax(0, 1fr); }
  .with-finding-layout > .conclusion .metrics { grid-column: 1; grid-row: 3; }
  .with-finding-layout .summary-text { grid-row: 4; }
}
@media (max-width: 700px) { .conclusion, .finding-head, .finding-content, .facts { padding: 16px; } }
</style>
