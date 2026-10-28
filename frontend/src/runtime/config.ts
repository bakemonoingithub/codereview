export interface RuntimeConfig {
  version: 1
  title: string
  apiBase: string
  requestTimeoutMillis: number
  modelValidationTimeoutMillis?: number
  agentValidationTimeoutMillis?: number
  agentPollMillis: number
  reportPollMillis: number
  specialistPollMillis: number
}

export const testDefaults: RuntimeConfig = {
  version: 1, title: '智能代码分析工具', apiBase: 'api/v2', requestTimeoutMillis: 30000,
  modelValidationTimeoutMillis: 180000, agentValidationTimeoutMillis: 300000,
  agentPollMillis: 2000, reportPollMillis: 2000, specialistPollMillis: 3000
}
let config: Readonly<RuntimeConfig> | undefined = import.meta.env.MODE === 'test' ? Object.freeze({ ...testDefaults }) : undefined

export function parseRuntimeConfig(value: unknown): Readonly<RuntimeConfig> {
  if (!value || typeof value !== 'object') throw new Error('应用配置格式无效')
  const data = value as Record<string, unknown>
  if (data.version !== 1 || typeof data.title !== 'string' || !data.title.trim() || data.title.length > 100
    || typeof data.apiBase !== 'string' || !/^\/?[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/.test(data.apiBase)) {
    throw new Error('应用配置版本或字段无效')
  }
  const numbers = ['requestTimeoutMillis', 'agentPollMillis', 'reportPollMillis', 'specialistPollMillis'] as const
  for (const key of numbers) {
    if (!Number.isSafeInteger(data[key]) || (data[key] as number) <= 0 || (data[key] as number) > 2147483647) throw new Error('应用配置时间范围无效')
  }
  for (const key of ['modelValidationTimeoutMillis', 'agentValidationTimeoutMillis'] as const) {
    if (data[key] !== undefined && (!Number.isSafeInteger(data[key]) || (data[key] as number) <= 0
      || (data[key] as number) > 2147483647)) throw new Error('应用配置时间范围无效')
  }
  return Object.freeze({ version: 1, title: data.title, apiBase: data.apiBase,
    requestTimeoutMillis: data.requestTimeoutMillis as number, agentPollMillis: data.agentPollMillis as number,
    modelValidationTimeoutMillis: (data.modelValidationTimeoutMillis ?? data.requestTimeoutMillis) as number,
    agentValidationTimeoutMillis: (data.agentValidationTimeoutMillis ?? data.requestTimeoutMillis) as number,
    reportPollMillis: data.reportPollMillis as number, specialistPollMillis: data.specialistPollMillis as number })
}

export async function loadRuntimeConfig(fetcher: typeof fetch = fetch): Promise<Readonly<RuntimeConfig>> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetcher(new URL('api/v2/runtime-config', document.baseURI), {
      cache: 'no-store', credentials: 'same-origin', signal: controller.signal
    })
    if (!response.ok) throw new Error('无法读取应用配置')
    config = parseRuntimeConfig(await response.json())
    return config
  } finally { clearTimeout(timer) }
}

export function runtimeConfig(): Readonly<RuntimeConfig> {
  if (!config) throw new Error('应用配置尚未加载')
  return config
}

export function businessApiBase(): string {
  return new URL(runtimeConfig().apiBase, document.baseURI).pathname.replace(/\/$/, '')
}
