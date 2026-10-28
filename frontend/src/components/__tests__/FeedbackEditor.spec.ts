// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({ feedbackApi: { get: vi.fn(), save: vi.fn() } }))
import { feedbackApi } from '@/api/v2'
import { discardFeedbackDrafts, feedbackDraftKey, hasDirtyFeedbackDrafts, readFeedbackDraft, feedbackSaveVersion } from '@/components/feedbackDrafts'

let sequence = 0
const nextId = () => `subject-${++sequence}`
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}

function server(subjectId: string, overrides: Record<string, unknown> = {}) {
  return {
    subjectKind: 'ISSUE',
    subjectId,
    assessment: 'ACCURATE',
    comment: '服务端评论',
    revision: 1,
    updatedAt: '2026-10-21T10:00:00',
    ...overrides
  }
}

async function start(subjectId: string) {
  const wrapper = mount(FeedbackEditor, {
    props: { taskId: 'task-1', subjectKind: 'ISSUE', subjectId, subjectText: '此问题' },
    global: { stubs: namedAntStubs }
  })
  await flushPromises()
  return wrapper
}

const textarea = (wrapper: any) => wrapper.find('a-textarea-stub input')
const button = (wrapper: any, text: string) =>
  wrapper.findAll('a-button-stub').find((item: any) => item.text() === text)!

beforeEach(() => {
  vi.resetAllMocks()
  discardFeedbackDrafts('task-1')
  document.body.innerHTML = ''
})

