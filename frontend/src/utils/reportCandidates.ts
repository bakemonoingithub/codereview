import type { ReportCandidate } from '@/api/v2'

export function kindText(kind: string) {
  return ({ ISSUE: '问题审查', PATTERN: '设计模式', COUPLING: '耦合度', AGENT: 'Agent' } as Record<string, string>)[kind] || kind
}

export function pathTitle(record: { path: string | null; oldPath?: string | null }) {
  if (!record.path) return 'Agent 调查结果'
  return record.oldPath ? `${record.oldPath} → ${record.path}` : record.path
}

export function shortSha(sha: string | null) {
  return sha ? sha.slice(0, 10) : '—'
}

export function feedbackText(candidate: ReportCandidate) {
  if (!candidate.feedback?.assessment) {
    return candidate.feedback?.comment ? '未判定（有评论）' : '未反馈'
  }
  return ({ ACCURATE: '准确', INACCURATE: '不准确', UNKNOWN: '无法判断' } as Record<string, string>)[candidate.feedback.assessment]
    || candidate.feedback.assessment
}

export function resultTime(value: string) {
  return value ? value.replace('T', ' ').replace('Z', '').slice(0, 16) : '时间未知'
}
