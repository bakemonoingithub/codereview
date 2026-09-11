import { describe, expect, it } from 'vitest'
import {
  changeTypeText,
  changeTypeTip,
  severityText,
  severityTip,
  unitKindText,
  unitKindTip
} from '../enums'

/**
 * 英文枚举的中文释义（C4）。
 *
 * 口径：界面保留英文枚举 + 中文 tooltip；认不出的值原样返回，不猜。
 */
describe('问题级别释义', () => {
  it('同时认 LLM 与 SonarQube 两套级别 —— 少认一套会让一半记录没有释义', () => {
    // LLM 侧（见 LlmReviewAnalyzer 的提示词）
    expect(severityText('MAJOR')).toBeTruthy()
    expect(severityText('MINOR')).toBeTruthy()
    expect(severityText('INFO')).toBeTruthy()
    // SonarQube 侧（见 ApiReviewAnalyzer 取回的 issues）
    expect(severityText('BLOCKER')).toBeTruthy()
    expect(severityText('CRITICAL')).toBeTruthy()
  })

  it('大小写不敏感（后端取值大小写不完全统一）', () => {
    expect(severityText('major')).toBe(severityText('MAJOR'))
    expect(severityText(' Major ')).toBe(severityText('MAJOR'))
  })

  it('tooltip 文案是"原值 · 中文"', () => {
    expect(severityTip('MAJOR')).toBe('MAJOR · 重要')
  })

  it('认不出的级别不硬翻，tooltip 给空串（调用方据此不挂 tooltip）', () => {
    expect(severityText('WEIRD')).toBe('')
    expect(severityTip('WEIRD')).toBe('')
    expect(severityTip(undefined)).toBe('')
    expect(severityTip(null)).toBe('')
  })
})

describe('变更类型释义', () => {
  it('四种取值都有中文', () => {
    expect(changeTypeText('added')).toBe('新增')
    expect(changeTypeText('modified')).toBe('修改')
    expect(changeTypeText('removed')).toBe('删除')
    expect(changeTypeText('renamed')).toBe('重命名')
  })

  it('与 changedFiles 的 STATUS_META 口径一致（同一份语义不该有两套说法）', () => {
    // STATUS_META: added→新增 / modified→修改 / removed→删除 / renamed→重命名
    expect(changeTypeTip('added')).toBe('added · 新增')
  })

  it('认不出的类型原样不翻', () => {
    expect(changeTypeText('copied')).toBe('')
    expect(changeTypeTip('copied')).toBe('')
  })
})

describe('审查单元粒度释义', () => {
  it('覆盖后端实际产出的取值', () => {
    // 取值来自 Chunker / DiffUnitBuilder（diff-method / diff-hunk / full-file / merged 等）
    expect(unitKindText('diff-method')).toContain('方法')
    expect(unitKindText('diff-hunk')).toContain('变更块')
    expect(unitKindText('full-file')).toContain('整个文件')
    expect(unitKindText('merged')).toContain('合并')
  })

  it('认不出的粒度不硬翻', () => {
    expect(unitKindText('unknown-kind')).toBe('')
    expect(unitKindTip('unknown-kind')).toBe('')
  })
})
