<template>
  <a-modal
    :open="open"
    title="材料与固定版本"
    width="min(1000px, calc(100vw - 48px))"
    :footer="null"
    destroy-on-close
    @cancel="close"
  >
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <template v-else-if="detail">
        <a-descriptions :column="2" size="small" bordered class="mb12">
          <a-descriptions-item label="路径" :span="2">{{ metadata.path }}</a-descriptions-item>
          <a-descriptions-item label="版本">
            <code>{{ metadata.sha?.slice(0, 12) }}</code>
          </a-descriptions-item>
          <a-descriptions-item label="侧">{{ sideText(metadata.side) }}</a-descriptions-item>
          <a-descriptions-item label="行号">{{ metadata.startLine }}-{{ metadata.endLine }}</a-descriptions-item>
          <a-descriptions-item label="角色">{{ metadata.role }}</a-descriptions-item>
          <a-descriptions-item label="完整性">{{ metadata.completeness }}</a-descriptions-item>
          <a-descriptions-item label="发送">
            {{ metadata.sent ? '实际发送' : '读取后未发送' }}
          </a-descriptions-item>
        </a-descriptions>
        <a-alert v-if="!detail.complete" type="warning" show-icon message="保存的材料正文不可用，不能将空白内容视为完整原文。" />
        <p v-if="metadata.diagnostic" class="notice">{{ metadata.diagnostic }}</p>
        <a-alert
          v-if="focusRange"
          type="info"
          show-icon
          :message="`${focusLabel || '当前引用'}：第 ${focusRange.startLine}–${focusRange.endLine} 行`"
          class="mb12"
        />

        <div class="tabs">
          <a-button size="small" :type="mode === 'saved' ? 'primary' : 'default'" @click="showSaved">
            保存片段
          </a-button>
          <a-button size="small" :type="mode === 'full' ? 'primary' : 'default'" @click="loadFull">
            固定版本全文
          </a-button>
          <a-button
            v-if="metadata.side !== 'FILE'"
            size="small"
            :type="mode === 'diff' ? 'primary' : 'default'"
            @click="loadDiff"
          >
            提交差异
          </a-button>
          <span class="spacer" />
          <span class="muted small">扩展全文不会改变实际发送内容</span>
        </div>

        <a-spin :spinning="contentLoading">
          <a-alert v-if="contentError" type="error" show-icon :message="contentError" />
          <DiffViewer
            v-else-if="mode === 'diff'"
            :path="metadata.path"
            :patch="diff?.patch || null"
            :status="diff?.status"
            highlight
            empty-text="宿主未提供可显示的差异，请阅读固定版本原文"
          />
          <CodeViewer
            v-else
            :text="mode === 'saved' ? (detail.content || '') : content"
            :path="metadata.path"
            :start-line="mode === 'saved' ? (metadata.startLine || 1) : 1"
            :ranges="focusRanges"
            :active-range="focusRange"
          />
        </a-spin>
      </template>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import CodeViewer from '@/components/CodeViewer.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import {
  repositoryApi,
  tasksApi,
  type DiffSides,
  type MaterialDetail,
  type MaterialView
} from '@/api/v2'

const props = defineProps<{
  open: boolean
  taskId: string
  projectId: string
  headSha: string
  materialId: string
  focusStart?: number | null
  focusEnd?: number | null
  focusLabel?: string
}>()
const emit = defineEmits<{ (event: 'update:open', value: boolean): void }>()

let loadEpoch = 0
let contentEpoch = 0
const loading = ref(false)
const contentLoading = ref(false)
const error = ref('')
const contentError = ref('')
const detail = ref<MaterialDetail | null>(null)
const content = ref('')
const diff = ref<DiffSides | null>(null)
const mode = ref<'saved' | 'full' | 'diff'>('saved')

const emptyMaterial: MaterialView = {
  id: '',
  path: '',
  sha: '',
  side: 'FILE',
  startLine: 1,
  endLine: 1,
  role: '',
  disposition: '',
  completeness: '',
  diagnostic: null,
  contentHash: '',
  byteSize: 0,
  sent: false
}
const metadata = computed<MaterialView>(() => detail.value?.metadata || emptyMaterial)
const focusRange = computed(() => {
  const startLine = props.focusStart
  const endLine = props.focusEnd
  return typeof startLine === 'number' && typeof endLine === 'number'
    ? { startLine, endLine }
    : null
})
const focusRanges = computed(() => focusRange.value ? [focusRange.value] : [])

watch(
  () => [props.open, props.taskId, props.projectId, props.materialId, props.headSha,
    props.focusStart, props.focusEnd, props.focusLabel] as const,
  () => {
    loadEpoch++
    contentEpoch++
    detail.value = null
    content.value = ''
    diff.value = null
    contentError.value = ''
    contentLoading.value = false
    if (props.open && props.materialId) void load()
  },
  { immediate: true, flush: 'sync' }
)
onBeforeUnmount(() => { loadEpoch++; contentEpoch++ })

async function load() {
  const epoch = ++loadEpoch
  loading.value = true
  error.value = ''
  mode.value = 'saved'
  try {
    const loaded = await tasksApi.material(props.taskId, props.materialId)
    if (epoch !== loadEpoch) return
    detail.value = loaded
  } catch (failure) {
    if (epoch === loadEpoch) error.value = (failure as Error).message || '材料读取失败'
  } finally {
    if (epoch === loadEpoch) loading.value = false
  }
}

function showSaved() {
  contentEpoch++
  mode.value = 'saved'
  contentError.value = ''
  contentLoading.value = false
}

async function loadFull() {
  if (!detail.value) return
  const epoch = ++contentEpoch
  const material = detail.value.metadata
  mode.value = 'full'
  content.value = ''
  contentError.value = ''
  contentLoading.value = true
  try {
    const snapshot = await repositoryApi.file(props.projectId, material.sha, material.path)
    if (epoch === contentEpoch) content.value = snapshot.content
  } catch (failure) {
    if (epoch === contentEpoch) contentError.value = (failure as Error).message || '固定版本原文读取失败'
  } finally {
    if (epoch === contentEpoch) contentLoading.value = false
  }
}

async function loadDiff() {
  if (!detail.value) return
  const epoch = ++contentEpoch
  mode.value = 'diff'
  diff.value = null
  contentError.value = ''
  contentLoading.value = true
  try {
    const loaded = await repositoryApi.diff(props.projectId, props.headSha, metadata.value.path)
    if (epoch === contentEpoch) diff.value = loaded
  } catch (failure) {
    if (epoch === contentEpoch) contentError.value = (failure as Error).message || '提交差异读取失败'
  } finally {
    if (epoch === contentEpoch) contentLoading.value = false
  }
}

function sideText(side: string): string {
  return ({ FILE: '文件', BEFORE: '修改前', AFTER: '修改后' } as Record<string, string>)[side] || side
}

function close() {
  loadEpoch++
  contentEpoch++
  emit('update:open', false)
}
</script>

<style scoped lang="less">
.mb12 {
  margin-bottom: 12px;
}
.muted {
  color: var(--muted);
}
.small {
  font-size: 12px;
}
.notice {
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 13px 16px;
  border-radius: 14px;
  color: var(--muted);
  font-size: 12px;
}
.tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.code {
  max-height: 55vh;
  overflow: auto;
  margin: 0;
  padding: 12px;
  background: #f6f8fc;
  border: 1px solid var(--line);
  border-radius: 14px;
  font: 12px/1.95 Consolas, 'Cascadia Code', monospace;
  white-space: pre;
}
</style>
