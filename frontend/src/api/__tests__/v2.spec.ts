import axios, { type InternalAxiosRequestConfig } from 'axios'
import { afterAll, beforeEach, describe, expect, it } from 'vitest'
import { loadRuntimeConfig, testDefaults } from '@/runtime/config'

const originalAdapter = axios.defaults.adapter
let payload: unknown
let sent: InternalAxiosRequestConfig
let failure: unknown
axios.defaults.adapter = async (config) => {
  sent = config
  if (failure) throw failure
  return { data: payload, status: 200, statusText: 'OK', headers: {}, config }
}
const { projectsApi, modelsApi, promptsApi, tasksApi, reportsApi, repositoryApi, modelCallsApi, agentTasksApi } = await import('@/api/v2')
afterAll(() => { axios.defaults.adapter = originalAdapter })
beforeEach(() => { failure = undefined })

describe('v2 客户端的真实响应链', () => {
  it('模型验证独立使用运行配置的长超时，普通业务请求保持原超时', async () => {
    const load = (data: unknown) => loadRuntimeConfig(async () => new Response(JSON.stringify(data)))
    await load({ ...testDefaults, modelValidationTimeoutMillis: 900000, agentValidationTimeoutMillis: 2700000 })
    try {
      payload = { result: 'PASSED' }
      await modelsApi.validate('m1')
      expect(sent.timeout).toBe(900000)
      await modelsApi.validateAgent('m1')
      expect(sent.timeout).toBe(2700000)
      payload = { items: [] }
      await projectsApi.list()
      expect(sent.timeout).toBe(testDefaults.requestTimeoutMillis)
    } finally { await load(testDefaults) }
  })
  it('Agent 进度把后端 Long 字符串转换为用量数字，同时保留任务 ID', async () => {
    payload = {
      taskId: '01M5KP4AB5BP9WN66DBQSFFBAW', state: 'SUCCEEDED', turnNo: 29,
      elapsedSeconds: '164',
      outputUsage: { totalOutputTokens: '19537', peakOutputTokens: '9962', reportedCalls: 29, unreportedCalls: 0 }
    }
    const progress = await agentTasksApi.progress('01M5KP4AB5BP9WN66DBQSFFBAW')
    expect(sent.url).toBe('/agent-tasks/01M5KP4AB5BP9WN66DBQSFFBAW/progress')
    expect(progress).toMatchObject({
      taskId: '01M5KP4AB5BP9WN66DBQSFFBAW', elapsedSeconds: 164,
      outputUsage: { totalOutputTokens: 19537, peakOutputTokens: 9962, reportedCalls: 29, unreportedCalls: 0 }
    })
  })

  it.each([0, '0'])('Agent 进度保留实际零用量：%s', async (zero) => {
    payload = { elapsedSeconds: zero, outputUsage: {
      totalOutputTokens: zero, peakOutputTokens: zero, reportedCalls: 0, unreportedCalls: 0
    } }
    expect(await agentTasksApi.progress('t1')).toMatchObject({
      elapsedSeconds: 0, outputUsage: { totalOutputTokens: 0, peakOutputTokens: 0 }
    })
  })

  it('Agent 缺失或非法用量保持未知，不转换成零或丢失精度的数字', async () => {
    payload = { elapsedSeconds: null, outputUsage: {
      totalOutputTokens: '', peakOutputTokens: '9007199254740993', reportedCalls: 0, unreportedCalls: 2
    } }
    expect(await agentTasksApi.progress('t1')).toMatchObject({
      elapsedSeconds: null, outputUsage: { totalOutputTokens: null, peakOutputTokens: null }
    })
  })

  it('报告调用读取完整详情端点，保留请求、返回和调用元数据', async () => {
    payload = { id: 'c1', state: 'SUCCEEDED', purpose: 'REPORT', attempt: 1,
      requestBody: '{"messages":[]}', responseBody: '{"choices":[]}', usageJson: '{"total_tokens":12}' }
    expect(await modelCallsApi.detail('c1')).toEqual(payload)
    expect(sent.url).toBe('/model-calls/c1/full')
    expect(sent.params).toBeUndefined()
  })
  it.each([
    ['项目', projectsApi.list], ['模型', modelsApi.list], ['提示词', promptsApi.list],
    ['审查中心', tasksApi.list], ['报告', reportsApi.list]
  ] as const)('%s传递页码和条数，保留服务端总数', async (_name, list) => {
    payload = { items: [{ id: 'one' }], total: 65, pageNum: 2, pageSize: 10, nextCursor: null, hasMore: true }
    expect(await list({ pageNum: 2, pageSize: 10 })).toEqual(payload)
    expect(sent.params).toMatchObject({ pageNum: 2, pageSize: 10 })
    expect(sent.params.cursor).toBeUndefined()
    expect(sent.params.limit).toBeUndefined()
  })

  it.each([
    ['项目', projectsApi.list], ['模型', modelsApi.list],
    ['提示词', promptsApi.list]
  ] as const)('%s列表保留分页正文', async (_name, list) => {
    payload = { items: [{ id: 'one', name: '配置' }], nextCursor: 'next', hasMore: true }
    expect(await list()).toEqual(payload)
  })

  it('保留提示词详情、版本和对比正文', async () => {
    payload = { id: 'p1', revision: 2, currentVersion: { content: '正文' } }
    expect(await promptsApi.detail('p1')).toEqual(payload)
    payload = [{ id: 'v1', content: '正文' }]
    expect(await promptsApi.versions('p1')).toEqual(payload)
    payload = [{ type: 'add', text: '新行', newLine: 1 }]
    expect(await promptsApi.diff('p1', 'v1', 'v2')).toEqual(payload)
  })

  it('保留写入和模型验证的结果', async () => {
    payload = { id: 'p1', revision: 1 }
    expect(await projectsApi.create({ name: '项目', repoUrl: 'https://example.com/repo' })).toEqual(payload)
    payload = { id: 'p1', revision: 2 }
    expect(await projectsApi.update('p1', { name: '新名称', expectedRevision: 1 })).toEqual(payload)
    payload = { result: 'PASSED', configRevision: 2 }
    expect(await modelsApi.validate('m1')).toEqual(payload)
  })

  it('创建任务把幂等键放在请求头，并保留固定版本参数', async () => {
    payload = { id: 't1', state: 'QUEUED' }
    const body = {
      projectId: 'p1',
      specialistType: 'ISSUE' as const,
      modelConfigId: 'm1',
      promptId: 'rule-1',
      promptVersionId: 'version-2',
      source: { kind: 'FILES' as const, branch: 'main', observedSha: 'a'.repeat(40) },
      targets: [{ path: 'src/A.java', note: '说明' }],
      smartSplit: true
    }
    expect(await tasksApi.create(body, 'idem-1')).toEqual(payload)
    expect(sent.headers?.['Idempotency-Key']).toBe('idem-1')
    expect(JSON.parse(String(sent.data))).toMatchObject(body)
  })

  it('仓库读取走项目下的固定版本接口', async () => {
    payload = { sha: 'a'.repeat(40), entries: [], truncated: false }
    await repositoryApi.paths('p1', 'a'.repeat(40))
    expect(sent.url).toBe('/projects/p1/paths')
    expect(sent.params).toEqual({ sha: 'a'.repeat(40) })

    payload = { sha: 'a'.repeat(40), path: 'src/A.java', content: 'class A {}' }
    await repositoryApi.file('p1', 'a'.repeat(40), 'src/A.java')
    expect(sent.url).toBe('/projects/p1/files')
    expect(sent.params).toEqual({ sha: 'a'.repeat(40), path: 'src/A.java' })
  })

  it('统一详情读取结果、材料和实际调用', async () => {
    payload = { id: 'r1', kind: 'ISSUE', body: { summary: 'x' }, issues: [], provenance: {} }
    expect(await tasksApi.result('t1', 'g1')).toEqual(payload)
    expect(sent.url).toBe('/review-tasks/t1/targets/g1/result')

    payload = [{ id: 'm1', path: 'src/A.java', sent: true }]
    expect(await tasksApi.materials('t1', 'g1')).toEqual(payload)
    expect(sent.url).toBe('/review-tasks/t1/targets/g1/materials')

    payload = { id: 'c1', requestBody: '{}', responseBody: '{}' }
    expect(await tasksApi.call('t1', 'c1')).toEqual(payload)
    expect(sent.url).toBe('/review-tasks/t1/calls/c1')

    payload = ['main', 'release']
    expect(await repositoryApi.branches('p1')).toEqual(payload)
    expect(sent.url).toBe('/projects/p1/branches')
  })

  it('保留结构化错误，便于页面展示与重试', async () => {
    failure = { response: { data: {
      code: 'REVISION_CONFLICT', message: '配置已更新', retryable: false, details: { revision: 3 }
    } } }
    await expect(promptsApi.detail('p1')).rejects.toMatchObject({
      message: '配置已更新', code: 'REVISION_CONFLICT', retryable: false, details: { revision: 3 }
    })
  })
})
