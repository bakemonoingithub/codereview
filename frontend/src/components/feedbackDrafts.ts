import type { FeedbackView } from '@/api/v2'

export interface FeedbackDraft {
  assessment: FeedbackView['assessment']
  comment: string
  revision: number
  dirty: boolean
}

const drafts = new Map<string, FeedbackDraft>()

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
