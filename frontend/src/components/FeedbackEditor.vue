<template>
  <section class="feedback">
    <div class="feedback-head">
      <strong class="small">准确度反馈</strong>
      <span class="muted small">作用于{{ subjectText }}</span>
      <span class="spacer" />
      <span class="muted small">{{ statusText }}</span>
    </div>
    <div class="choices">
      <a-button v-for="choice in choices" :key="choice.value" size="small"
        :type="assessment === choice.value ? 'primary' : 'default'" :disabled="loading || saving"
        @click="setAssessment(choice.value)">{{ choice.label }}</a-button>
      <a-button size="small" type="link" :disabled="assessment === null || loading || saving"
        @click="setAssessment(null)">清除评价</a-button>
    </div>
    <a-textarea v-model:value="comment" :maxlength="16000" :rows="3" :disabled="loading || saving"
      placeholder="可选评论，可不选择评价而单独保存" />
    <div class="feedback-foot">
      <span class="muted small">评论与评价分别保留；切换问题或文件时保留页面内输入。</span>
      <span class="spacer" />
      <a-button size="small" type="primary" :loading="saving" :disabled="loading || !dirty" @click="save">保存反馈</a-button>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="error" class="feedback-alert" />
    <div v-if="latest" class="conflict">
      <a-alert type="warning" show-icon message="其他页面已更新反馈。当前输入已保留，请核对最新内容并手动合并。" />
      <p class="small">最新评价：{{ assessmentText(latest.assessment) }} · 最新评论：{{ latest.comment || '无评论' }}</p>
      <a-button size="small" @click="adoptLatestRevision">已核对，使用此版本号保存我的合并内容</a-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { feedbackApi, type FeedbackView } from '@/api/v2'
import { feedbackDraftKey, readFeedbackDraft, storeFeedbackDraft } from './feedbackDrafts'

type Assessment = FeedbackView['assessment']

const props = defineProps<{ taskId: string; subjectKind: FeedbackView['subjectKind']; subjectId: string; subjectText: string }>()
const emit = defineEmits<{ (event: 'saved', value: FeedbackView): void }>()
const choices: { value: Exclude<Assessment, null>; label: string }[] = [
  { value: 'ACCURATE', label: '准确' }, { value: 'INACCURATE', label: '不准确' }, { value: 'UNKNOWN', label: '无法判断' }
]
const key = computed(() => feedbackDraftKey(props.taskId, props.subjectKind, props.subjectId))
const assessment = ref<Assessment>(null)
const comment = ref('')
const revision = ref(0)
/** 最近一次从服务端读到或成功保存的值；与当前输入比较得出"未保存"，避免初始化被误标为脏。 */
const baseline = ref<{ assessment: Assessment; comment: string }>({ assessment: null, comment: '' })
const dirty = computed(() =>
  assessment.value !== baseline.value.assessment || comment.value !== baseline.value.comment)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const latest = ref<FeedbackView | null>(null)
const updatedAt = ref<string | null>(null)
let loadEpoch = 0
let saveEpoch = 0
const statusText = computed(() => dirty.value ? '尚未保存' : revision.value ? `已保存 · ${timeText(updatedAt.value)}` : '尚未反馈')

function snapshot() { storeFeedbackDraft(key.value, { assessment: assessment.value, comment: comment.value, revision: revision.value, dirty: dirty.value }) }
function setAssessment(value: Assessment) { assessment.value = value; error.value = ''; snapshot() }
watch([assessment, comment], () => { if (!loading.value) { error.value = ''; snapshot() } })
watch(() => [props.taskId, props.subjectKind, props.subjectId], load)
onMounted(load)

async function load() {
  const epoch = ++loadEpoch
  saveEpoch++
  loading.value = true; saving.value = false; error.value = ''; latest.value = null
  // 先清空再取数：新主体读取失败时不能把上一个主体的评价或评论留在表单里。
  assessment.value = null; comment.value = ''; revision.value = 0; updatedAt.value = null
  baseline.value = { assessment: null, comment: '' }
  try {
    const server = await feedbackApi.get(props.subjectKind, props.subjectId)
    if (epoch !== loadEpoch) return
    const draft = readFeedbackDraft(key.value)
    revision.value = server.revision
    updatedAt.value = server.updatedAt
    baseline.value = { assessment: server.assessment, comment: server.comment || '' }
    if (draft?.dirty) {
      assessment.value = draft.assessment; comment.value = draft.comment
      revision.value = draft.revision
    } else {
      assessment.value = server.assessment; comment.value = server.comment || ''
      snapshot()
    }
  } catch (e) {
    if (epoch === loadEpoch) {
      const draft = readFeedbackDraft(key.value)
      if (draft?.dirty) {
        assessment.value = draft.assessment; comment.value = draft.comment
        revision.value = draft.revision
      }
      error.value = e instanceof Error ? e.message : '读取反馈失败'
    }
  }
  finally { if (epoch === loadEpoch) loading.value = false }
}

async function save() {
  const epoch = ++saveEpoch
  const requestKey = key.value
  const subjectKind = props.subjectKind
  const subjectId = props.subjectId
  const submitted = { assessment: assessment.value, comment: comment.value }
  saving.value = true; error.value = ''; latest.value = null
  try {
    const saved = await feedbackApi.save(subjectKind, subjectId, {
      assessment: submitted.assessment, comment: submitted.comment, expectedRevision: revision.value
    })
    if (epoch !== saveEpoch || requestKey !== key.value) return
    revision.value = saved.revision
    updatedAt.value = saved.updatedAt
    baseline.value = { assessment: saved.assessment, comment: saved.comment || '' }
    if (assessment.value === submitted.assessment && comment.value === submitted.comment) {
      assessment.value = saved.assessment; comment.value = saved.comment || ''
    }
    // 保存在途时又改了表单：baseline 已更新，dirty 会因新输入与保存值不同而保持为真。
    snapshot()
    emit('saved', saved)
  } catch (e) {
    if (epoch !== saveEpoch || requestKey !== key.value) return
    const failure = e as Error & { code?: string; details?: { current?: FeedbackView } }
    if (failure.code === 'REVISION_CONFLICT' && failure.details?.current) latest.value = failure.details.current
    else error.value = failure.message || '保存失败，输入已保留，请重试'
    snapshot()
  } finally { if (epoch === saveEpoch) saving.value = false }
}

function adoptLatestRevision() {
  if (!latest.value) return
  revision.value = latest.value.revision; latest.value = null; snapshot()
}
function assessmentText(value: Assessment): string {
  return ({ ACCURATE: '准确', INACCURATE: '不准确', UNKNOWN: '无法判断' } as Record<string, string>)[value || ''] || '未评价'
}
function timeText(value: string | null): string {
  return value ? value.replace('T', ' ').slice(5, 16) : '刚刚'
}
</script>

<style scoped lang="less">
.feedback { margin-top: 16px; padding: 13px 0 2px; border-top: 1px dashed #d3dce8; }
.feedback-head, .feedback-foot, .choices { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.feedback-head { margin-bottom: 10px; }
.choices { margin-bottom: 9px; }
.feedback-foot { margin-top: 8px; }
.spacer { flex: 1; }
.small { font-size: 12px; }
.muted { color: #69798d; }
.feedback-alert, .conflict { margin-top: 10px; }
.conflict p { margin: 8px 0; white-space: pre-wrap; }
</style>
