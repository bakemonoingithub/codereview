import type { FeedbackView } from '@/api/v2'
import { shallowReactive } from 'vue'

export interface FeedbackDraft {
  assessment: FeedbackView['assessment']
  comment: string
  revision: number
  dirty: boolean
  baseline: { assessment: FeedbackView['assessment']; comment: string }
  updatedAt: string | null
}

const drafts = shallowReactive(new Map<string, FeedbackDraft>())
const saves = shallowReactive(new Map<string, number>())

export function feedbackSaveVersion(taskId: string) { return saves.get(taskId) || 0 }
export function recordFeedbackSave(taskId: string) { saves.set(taskId, feedbackSaveVersion(taskId) + 1) }

export function settleFeedbackSave(key: string, saved: FeedbackView) {
  const draft = drafts.get(key)
  // 已确认丢弃的草稿不重建；更高版本的草稿也不被迟到响应回退。
  if (!draft || draft.revision >= saved.revision) return
  const baseline = { assessment: saved.assessment, comment: saved.comment || '' }
  drafts.set(key, { ...draft, revision: saved.revision, updatedAt: saved.updatedAt, baseline,
    dirty: draft.assessment !== baseline.assessment || draft.comment !== baseline.comment })
}

export function feedbackDraftKey(taskId: string, subjectKind: FeedbackView['subjectKind'], subjectId: string) {
  return `${taskId}:${subjectKind}:${subjectId}`
}

export function hasDirtyFeedbackDrafts(taskId: string): boolean {
  return [...drafts.entries()].some(([key, draft]) => key.startsWith(`${taskId}:`) && draft.dirty)
}

export function discardFeedbackDrafts(taskId: string): void {
  for (const key of drafts.keys()) if (key.startsWith(`${taskId}:`)) drafts.delete(key)
}

export function readFeedbackDraft(key: string) { return drafts.get(key) }
export function storeFeedbackDraft(key: string, draft: FeedbackDraft) { drafts.set(key, draft) }
