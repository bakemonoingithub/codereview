import axios, { type InternalAxiosRequestConfig } from 'axios'
import { afterAll, beforeEach, describe, expect, it } from 'vitest'

const originalAdapter = axios.defaults.adapter
let payload: unknown
let sent: InternalAxiosRequestConfig
let failure: unknown
axios.defaults.adapter = async (config) => {
  sent = config
  if (failure) throw failure
  return { data: payload, status: 200, statusText: 'OK', headers: {}, config }
}
const { projectsApi, modelsApi, promptsApi, strategiesApi } = await import('@/api/v2')
afterAll(() => { axios.defaults.adapter = originalAdapter })
beforeEach(() => { failure = undefined })

describe('v2 客户端的真实响应链', () => {
  it.each([
    ['项目', projectsApi.list], ['模型', modelsApi.list],
    ['提示词', promptsApi.list], ['策略', strategiesApi.list]
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

  it('策略筛选使用服务端的 type 参数并保留其他查询条件', async () => {
    payload = { items: [], nextCursor: null, hasMore: false }
    await strategiesApi.list({ specialistType: 'PATTERN', q: '规则', cursor: 'next', limit: 20 })
    expect(sent.params).toEqual({ type: 'PATTERN', q: '规则', cursor: 'next', limit: 20 })
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
