<template>
  <div class="agent-layout">
    <article class="card agent-article">
      <div class="row result-head">
        <StatusBadge :tone="body.answerStatus === 'ANSWERED' ? 'green' : 'amber'">
          {{ body.answerStatus === 'ANSWERED' ? '已回答' : body.answerStatus === 'INSUFFICIENT_EVIDENCE' ? '材料不足' : '状态未记录' }}
        </StatusBadge>
        <span class="spacer" />
        <a-button v-if="showDownload" size="small" :loading="downloading" @click="emit('download')">下载 Markdown</a-button>
      </div>
      <h2>{{ body.title }}</h2>
      <p class="muted">{{ body.summary }}</p>
      <a-alert
        v-if="body.answerStatus === 'INSUFFICIENT_EVIDENCE'"
        class="mb16" type="warning" show-icon
        message="本次调查材料不足；报告选材时会保留该限制，不视为全部问题已回答。"
      />
      <div v-if="body.unresolved.length" class="unresolved">
        <strong>未解决事项</strong>
        <ul><li v-for="(item, index) in body.unresolved" :key="index">{{ item }}</li></ul>
      </div>
      <MarkdownView :text="body.markdown" :citation-ids="citationIds" @citation-click="openCitation" />
    </article>
    <aside class="agent-side card">
      <div class="section-top"><h3>代码证据</h3><span class="spacer" /><span class="muted small">固定版本</span></div>
      <div class="pad">
        <p v-if="!body.citations.length" class="muted small">没有引用。</p>
        <div v-for="item in body.citations" :key="item.id" class="agent-evidence">
          <span class="muted small">{{ item.id }}</span>
          <button type="button" class="source-link" @click="openCitation(item.id)">
            {{ item.path }} : {{ item.startLine }}–{{ item.endLine }} ↗
          </button>
          <p class="muted small">
            <code>{{ item.sha?.slice(0, 7) || '—' }}</code>
            <template v-if="item.side && item.side !== 'FILE'"> · {{ item.side === 'BEFORE' ? '变更前' : '变更后' }}</template>
          </p>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AgentResultView } from '@/api/v2'
import MarkdownView from '@/components/MarkdownView.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { buildCodeReferencePayload, type CodeReference, type CodeReferencePayload } from '@/utils/codeReferences'

const props = withDefaults(defineProps<{
  body: AgentResultView['body']
  showDownload?: boolean
  downloading?: boolean
}>(), { showDownload: true, downloading: false })
const emit = defineEmits<{
  'open-code': [value: CodeReferencePayload]
  download: []
}>()
const citationIds = computed(() => props.body.citations.map((item) => item.id))

function openCitation(id: string) {
  const references: CodeReference[] = props.body.citations.map((citation) => ({
    label: citation.id, materialId: citation.materialId, path: citation.path,
    sha: citation.sha || undefined, side: citation.side || 'FILE',
    startLine: citation.startLine, endLine: citation.endLine
  }))
  const target = references.find((reference) => reference.label === id)
  if (!target) return
  const candidates = target.sha ? references : references.filter((reference) => reference.materialId === target.materialId)
  emit('open-code', buildCodeReferencePayload(target, candidates, 'result'))
}
</script>

<style scoped lang="less">
.agent-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(340px, 380px); gap: 24px; align-items: start; }
.card { min-width: 0; background: #fff; border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); overflow: hidden; }
.agent-article { padding: 28px 32px; line-height: 1.95; }
.agent-article h2 { margin: 4px 0 14px; font-size: 20px; }
.row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.result-head { margin-bottom: 16px; }
.spacer { flex: 1; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.unresolved ul { margin: 6px 0 0 18px; padding: 0; font-size: 13px; }
.section-top { display: flex; align-items: center; gap: 12px; padding: 18px 22px; border-bottom: 1px solid var(--line); }
.section-top h3 { margin: 0; }
.pad { padding: 18px 22px; max-height: min(70vh, 720px); overflow: auto; overscroll-behavior: contain; }
.agent-evidence { min-width: 0; padding: 13px 0; border-bottom: 1px solid var(--line); font-size: 12px; }
.agent-evidence:last-child { border-bottom: 0; }
.source-link { display: inline-block; width: 100%; margin-top: 6px; padding: 3px 7px; border: 0; background: var(--soft); border-radius: 6px; color: var(--blue); font-family: var(--font-mono); text-align: left; white-space: normal; overflow-wrap: anywhere; word-break: break-word; cursor: pointer; }
@media (max-width: 1000px) { .agent-layout { grid-template-columns: 1fr; } }
@media (min-width: 1001px) and (max-width: 1250px) { .agent-layout { grid-template-columns: minmax(0, 1fr) minmax(320px, 340px); } .agent-article { padding: 24px; } }
@media (max-width: 760px) { .agent-article { padding: 20px; } }
</style>
