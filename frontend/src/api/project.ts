import request from '@/utils/request'
import type { ChangedFile } from '@/utils/changedFiles'
import type { AccuracyStat } from '@/utils/accuracy'

export interface ProjectItem {
  id: string
  name: string
  giteaUrl: string
  currentBranch: string
  createdAt: string
}

export interface TreeNode {
  path: string
  name: string
  type: string
  children?: TreeNode[]
}

export function listProjects(params: { pageNum?: number; pageSize?: number } = {}) {
  return request.get('/projects', { params }) as Promise<any>
}

export function createProject(data: {
  name: string
  giteaUrl: string
  credential?: string
  credentialType?: number
}) {
  return request.post('/projects', data) as Promise<any>
}

export function getTree(id: string, branch: string) {
  return request.get(`/projects/${id}/tree`, { params: { branch } }) as Promise<TreeNode[]>
}

export function getBranches(id: string) {
  return request.get(`/projects/${id}/branches`) as Promise<string[]>
}

export interface CommitInfo {
  sha: string
  message: string
  author: string
  date: string
}

export function listCommits(id: string, branch: string) {
  return request.get(`/projects/${id}/commits`, { params: { branch } }) as Promise<CommitInfo[]>
}

export function listChangedFiles(id: string, base: string, head: string) {
  return request.get(`/projects/${id}/changed-files`, { params: { base, head } }) as Promise<string[]>
}

// ---------------------------------------------------------------------------
// 提交视图（diff 审查）
// ---------------------------------------------------------------------------

export interface CommitPage {
  commits: CommitInfo[]
  /** 是否还有下一页（后端刻意不返回 total，避免伪造不可信的数字） */
  hasMore: boolean
}

/** 单提交详情（列表视图，后端已剥离每文件 patch） */
export interface CommitDetail {
  sha: string
  parents: string[]
  message?: string
  author?: string
  date?: string
  additions?: number
  deletions?: number
  totalChanges?: number
  /** 宿主提示还有更多文件未返回（GitHub 用 Link 头告知） */
  truncated: boolean
  files: ChangedFile[]
}

export function listCommitPage(id: string, branch: string, page = 1, pageSize = 50) {
  return request.get(`/projects/${id}/commits/page`, { params: { branch, page, pageSize } }) as Promise<CommitPage>
}

export function getCommitDetail(id: string, sha: string) {
  return request.get(`/projects/${id}/commits/${sha}`) as Promise<CommitDetail>
}

/** 单个变更文件的 patch；无 patch（二进制/过大）时返回 null */
export function getFilePatch(id: string, sha: string, path: string) {
  return request.get(`/projects/${id}/commits/${sha}/patch`, { params: { path } }) as Promise<string | null>
}

/** 项目维度的准确率统计（服务验收指标 5） */
export function getAccuracy(id: string) {
  return request.get(`/projects/${id}/accuracy`) as Promise<AccuracyStat[]>
}
