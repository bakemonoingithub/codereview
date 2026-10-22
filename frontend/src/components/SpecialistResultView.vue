<template>
  <div class="result-view">
    <a-alert
      v-if="result.completeness === 'INCOMPLETE'"
      type="warning"
      show-icon
      message="文件未完成：以下内容来自已成功的片段，不能作为完整文件结论或报告材料。"
      class="mb16"
    />
    <div class="summary">
      <div class="row">
        <a-tag :color="result.kind === 'ISSUE' ? 'blue' : result.kind === 'PATTERN' ? 'purple' : 'green'">
          {{ kindText }}
        </a-tag>
        <span class="muted small">协议 v{{ result.schemaVersion }}</span>
        <span class="spacer" />
        <span class="muted small">{{ result.createdAt?.replace('T', ' ').slice(0, 19) }}</span>
      </div>
      <p class="summary-text">{{ result.body.summary || '（无摘要）' }}</p>
    </div>

    <template v-if="result.kind === 'ISSUE'">
      <a-empty v-if="!result.body.issues?.length" description="基于本次材料未发现可说明的问题">
        <template #description>
          <span>未发现问题不等于完整验证了依赖实现或业务正确性。</span>
        </template>
      </a-empty>
      <div v-for="(issue, index) in result.body.issues" :key="index" class="issue">
        <div class="row wrap">
          <a-tag :color="severityColor(issue.severity)">{{ issue.severity }}</a-tag>
          <a-tag>{{ categoryText(issue.category) }}</a-tag>
          <strong>{{ issue.title }}</strong>
        </div>
        <p class="body">{{ issue.reason }}</p>
        <p v-if="issue.changeRelation" class="change">与本次变更：{{ issue.changeRelation }}</p>
        <p class="suggestion"><strong>建议：</strong>{{ issue.suggestion }}</p>
        <div class="locations">
          <span class="muted small">位置</span>
          <a-button
            v-for="(location, locationIndex) in issue.locations"
            :key="locationIndex"
            size="small"
            type="link"
            @click="openIssueLocation(issue, location)"
          >
            {{ modelLocationLabel(location) }}
          </a-button>
        </div>
        <div v-if="issue.evidence?.length" class="locations">
          <span class="muted small">辅助证据</span>
          <a-button
            v-for="(evidence, evidenceIndex) in issue.evidence"
            :key="'e-' + evidenceIndex"
            size="small"
            type="link"
            @click="openIssueLocation(issue, evidence)"
          >
            {{ modelLocationLabel(evidence) }}
          </a-button>
        </div>
        <FeedbackEditor v-if="result.issues[index] && result.completeness === 'COMPLETE'"
          :task-id="taskId" subject-kind="ISSUE" :subject-id="result.issues[index].id" subject-text="此问题"
          @saved="emit('feedback-saved')" />
      </div>
    </template>

    <template v-else-if="result.kind === 'PATTERN'">
      <a-empty v-if="!result.body.patterns?.length" description="未识别到有依据的模式候选" />
      <div v-for="(pattern, index) in result.body.patterns" :key="index" class="issue">
        <div class="row wrap">
          <a-tag color="purple">{{ pattern.family }}</a-tag>
          <strong>{{ pattern.name }}</strong>
          <span class="muted small">{{ pattern.role }}</span>
        </div>
        <p class="body">{{ pattern.explanation }}</p>
        <div class="locations">
          <span class="muted small">代码依据</span>
          <a-button
            v-for="(evidence, evidenceIndex) in pattern.evidence"
            :key="evidenceIndex"
            size="small"
            type="link"
            @click="openPatternEvidence(pattern, evidence)"
          >
            {{ modelLocationLabel(evidence) }}
          </a-button>
        </div>
      </div>
    </template>

    <template v-else>
      <div v-if="result.provenance.couplingFacts" class="summary">
        <strong>文件级依赖事实</strong>
        <p>仓库内已解析出向依赖：{{ result.provenance.couplingFacts.resolvedOutgoingCount }}
          · 未可靠定位类型：{{ result.provenance.couplingFacts.unresolvedTypeCount }}</p>
        <p class="muted small">对应版本 {{ result.provenance.couplingFacts.sha }} ·
          {{ sideText(result.provenance.couplingFacts.side) }}；未解析类型不计为零依赖，不包含全仓入向关系。</p>
        <p v-for="edge in result.provenance.couplingFacts.edges" :key="edge.id" class="small">
          {{ edge.id }} · {{ edge.sourceType }} → {{ edge.targetType }} · {{ edge.relation }}
          · 目标声明文件 {{ edge.targetPath }} · 本文件引用行 {{ edge.line }}
        </p>
      </div>
      <a-empty v-if="!result.body.observations?.length" description="未形成有依据的关系解释" />
      <div v-for="(observation, index) in result.body.observations" :key="index" class="issue">
        <strong>{{ observation.title }}</strong>
        <p class="body">{{ observation.explanation }}</p>
        <div v-if="observation.dependencyIds?.length" class="locations">
          <span class="muted small">依赖事实</span>
          <a-tag v-for="id in observation.dependencyIds" :key="id" color="blue">{{ dependencyLabel(id) }}</a-tag>
        </div>
        <p v-if="observation.suggestion" class="suggestion">
          <strong>建议：</strong>{{ observation.suggestion }}
        </p>
        <div class="locations">
          <span class="muted small">代码依据</span>
          <a-button
            v-for="(evidence, evidenceIndex) in observation.evidence"
            :key="evidenceIndex"
            size="small"
            type="link"
            @click="openObservationEvidence(observation, evidence)"
          >
            {{ modelLocationLabel(evidence) }}
          </a-button>
        </div>
      </div>
    </template>

    <div v-if="limitations.length" class="limitations">
      <strong class="small">边界与缺失</strong>
      <ul>
        <li v-for="(limitation, index) in limitations" :key="index">{{ limitation }}</li>
      </ul>
    </div>
    <FeedbackEditor v-if="result.kind !== 'ISSUE' && result.completeness === 'COMPLETE'"
      :task-id="taskId" subject-kind="RESULT" :subject-id="result.id"
      :subject-text="result.kind === 'PATTERN' ? '整个模式审查结果' : '整个耦合度审查结果'"
      @saved="emit('feedback-saved')" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ResultView } from '@/api/v2'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import { dedupeReferences, sameFileSide, type CodeReference, type CodeReferencePayload } from '@/utils/codeReferences'

