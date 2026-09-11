import { describe, expect, it } from 'vitest'
import { toReadableError } from '../request'

/**
 * 错误文案归一化。
 *
 * 回归背景：各调用方都写 `message.error(e?.message || '中文兜底')`，而 axios 的
 * `e.message` **永远非空**，中文兜底从不生效 —— 用户直接看到
 * `Request failed with status code 500` / `timeout of 30000ms exceeded`。
 */
function axiosError(message: string, opts: { code?: string; status?: number } = {}) {
  return {
    isAxiosError: true,
    message,
    code: opts.code,
    response: opts.status === undefined ? undefined : { status: opts.status }
  } as any
}

describe('错误文案归一化', () => {
  it('不再把 axios 的英文原文抛给用户', () => {
    const text = toReadableError(axiosError('Request failed with status code 500', { status: 500 }))
    expect(text).toBe('服务器内部错误')
    expect(text).not.toContain('Request failed')
  })

  it('常见状态码都有中文', () => {
    expect(toReadableError(axiosError('x', { status: 400 }))).toBe('请求参数有误')
    expect(toReadableError(axiosError('x', { status: 401 }))).toContain('登录状态已失效')
    expect(toReadableError(axiosError('x', { status: 403 }))).toContain('没有权限')
    expect(toReadableError(axiosError('x', { status: 404 }))).toContain('资源不存在')
    expect(toReadableError(axiosError('x', { status: 429 }))).toContain('频繁')
    expect(toReadableError(axiosError('x', { status: 502 }))).toContain('网关')
    expect(toReadableError(axiosError('x', { status: 503 }))).toContain('暂不可用')
    expect(toReadableError(axiosError('x', { status: 504 }))).toContain('超时')
  })

  it('未知状态码兜底成"请求失败（HTTP xxx）"', () => {
    expect(toReadableError(axiosError('x', { status: 418 }))).toBe('请求失败（HTTP 418）')
  })

  it('超时（含 code 与文案两种表现）都提示重试', () => {
    expect(toReadableError(axiosError('timeout of 30000ms exceeded', { code: 'ECONNABORTED' }))).toBe(
      '请求超时，请稍后重试'
    )
    expect(toReadableError(axiosError('timeout of 30000ms exceeded'))).toBe('请求超时，请稍后重试')
  })

  it('没有响应对象 = 连不上后端，给出可操作的提示', () => {
    expect(toReadableError(axiosError('Network Error'))).toContain('无法连接服务器')
  })

  it('非 axios 错误保留原样（比如业务层自己抛的中文）', () => {
    expect(toReadableError(new Error('策略不存在'))).toBe('策略不存在')
    expect(toReadableError('随便一个字符串')).toBe('请求失败')
    expect(toReadableError(null)).toBe('请求失败')
  })
})
