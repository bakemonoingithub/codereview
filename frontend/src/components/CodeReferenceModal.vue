<template>
  <a-modal
    :open="open"
    :footer="null"
    :closable="false"
    :width="1160"
    :style="{ top: '5vh' }"
    wrap-class-name="code-reference-modal"
    :mask-closable="true"
    :destroy-on-close="false"
    @cancel="close"
  >
    <div class="reader">
      <header class="reader-head">
        <div class="head-main">
          <div class="title-line">
            <h2>{{ fileName }}</h2>
            <span class="pill">{{ scope === 'issue' ? '问题引用' : '结果引用' }}</span>
            <span v-if="isFrozen" class="pill">报告冻结</span>
            <span v-if="active?.label" class="pill">{{ active.label }}</span>
            <span v-if="active" class="pill blue">定位 第 {{ activeLabel }} 行</span>
          </div>
          <div class="sub-line">
            <span class="path" :title="path">{{ path }}</span>
            <span class="meta">
              <span>固定版本 <code>{{ shortSha }}</code></span>
              <span>·</span>
              <span>{{ sideLabel }}</span>
              <span>·</span>
              <span>{{ sentText }}</span>
            </span>
          </div>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭代码阅读" @click="close">×</button>
      </header>

      <div class="toolbar">
        <a-radio-group v-model:value="mode" size="small" button-style="solid">
          <a-radio-button value="saved">{{ savedLabel }}</a-radio-button>
          <a-radio-button value="full">固定版本全文</a-radio-button>
        </a-radio-group>
        <span class="source-note">{{ mode === 'saved' ? savedRangeText : fullRangeText }}</span>
        <span class="scope-text">
          {{ scopeText }}在此文件的 <b>{{ references.length }}</b> 处引用<template v-if="evidenceCount">（含 {{ evidenceCount }} 处辅助证据）</template>
        </span>
        <div class="nav">
          <a-button size="small" :disabled="!canPrev" @click="go(-1)">↑ 上一处</a-button>
          <span class="counter">{{ references.length ? activePosition : '0 / 0' }}</span>
          <a-button size="small" :disabled="!canNext" @click="go(1)">下一处 ↓</a-button>
        </div>
      </div>

      <div v-if="bannerText" class="banner">
        <span>{{ bannerText }}</span>
        <span class="spacer"></span>
        <a-button v-if="mode === 'saved'" size="small" @click="mode = 'full'">查看固定版本全文</a-button>
      </div>

      <div ref="scroller" class="code-wrap" tabindex="0" aria-label="代码内容">
        <div v-if="busy" class="state">
          <a-spin />
          <p>正在读取{{ mode === 'saved' ? savedLabel : '固定版本全文' }}…</p>
        </div>
        <div v-else-if="loadError" class="state error">
          <span class="state-icon" aria-hidden="true">!</span>
          <strong>{{ mode === 'saved' ? savedLabel : '固定版本全文' }}读取失败</strong>
          <p>{{ loadError }}</p>
          <div class="state-actions">
            <a-button type="primary" size="small" @click="retry">重试</a-button>
            <a-button v-if="mode === 'full' && active && active.materialId" size="small" @click="mode = 'saved'">返回{{ savedLabel }}</a-button>
            <a-button v-else-if="mode === 'saved'" size="small" @click="mode = 'full'">查看固定版本全文</a-button>
          </div>
        </div>
        <CodeViewer
          v-else
          ref="viewer"
          :text="viewText"
          :path="path"
          :start-line="viewStartLine"
          :ranges="references"
          :active-range="active"
        />
      </div>

      <footer class="reader-foot">
        <span class="legend">
          <i class="swatch active"></i>{{ scope === 'issue' ? '当前引用' : '当前依据' }}
          <i class="swatch other"></i>{{ scope === 'issue' ? '本问题其他引用' : '本结果其他依据' }}
        </span>
        <span class="spacer"></span>
        <span class="hint">{{ footerHint }}</span>
        <span class="hint kbd-hint"><kbd>Alt</kbd> + <kbd>←</kbd>/<kbd>→</kbd> 切换</span>
        <a-button size="small" @click="close">关闭</a-button>
      </footer>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import CodeViewer from '@/components/CodeViewer.vue'