interface ModelLocation {
  materialId: string
  startLine: number
  endLine: number
}

type BodyIssue = NonNullable<ResultView['body']['issues']>[number]
type BodyPattern = NonNullable<ResultView['body']['patterns']>[number]
type BodyObservation = NonNullable<ResultView['body']['observations']>[number]

const props = defineProps<{ result: ResultView; taskId: string }>()
const emit = defineEmits<{
  (event: 'open-code', value: CodeReferencePayload): void
  (event: 'feedback-saved'): void
}>()

const kindText = computed(() => ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度' } as Record<string, string>)[props.result.kind] || props.result.kind)

const limitations = computed(() => [...new Set([
  ...(props.result.body.limitations || []),
  ...(props.result.provenance.couplingFacts?.limitations || []),
  ...(props.result.provenance.materialGaps || []).map(gap => `${gap.code}：${gap.subject}，${gap.detail}`)
])])
function sideText(side: string): string {
  return ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || side
}
function dependencyLabel(id: string): string {
  const edge = props.result.provenance.couplingFacts?.edges.find(edge => edge.id === id)
  return edge ? `${edge.sourceType} → ${edge.targetType}（${edge.relation}）` : id
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

  const references = dedupeReferences(all
    .map(resolveReference)
    .filter((ref): ref is CodeReference => !!ref)
    .filter((ref) => sameFileSide(ref, target)))
  // 同范围可能来自另一个材料片段；保留去重后的一行，但必须用点击处
  // 的材料上下文，保存片段和发送范围才不会指到别的单元。
  const existing = references.findIndex((ref) => ref.startLine === target.startLine && ref.endLine === target.endLine)
  if (existing >= 0) {
    references[existing] = target
  } else {
    references.push(target)
    references.sort((a, b) => a.startLine - b.startLine || a.endLine - b.endLine)
  }

  const evidenceCount = dedupeReferences(evidence
    .map(resolveReference)
    .filter((ref): ref is CodeReference => !!ref)
    .filter((ref) => sameFileSide(ref, target))).length

  emit('open-code', { ...target, references, evidenceCount, scope })
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
</script>

<style scoped lang="less">
.result-view {
  padding: 4px 2px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wrap {
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.muted {
  color: #69798d;
}
.small {
  font-size: 12px;
}
.mb16 {
  margin-bottom: 16px;
}
.summary {
  padding: 14px 16px;
  background: #f7f9fc;
  border: 1px solid #e3e9f0;
  border-radius: 8px;
  margin-bottom: 16px;
}
.summary-text {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.8;
}
.issue {
  padding: 18px 0;
  border-bottom: 1px solid #e3e9f0;
}
.issue:last-of-type {
  border-bottom: 0;
}
.body {
  margin: 12px 0;
  line-height: 1.9;
  max-width: 880px;
}
.change,
.suggestion {
  font-size: 13px;
  line-height: 1.8;
  color: #4d5d70;
}
.locations {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.limitations {
  margin-top: 18px;
  padding: 14px 16px;
  background: #fffaf0;
  border: 1px solid #efdfbd;
  border-radius: 7px;
}
.limitations ul {
  margin: 8px 0 0;
  padding-left: 20px;
  font-size: 13px;
}
</style>
