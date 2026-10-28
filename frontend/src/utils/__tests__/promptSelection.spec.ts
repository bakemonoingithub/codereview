import { describe, expect, it } from 'vitest'
import { isPromptApplicable } from '@/utils/promptSelection'

describe('提示词适用范围', () => {
  it.each(['SPECIALIST', 'AGENT', 'REPORT'] as const)('%s 仅接受本用途和通用', scene => {
    for (const purpose of ['SPECIALIST', 'AGENT', 'REPORT', 'COMMON', 'UNKNOWN', '', null, undefined]) {
      expect(isPromptApplicable(purpose, scene)).toBe(purpose === scene || purpose === 'COMMON')
    }
  })
})
