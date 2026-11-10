import { describe, expect, it } from 'vitest'
import { baseName, fileName, taskLabel } from '@/utils/taskLabel'

describe('taskLabel', () => {
  it('空名称与纯空白都回落到审查任务加 ID', () => {
    expect(taskLabel(null, 't1')).toBe('审查任务 t1')
    expect(taskLabel('  ', 't1')).toBe('审查任务 t1')
    expect(taskLabel(' 支付重试 ', 't1')).toBe('支付重试')
  })
})

describe('fileName / baseName', () => {
  it('正斜杠、反斜杠和末尾分隔符都只留最后一段', () => {
    expect(baseName('src/main/java/Order.java')).toBe('Order.java')
    expect(baseName('src\\main\\Order.java')).toBe('Order.java')
    expect(baseName('src/main/Order.java/')).toBe('Order.java')
    expect(fileName('src\\main\\java\\Order.java')).toBe('Order.java')
    expect(fileName(null)).toBe('Agent Markdown')
    expect(fileName('   ')).toBe('Agent Markdown')
    expect(fileName('///')).toBe('Agent Markdown')
  })

  it('去掉盘符和两侧空白，连续分隔符不影响最后一段', () => {
    expect(baseName('C:\\src\\Order.java')).toBe('Order.java')
    expect(baseName('C:Order.java')).toBe('Order.java')
    expect(baseName('c:/src//Order.java')).toBe('Order.java')
    expect(baseName('  src\\\\Order.java  ')).toBe('Order.java')
    expect(baseName('C:\\')).toBe('')
    expect(baseName('.java')).toBe('.java')
    expect(fileName('C:\\src\\Order.java')).toBe('Order.java')
    expect(fileName('C:\\')).toBe('Agent Markdown')
  })
})