import { repositoryApi, tasksApi, type MaterialDetail } from '@/api/v2'
import { dedupeReferences, type CodeReference } from '@/utils/codeReferences'

interface CodeViewerHandle {
  scrollToLine: (line: number, container: HTMLElement | null, instant?: boolean) => void
}

const props = withDefaults(defineProps<{
  open: boolean
  taskId: string
  projectId: string
  scope: 'issue' | 'result'
  path: string
  sha: string
  side: string
  references: CodeReference[]
  activeStart: number
  activeEnd: number
  evidenceCount?: number
  initialMode?: 'saved' | 'full'
  /** 传入后，片段只来自报告快照，缺失时不回查当前任务材料。 */
  frozenMaterials?: MaterialDetail[]
}>(), {
  evidenceCount: 0,
  initialMode: 'full'
})

const emit = defineEmits<{ (event: 'update:open', value: boolean): void }>()
const isFrozen = computed(() => props.frozenMaterials !== undefined)
const savedLabel = computed(() => isFrozen.value ? '冻结片段' : '保存片段')

const mode = ref<'saved' | 'full'>('full')
const fullText = ref('')
const fullLoading = ref(false)
const fullLoaded = ref(false)
const fullError = ref('')
const savedById = ref<Record<string, MaterialDetail>>({})
const materialState = ref<Record<string, 'loading' | 'error' | 'done'>>({})
const activeIndex = ref(0)
const viewer = ref<CodeViewerHandle | null>(null)
const scroller = ref<HTMLElement | null>(null)
let generation = 0

const references = computed(() => dedupeReferences(props.references.filter((ref) =>
  ref.path === props.path && (ref.sha || '') === (props.sha || '') && ref.side === props.side
)))
const active = computed<CodeReference | null>(() => references.value[activeIndex.value] || null)
const activeLabel = computed(() => active.value ? fmtRange(active.value) : '')
const activePosition = computed(() => `${activeIndex.value + 1} / ${references.value.length}`)
const activeMaterial = computed(() => {
  const id = active.value?.materialId
  return id ? savedById.value[id] || null : null
})
const savedView = computed(() => {
  const detail = activeMaterial.value
  if (!detail) return null
  const start = detail.metadata.startLine || 1
  const lineCount = detail.content ? detail.content.split('\n').length : 0
  return { start, end: start + Math.max(lineCount - 1, 0) }
})

function inSaved(ref: CodeReference): boolean {
  const view = savedView.value
  return !!view && ref.startLine >= view.start && ref.endLine <= view.end
}

const savedLoading = computed(() => {
  if (mode.value !== 'saved') return false
  const id = active.value?.materialId
  if (!id) return false
  if (savedById.value[id]) return false
  return materialState.value[id] !== 'error'
})
const savedError = computed(() => {
  if (mode.value !== 'saved') return ''
  const id = active.value?.materialId
  if (!id) return '该引用没有关联的保存片段，无法查看实际发送内容。'
  if (savedById.value[id]) return ''
  return materialState.value[id] === 'error'
    ? isFrozen.value ? '报告快照未保存该材料的可用正文，无法读取冻结片段。' : '保存片段读取失败。'
    : ''
})
const busy = computed(() => mode.value === 'full' ? fullLoading.value : savedLoading.value)
const loadError = computed(() => mode.value === 'full' ? fullError.value : savedError.value)
const viewText = computed(() => mode.value === 'saved' ? (activeMaterial.value?.content || '') : fullText.value)
const viewStartLine = computed(() => mode.value === 'saved' ? (savedView.value?.start || 1) : 1)
const fullLineCount = computed(() => fullText.value ? fullText.value.split('\n').length : 0)
const activeInView = computed(() => {
  if (!active.value) return false
  if (mode.value === 'saved') return inSaved(active.value)
  return fullLoaded.value && active.value.endLine <= fullLineCount.value
})
const outsideCount = computed(() => mode.value === 'saved' && savedView.value
  ? references.value.filter((ref) => !inSaved(ref)).length
  : 0)

