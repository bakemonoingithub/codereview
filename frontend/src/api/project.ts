import request from '@/utils/request'

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
