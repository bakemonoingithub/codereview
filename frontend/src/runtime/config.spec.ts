import { describe, it, expect, vi } from 'vitest'
import { businessApiBase, loadRuntimeConfig, parseRuntimeConfig, runtimeConfig, testDefaults } from './config'
describe('runtime configuration', () => {
  it('loads current server values without a build-time environment override', async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ...testDefaults, title: '内网工具', agentPollMillis: 4500, password: 'not public' }) })
    await loadRuntimeConfig(fetcher)
    expect(runtimeConfig().title).toBe('内网工具')
    expect(runtimeConfig().agentPollMillis).toBe(4500)
    expect(runtimeConfig()).not.toHaveProperty('password')
    expect(fetcher.mock.calls[0][1]).toMatchObject({ cache: 'no-store', credentials: 'same-origin' })
  })
  it.each([{ version: 2 }, { apiBase: '//evil.test/api' }, { apiBase: '../api' }, { agentPollMillis: 0 }, { requestTimeoutMillis: Infinity }, { modelValidationTimeoutMillis: 0 }, { agentValidationTimeoutMillis: Infinity }])('rejects invalid values %s', value => {
    expect(() => parseRuntimeConfig({ ...testDefaults, ...value })).toThrow()
  })
  it('accepts independent validation timeouts and falls back for an older server', () => {
    const data = { ...testDefaults, modelValidationTimeoutMillis: 900000, agentValidationTimeoutMillis: 2700000 }
    expect(parseRuntimeConfig(data)).toMatchObject({ requestTimeoutMillis: 30000, modelValidationTimeoutMillis: 900000, agentValidationTimeoutMillis: 2700000 })
    const { modelValidationTimeoutMillis, agentValidationTimeoutMillis, ...older } = data
    expect(parseRuntimeConfig(older)).toMatchObject({ modelValidationTimeoutMillis: 30000, agentValidationTimeoutMillis: 30000 })
  })
  it('rejects unavailable configuration so the bootstrap cannot proceed', async () => {
    await expect(loadRuntimeConfig(vi.fn().mockResolvedValue({ ok: false }))).rejects.toThrow()
  })
  it('resolves bootstrap and business requests under the current same-origin deployment path', async () => {
    const base = document.createElement('base')
    base.href = '/review/'
    document.head.append(base)
    try {
      const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => testDefaults })
      await loadRuntimeConfig(fetcher)
      expect(fetcher.mock.calls[0][0].pathname).toBe('/review/api/v2/runtime-config')
      expect(businessApiBase()).toBe('/review/api/v2')
    } finally { base.remove() }
  })
})