const fullRangeText = computed(() => {
  const note = `扩展阅读，不改变${isFrozen.value ? '冻结' : '发送'}内容`
  if (!fullLoaded.value) return `全文件 · ${note}`
  return fullLineCount.value
    ? `全文件 1–${fullLineCount.value} 行 · ${note}`
    : `空文件 · ${note}`
})
const savedRangeText = computed(() => savedView.value
  ? `${isFrozen.value ? '报告冻结' : '实际发送'} · 第 ${savedView.value.start}–${savedView.value.end} 行`
  : isFrozen.value ? '报告保存的冻结片段' : '实际发送的片段')
const sentText = computed(() => {
  if (!active.value?.materialId) return '未关联材料'
  const detail = activeMaterial.value
  if (detail) return `${isFrozen.value ? '报告冻结' : '本次发送'}第 ${detail.metadata.startLine}–${detail.metadata.endLine} 行`
  return materialState.value[active.value.materialId] === 'error'
    ? `${isFrozen.value ? '冻结' : '本次发送'}范围未知`
    : `${isFrozen.value ? '冻结' : '本次发送'}范围读取中`
})
const scopeText = computed(() => props.scope === 'issue' ? '本问题' : '本结果')
const footerHint = computed(() => {
  const parts: string[] = []
  if (outsideCount.value) parts.push(`${outsideCount.value} 处引用不在${isFrozen.value ? '冻结' : '发送'}片段内`)
  parts.push(props.scope === 'issue' ? '同一审查的其他问题引用不标记' : '其他结果的引用不标记')
  return parts.join(' · ')
})
const bannerText = computed(() => {
  if (!active.value) return ''
  if (mode.value === 'full' && fullLoaded.value && active.value.endLine > fullLineCount.value) {
    return `引用行号超出固定版本范围（文件共 ${fullLineCount.value} 行）。`
  }
  if (mode.value === 'saved' && savedView.value && !activeInView.value) {
    return `当前引用第 ${fmtRange(active.value)} 行不在${isFrozen.value ? '报告冻结' : '本次发送'}的片段（第 ${savedView.value.start}–${savedView.value.end} 行）内。`
  }
  return ''
})
const navDisabled = computed(() => busy.value || !!loadError.value || references.value.length === 0)
const canPrev = computed(() => !navDisabled.value && activeIndex.value > 0)
const canNext = computed(() => !navDisabled.value && activeIndex.value < references.value.length - 1)

const fileName = computed(() => props.path.split('/').pop() || props.path)
const shortSha = computed(() => props.sha ? props.sha.slice(0, 12) : '未记录')
const sideLabel = computed(() => (
  ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[props.side] || props.side
))

function fmtRange(ref: { startLine: number; endLine: number }): string {
  return ref.startLine === ref.endLine ? String(ref.startLine) : `${ref.startLine}–${ref.endLine}`
}

function findActiveIndex(): number {
  const index = references.value.findIndex((ref) => ref.startLine === props.activeStart && ref.endLine === props.activeEnd)
  return index < 0 ? 0 : index
}

function resetState() {
  generation += 1
  mode.value = isFrozen.value ? 'saved' : props.initialMode
  activeIndex.value = findActiveIndex()
  fullText.value = ''
  fullLoaded.value = false
  fullLoading.value = false
  fullError.value = ''
  savedById.value = {}
  materialState.value = {}
}

function start() {
  resetState()
  if (!references.value.length) return
  if (mode.value === 'full') void loadFull()
  const ref = active.value
  if (ref?.materialId) void ensureMaterial(ref.materialId)
}

async function loadFull() {
  if (fullLoading.value) return
  const run = generation
  fullLoading.value = true
  fullError.value = ''
  try {
    if (!props.sha) throw new Error('固定版本缺少 SHA，无法读取全文')
    const snapshot = await repositoryApi.file(props.projectId, props.sha, props.path)
    if (run !== generation) return
    fullText.value = snapshot.content || ''
    fullLoaded.value = true
  } catch (failure) {
    if (run !== generation) return
    fullError.value = (failure as Error).message || '固定版本全文读取失败'
  } finally {
    if (run === generation) {
      fullLoading.value = false
      if (!fullError.value) await scrollIfReady(true)
    }
  }
}

