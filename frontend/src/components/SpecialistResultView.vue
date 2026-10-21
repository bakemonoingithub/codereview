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
            @click="openModelLocation(location)"
          >
            {{ locationLabel(location) }}
          </a-button>
        </div>
        <div v-if="resolvedIssue(index)?.locations?.length" class="locations">
          <span class="muted small">保存位置</span>
          <a-button
            v-for="(location, locationIndex) in (resolvedIssue(index)?.locations || [])"
            :key="'r-' + locationIndex"
            size="small"
            type="link"
            @click="openResolved(location)"
          >
            {{ resolvedLabel(location) }}
          </a-button>
        </div>
        <div v-if="issue.evidence?.length" class="locations">
          <span class="muted small">辅助证据</span>
          <a-button
            v-for="(evidence, evidenceIndex) in issue.evidence"
            :key="'e-' + evidenceIndex"
            size="small"
            type="link"
            @click="openModelLocation(evidence)"
          >
            {{ modelLocationLabel(evidence) }}
          </a-button>
        </div>
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
            @click="openModelLocation(evidence)"
          >
            {{ modelLocationLabel(evidence) }}
          </a-button>
        </div>
      </div>
    </template>

    <template v-else>
      <a-empty v-if="!result.body.observations?.length" description="未形成有依据的关系解释" />
      <div v-for="(observation, index) in result.body.observations" :key="index" class="issue">
        <strong>{{ observation.title }}</strong>
        <p class="body">{{ observation.explanation }}</p>
        <div v-if="observation.dependencyIds?.length" class="locations">
          <span class="muted small">依赖事实</span>
          <a-tag v-for="id in observation.dependencyIds" :key="id" color="blue">{{ id }}</a-tag>
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
            @click="openModelLocation(evidence)"
          >
            {{ modelLocationLabel(evidence) }}
          </a-button>
        </div>
      </div>
    </template>

    <div v-if="result.body.limitations?.length" class="limitations">
      <strong class="small">边界与缺失</strong>
      <ul>
        <li v-for="(limitation, index) in result.body.limitations" :key="index">{{ limitation }}</li>
      </ul>
    </div>
    <div class="feedback-placeholder">
      <span class="muted small">反馈控件预留：问题级／结果级评价与评论在 P32 接入。</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { IssueView, ResultView } from '@/api/v2'

interface CodeRef {
  materialId?: string
  path: string
  sha?: string
  side: string
  startLine: number
  endLine: number
}

const props = defineProps<{ result: ResultView }>()
const emit = defineEmits<{ (event: 'open-code', value: CodeRef): void }>()

const kindText = computed(() => ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度' } as Record<string, string>)[props.result.kind] || props.result.kind)

function resolvedIssue(index: number): IssueView | undefined {
  return props.result.issues[index]
}

function openResolved(location: IssueView['locations'][number]) {
  emit('open-code', {
    materialId: location.materialId,
    path: location.path,
    sha: location.sha,
    side: location.side,
    startLine: location.startLine,
    endLine: location.endLine
  })
}

function openModelLocation(location: { materialId: string; startLine: number; endLine: number }) {
  const material = props.result.provenance.materials?.find((item) => item.id === location.materialId)
  if (!material) {
    return
  }
  emit('open-code', {
    materialId: material.id,
    path: material.path,
    sha: material.sha,
    side: material.side,
    startLine: location.startLine,
    endLine: location.endLine
  })
}

function locationLabel(location: { materialId: string; startLine: number; endLine: number }): string {
  return `${location.materialId.slice(0, 8)}… : ${location.startLine}-${location.endLine}`
}

function modelLocationLabel(location: { materialId: string; startLine: number; endLine: number }): string {
  const material = props.result.provenance.materials?.find((item) => item.id === location.materialId)
  return material
    ? `${material.path} : ${location.startLine}-${location.endLine}`
    : locationLabel(location)
}

function resolvedLabel(location: IssueView['locations'][number]): string {
  return `${location.path} : ${location.startLine}-${location.endLine}`
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
.feedback-placeholder {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px dashed #d3dce8;
}
</style>
