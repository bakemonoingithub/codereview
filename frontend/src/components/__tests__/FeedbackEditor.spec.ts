// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({ feedbackApi: { get: vi.fn(), save: vi.fn() } }))
import { feedbackApi } from '@/api/v2'

let sequence = 0
const nextId = () => `subject-${++sequence}`

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
  document.body.innerHTML = ''
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