async function ensureMaterial(id?: string) {
  if (!id || savedById.value[id] || materialState.value[id] === 'loading') return
  const run = generation
  materialState.value = { ...materialState.value, [id]: 'loading' }
  try {
    const detail = isFrozen.value
      ? props.frozenMaterials?.find((material) => material.metadata.id === id)
      : await tasksApi.material(props.taskId, id)
    if (run !== generation) return
    if (!detail || (isFrozen.value && !detail.complete)) throw new Error('冻结材料正文不可用')
    savedById.value = { ...savedById.value, [id]: detail }
    materialState.value = { ...materialState.value, [id]: 'done' }
    if (mode.value === 'saved' && active.value?.materialId === id) await scrollSaved(true)
  } catch {
    if (run !== generation) return
    materialState.value = { ...materialState.value, [id]: 'error' }
  }
}

async function retry() {
  if (mode.value === 'full') {
    fullError.value = ''
    fullLoaded.value = false
    await loadFull()
    return
  }
  const id = active.value?.materialId
  if (!id) return
  const next = { ...materialState.value }
  delete next[id]
  materialState.value = next
  await ensureMaterial(id)
  await scrollSaved(true)
}

async function scrollIfReady(instant: boolean) {
  if (!props.open || busy.value || loadError.value || !active.value || !activeInView.value) return
  if (mode.value === 'full' && !fullText.value) return
  if (mode.value === 'saved' && !activeMaterial.value) return
  await nextTick()
  viewer.value?.scrollToLine(active.value.startLine, scroller.value, instant)
}

/**
 * 片段模式下的定位：当前引用在片段内就滚到它；不在片段内则滚到片段开头，
 * 让用户先看到实际发送的内容，而不是停在上一个全文视口被截断的位置。
 */
async function scrollSaved(instant: boolean) {
  if (!props.open || mode.value !== 'saved' || busy.value || loadError.value || !savedView.value) return
  await nextTick()
  const line = active.value && activeInView.value ? active.value.startLine : savedView.value.start
  viewer.value?.scrollToLine(line, scroller.value, instant)
}

function go(delta: number) {
  if (navDisabled.value) return
  const next = activeIndex.value + delta
  if (next < 0 || next >= references.value.length) return
  const target = references.value[next]
  if (mode.value === 'saved' && !inSaved(target)) {
    mode.value = 'full'
    message.info('该引用不在发送片段内，已切换到固定版本全文')
  }
  activeIndex.value = next
}

function close() {
  emit('update:open', false)
}

function onKeydown(event: KeyboardEvent) {
  if (!props.open || !event.altKey) return
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  go(event.key === 'ArrowLeft' ? -1 : 1)
}

watch(() => props.open, (open) => {
  if (open) {
    start()
  } else {
    generation += 1
    fullLoading.value = false
  }
}, { immediate: true })

watch(
  () => [props.taskId, props.projectId, props.path, props.sha, props.side, props.activeStart, props.activeEnd, props.initialMode, JSON.stringify(props.references)].join('|'),
  () => {
    if (props.open) start()
  }
)

watch(() => props.frozenMaterials, () => {
  if (props.open) start()
})

watch(mode, (next) => {
  if (!props.open) return
  if (next === 'full') {
    if (!fullLoaded.value) void loadFull()
    else void scrollIfReady(true)
  } else if (active.value?.materialId) {
    void ensureMaterial(active.value.materialId).then(() => scrollSaved(true))
  }
})

watch(activeIndex, () => {
  if (!props.open) return
  const ref = active.value
  if (ref?.materialId) void ensureMaterial(ref.materialId)
  if (mode.value === 'full') void scrollIfReady(false)
  else void scrollSaved(false)
})

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  generation += 1
  fullLoading.value = false
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style lang="less">
/* 弹窗壳是全屏 teleport 出来的，不受 scoped 约束，单独一段全局样式。 */
.code-reference-modal .ant-modal {
  max-width: calc(100vw - 40px);
  padding-bottom: 0;
}

