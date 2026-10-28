import { promptsApi } from '@/api/v2'

export type PromptScene = 'SPECIALIST' | 'AGENT' | 'REPORT'

/** 通用规则只适用于三个业务入口，不改变实际调用协议。 */
export function isPromptApplicable(purpose: string | null | undefined, scene: PromptScene) {
  return ['SPECIALIST', 'AGENT', 'REPORT'].includes(scene) && (purpose === 'COMMON' || purpose === scene)
}

export const unavailablePromptVersion = '所选提示词版本不可用，请重新选择提示词或版本。'

/** 指定版本失效时不回退；表单可保留版本列表，供用户重新选择。 */
export async function readPromptSelection(id: string, versionId?: string | null, allowUnavailable = false) {
  const [prompt, versions] = await Promise.all([promptsApi.detail(id), promptsApi.versions(id)])
  const selectedId = versionId ?? prompt.currentVersion?.id
  const version = versions.find(item => item.id === selectedId && item.content?.trim()) || null
  if (!version && !allowUnavailable) throw new Error(unavailablePromptVersion)
  return { prompt, versions, version }
}