describe('反馈草稿、冲突及迟到响应回归', () => {
  it('逐条结论支持只评论保存、清除评价保留评论及保存中继续编辑', async () => {
    const id = nextId(); const pending = deferred<any>()
    vi.mocked(feedbackApi.get).mockResolvedValue(server(id, { subjectKind: 'PATTERN', assessment: null, comment: '', revision: 0 }) as any)
    vi.mocked(feedbackApi.save).mockReturnValue(pending.promise)
    const wrapper = mount(FeedbackEditor, { props: { taskId: 'task-1', subjectKind: 'PATTERN', subjectId: id, subjectText: '此模式候选', conclusion: true }, global: { stubs: namedAntStubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('这条结论是否准确？')
    await textarea(wrapper).setValue('仅评论')
    await button(wrapper, '准确').trigger('click')
    await button(wrapper, '清除评价').trigger('click')
    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('仅评论')
    await button(wrapper, '保存反馈').trigger('click')
    expect(feedbackApi.save).toHaveBeenCalledWith('PATTERN', id, { assessment: null, comment: '仅评论', expectedRevision: 0 })
    expect(textarea(wrapper).attributes('disabled')).toBeUndefined()
    await textarea(wrapper).setValue('保存中继续编辑')
    pending.resolve(server(id, { subjectKind: 'PATTERN', assessment: null, comment: '仅评论', revision: 1 }))
    await flushPromises()
    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('保存中继续编辑')
    expect(wrapper.text()).toContain('尚未保存')
    wrapper.unmount()
  })

  it('恢复非空草稿时仍显示读取失败原因', async () => {
    const id = nextId()
    vi.mocked(feedbackApi.get).mockResolvedValueOnce(server(id) as any)
    const first = await start(id)
    await textarea(first).setValue('待保存草稿'); first.unmount()
    vi.mocked(feedbackApi.get).mockRejectedValueOnce(new Error('服务暂不可用'))
    const restored = await start(id)
    expect((textarea(restored).element as HTMLInputElement).value).toBe('待保存草稿')
    expect(restored.find('a-alert-stub').attributes('message')).toBe('服务暂不可用')
    restored.unmount()
  })

  it('重新挂载的旧读取不能回退在途保存的成功结果', async () => {
    const id = nextId(); const saving = deferred<any>(); const reading = deferred<any>()
    vi.mocked(feedbackApi.get).mockResolvedValueOnce(server(id) as any).mockReturnValueOnce(reading.promise)
    vi.mocked(feedbackApi.save).mockReturnValueOnce(saving.promise)
    const first = await start(id)
    await textarea(first).setValue('保存后的新内容')
    await button(first, '保存反馈').trigger('click'); first.unmount()
    const restored = await start(id)
    saving.resolve(server(id, { comment: '保存后的新内容', revision: 2 })); await flushPromises()
    reading.resolve(server(id)); await flushPromises()
    expect((textarea(restored).element as HTMLInputElement).value).toBe('保存后的新内容')
    expect(readFeedbackDraft(feedbackDraftKey('task-1', 'ISSUE', id))?.revision).toBe(2)
    restored.unmount()
  })

  it('清空反馈的草稿在重新读取失败后仍是未保存状态', async () => {
    const id = nextId()
    vi.mocked(feedbackApi.get).mockResolvedValue(server(id) as any)
    const first = await start(id)
    await button(first, '清除评价').trigger('click')
    await textarea(first).setValue('')
    first.unmount()
    vi.mocked(feedbackApi.get).mockRejectedValue(new Error('读取失败'))

    const restored = await start(id)
    expect(restored.text()).toContain('尚未保存')
    expect(button(restored, '保存反馈').attributes('disabled')).toBeUndefined()
    expect(hasDirtyFeedbackDrafts('task-1')).toBe(true)
    restored.unmount()
  })

  it('手工合并后以冲突的最新内容作为已保存基线', async () => {
    const id = nextId()
    vi.mocked(feedbackApi.get).mockResolvedValue(server(id) as any)
    vi.mocked(feedbackApi.save).mockRejectedValueOnce(Object.assign(new Error('冲突'), {
      code: 'REVISION_CONFLICT', details: { current: server(id, { comment: '远端改动', revision: 2 }) }
    })).mockResolvedValueOnce(server(id, { revision: 3 }) as any)
    const wrapper = await start(id)
    await textarea(wrapper).setValue('临时编辑')
    await button(wrapper, '保存反馈').trigger('click'); await flushPromises()
    await textarea(wrapper).setValue('服务端评论')
    await button(wrapper, '已核对，使用此版本号保存我的合并内容').trigger('click')

    expect(wrapper.text()).toContain('尚未保存')
    expect(button(wrapper, '保存反馈').attributes('disabled')).toBeUndefined()
    await button(wrapper, '保存反馈').trigger('click'); await flushPromises()
    expect(feedbackApi.save).toHaveBeenLastCalledWith('ISSUE', id, {
      assessment: 'ACCURATE', comment: '服务端评论', expectedRevision: 2
    })
    wrapper.unmount()
  })

  it('未读取成功时禁止保存并提供读取重试', async () => {
    const id = nextId()
    vi.mocked(feedbackApi.get).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce(server(id) as any)
    const wrapper = await start(id)
    expect(button(wrapper, '保存反馈').attributes('disabled')).toBeDefined()
    await button(wrapper, '重试读取').trigger('click'); await flushPromises()
    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('服务端评论')
    wrapper.unmount()
  })

  it('卸载后迟到读取不能覆盖新编辑器的草稿', async () => {
    const id = nextId(); const pending = deferred<any>()
    vi.mocked(feedbackApi.get).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(server(id) as any)
    const old = await start(id); old.unmount()
    const current = await start(id)
    await textarea(current).setValue('新的草稿')
    pending.resolve(server(id, { comment: '迟到旧值' })); await flushPromises()
    expect(readFeedbackDraft(feedbackDraftKey('task-1', 'ISSUE', id))?.comment).toBe('新的草稿')
    current.unmount()
  })

  it('卸载后保存成功清理原主体草稿并通知任务刷新统计', async () => {
    const id = nextId(); const pending = deferred<any>()
    vi.mocked(feedbackApi.get).mockResolvedValue(server(id) as any)
    vi.mocked(feedbackApi.save).mockReturnValueOnce(pending.promise)
    const wrapper = await start(id)
    await textarea(wrapper).setValue('本次保存')
    await button(wrapper, '保存反馈').trigger('click')
    const version = feedbackSaveVersion('task-1')
    wrapper.unmount()
    pending.resolve(server(id, { comment: '本次保存', revision: 2 })); await flushPromises()
    expect(hasDirtyFeedbackDrafts('task-1')).toBe(false)
    expect(feedbackSaveVersion('task-1')).toBe(version + 1)
  })

  it('确认丢弃后迟到保存不重新建立草稿', async () => {
    const id = nextId(); const pending = deferred<any>()
    vi.mocked(feedbackApi.get).mockResolvedValue(server(id) as any)
    vi.mocked(feedbackApi.save).mockReturnValueOnce(pending.promise)
    const wrapper = await start(id)
    await textarea(wrapper).setValue('已提交')
    await button(wrapper, '保存反馈').trigger('click'); wrapper.unmount()
    discardFeedbackDrafts('task-1')
    pending.resolve(server(id, { comment: '已提交', revision: 2 })); await flushPromises()
    expect(readFeedbackDraft(feedbackDraftKey('task-1', 'ISSUE', id))).toBeUndefined()
  })
})

describe('问题级／结果级反馈编辑器', () => {
  it('读取当前评价与评论，保存时带 revision 并发出 saved 事件', async () => {
    const id = nextId()
    vi.mocked(feedbackApi.get).mockResolvedValue(server(id) as any)
    vi.mocked(feedbackApi.save).mockResolvedValue(server(id, {
      assessment: 'INACCURATE', revision: 2, updatedAt: '2026-10-21T10:05:00'
    }) as any)

    const wrapper = await start(id)
    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('服务端评论')
    expect(button(wrapper, '准确').attributes('type')).toBe('primary')

    await button(wrapper, '不准确').trigger('click')
    expect(button(wrapper, '保存反馈').attributes('disabled')).toBeUndefined()
    await button(wrapper, '保存反馈').trigger('click')
    await flushPromises()

    expect(feedbackApi.save).toHaveBeenCalledWith('ISSUE', id,
      { assessment: 'INACCURATE', comment: '服务端评论', expectedRevision: 1 })
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.text()).toContain('已保存')
    wrapper.unmount()
  })

  it('冲突时保留本地输入并展示服务端最新内容', async () => {
    const id = nextId()
    vi.mocked(feedbackApi.get).mockResolvedValue(server(id, { assessment: 'ACCURATE' }) as any)
    const conflict = Object.assign(new Error('反馈已被其他页面修改'), {
      code: 'REVISION_CONFLICT',
      details: { current: server(id, { assessment: 'UNKNOWN', comment: '服务端最新', revision: 2 }) }
    })
    vi.mocked(feedbackApi.save).mockRejectedValue(conflict)

    const wrapper = await start(id)
    await button(wrapper, '不准确').trigger('click')
    await textarea(wrapper).setValue('本地合并内容')
    await button(wrapper, '保存反馈').trigger('click')
    await flushPromises()

    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('本地合并内容')
    expect(wrapper.findAll('a-alert-stub').map((alert: any) => alert.attributes('message')))
      .toContain('其他页面已更新反馈。当前输入已保留，请核对最新内容并手动合并。')
    expect(wrapper.text()).toContain('最新评价：无法判断')
    expect(wrapper.text()).toContain('服务端最新')

    await button(wrapper, '已核对，使用此版本号保存我的合并内容').trigger('click')
    expect(wrapper.text()).not.toContain('其他页面已更新反馈')
    wrapper.unmount()
  })

  it('切换问题后保留原问题的未保存草稿，返回时恢复', async () => {
    const first = nextId()
    const second = nextId()
    vi.mocked(feedbackApi.get).mockImplementation((async (_kind: string, id: string) =>
      id === first
        ? server(first, { assessment: 'ACCURATE' })
        : server(second, { assessment: 'UNKNOWN', comment: '第二个主体', revision: 0 })) as any)

    const wrapper = await start(first)
    await textarea(wrapper).setValue('第一个问题的草稿')
    await wrapper.setProps({ subjectId: second })
    await flushPromises()
    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('第二个主体')
    expect(wrapper.text()).toContain('尚未反馈')

    await wrapper.setProps({ subjectId: first })
    await flushPromises()
    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('第一个问题的草稿')
    expect(wrapper.text()).toContain('尚未保存')
    wrapper.unmount()
  })

  it('新主体读取失败时不保留上一个主体的评价和评论', async () => {
    const first = nextId()
    const second = nextId()
    vi.mocked(feedbackApi.get).mockImplementation(async (_kind: string, id: string) => {
      if (id === second) throw new Error('读取失败')
      return server(first, { assessment: 'ACCURATE', comment: '第一个主体' }) as any
    })

    const wrapper = await start(first)
    await wrapper.setProps({ subjectId: second })
    await flushPromises()

    expect((textarea(wrapper).element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('a-alert-stub').attributes('message')).toBe('读取失败')
    expect(button(wrapper, '准确').attributes('type')).toBe('default')
    wrapper.unmount()
  })
})