.code-reference-modal .ant-modal .ant-modal-content {
  display: flex;
  flex-direction: column;
  height: min(90vh, 840px);
  padding: 0;
}

.code-reference-modal .ant-modal .ant-modal-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}
</style>

<style scoped lang="less">
.reader {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  background: #fff;
}

.reader-head {
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 22px 14px;
  border-bottom: 1px solid var(--line);
}

.head-main {
  flex: 1;
  min-width: 0;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.title-line h2 {
  margin: 0;
  color: var(--ink);
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.3px;
}

.pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: #eef2f7;
  color: #5d6c80;
  font-size: 11.5px;
  font-weight: 650;
  line-height: 1.7;
  white-space: nowrap;
}

.pill.blue {
  background: var(--soft);
  color: var(--blue);
}

.sub-line {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  margin-top: 3px;
}

.path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--muted);
  font: 12.5px/1.6 var(--font-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  display: flex;
  align-items: center;
  flex: none;
  gap: 7px;
  color: var(--faint);
  font-size: 11.5px;
  white-space: nowrap;
}

.meta code {
  padding: 0 5px;
  font-size: 11.5px;
}

.icon-btn {
  display: grid;
  flex: none;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--control-border);
  border-radius: 10px;
  background: #fff;
  color: var(--muted);
  font-size: 20px;
  line-height: 1;
  box-shadow: var(--shadow-control);
  cursor: pointer;
}

.icon-btn:hover {
  background: var(--bg);
  color: var(--ink);
}

.toolbar {
  display: flex;
  flex: none;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  padding: 10px 22px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}

.source-note {
  color: var(--faint);
  font-size: 11.5px;
  white-space: nowrap;
}

.scope-text {
  color: var(--muted);
  font-size: 12.5px;
  white-space: nowrap;
}

.scope-text b {
  color: var(--ink);
  font-weight: 650;
}

.nav {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.counter {
  min-width: 58px;
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.banner {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  margin: 10px 22px 0;
  padding: 9px 12px;
  border: 1px solid var(--notice-border);
  border-radius: 12px;
  background: var(--notice);
  color: var(--notice-ink);
  font-size: 12.5px;
  line-height: 1.6;
}

.code-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  background: #fff;
}

.code-wrap:focus-visible {
  outline: 2px solid var(--focus-border);
  outline-offset: -2px;
}

.state {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px;
  background: #fff;
  color: var(--muted);
  font-size: 12.5px;
  text-align: center;
}

.state strong {
  color: var(--ink);
  font-size: 14px;
}

.state p {
  max-width: 460px;
  margin: 0;
  line-height: 1.7;
}

.state-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--red-soft);
  color: var(--red);
  font-size: 20px;
  font-weight: 700;
}

.state-actions {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}

.reader-foot {
  display: flex;
  flex: none;
  align-items: center;
  gap: 16px;
  padding: 11px 22px;
  border-top: 1px solid var(--line);
  background: #fbfcfe;
  color: var(--muted);
  font-size: 11.5px;
}

.legend {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.swatch {
  display: inline-block;
  width: 11px;
  height: 11px;
  border: 1px solid transparent;
  border-radius: 3px;
}

.swatch.active {
  background: #dce9ff;
  border-color: #93b4f5;
}

.swatch.other {
  margin-left: 8px;
  background: #eef4ff;
  border-color: #c7d8f6;
}

.hint {
  color: var(--faint);
  white-space: nowrap;
}

.kbd-hint kbd {
  display: inline-block;
  min-width: 18px;
  padding: 0 4px;
  border: 1px solid var(--control-border);
  border-bottom-width: 2px;
  border-radius: 5px;
  background: #fff;
  color: #64748b;
  font: 11px/16px var(--font-mono);
  text-align: center;
}

.spacer {
  flex: 1;
}

@media (max-width: 1200px) {
  .source-note,
  .kbd-hint {
    display: none;
  }
}
</style>
