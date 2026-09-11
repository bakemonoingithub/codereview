/**
 * 审查/报告耗时的纯逻辑（A 批 E3）。
 *
 * 指标 8 的验证方法是"5 个 900 行文件批量分析，**记录完整耗时**"，
 * 而前端原先从不计算 `finishedAt - startedAt`（全项目搜 `耗时`/`duration` 零命中），
 * 耗时只能靠现场秒表。这里把格式化抽成纯函数，便于单测覆盖边界。
 */

/**
 * 解析后端时间戳为毫秒。
 *
 * 后端把 `LocalDateTime` 序列化成 `yyyy-MM-dd HH:mm:ss`（无时区）。这种"空格分隔"的写法
 * 不是 ECMAScript 规范认可的格式：V8 能解析，但 Safari 等会得到 `Invalid Date`。
 * 因此统一把空格换成 `T` 走 ISO 本地时间分支。
 */
export function parseTimestamp(value?: string | null): number | null {
  if (!value) {
    return null
  }
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const ms = Date.parse(normalized)
  return Number.isNaN(ms) ? null : ms
}

/** 秒数 → 中文可读（用于自证"< 1 小时"，所以小时也要显示出来） */
export function formatSeconds(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds))
  if (seconds < 60) {
    return `${seconds} 秒`
  }
  const minutes = Math.floor(seconds / 60)
  const restSeconds = seconds % 60
  if (minutes < 60) {
    return restSeconds ? `${minutes} 分 ${restSeconds} 秒` : `${minutes} 分`
  }
  const hours = Math.floor(minutes / 60)
  const restMinutes = minutes % 60
  return restMinutes ? `${hours} 小时 ${restMinutes} 分` : `${hours} 小时`
}

/**
 * 已完成任务的耗时。
 *
 * 缺任一时间戳都返回破折号：宁可显示"—"，也不要用 `now` 顶替，
 * 否则一个还在跑的记录会被显示成"已完成、耗时 3 秒"。
 */
export function formatDuration(startedAt?: string | null, finishedAt?: string | null): string {
  const start = parseTimestamp(startedAt)
  const end = parseTimestamp(finishedAt)
  if (start === null || end === null) {
    return '—'
  }
  return formatSeconds((end - start) / 1000)
}

/**
 * 进行中任务的已耗时。
 *
 * 只用于"用户正在盯着看"的场景（进度条旁），因为它是按调用时刻算的，
 * 不会自己跳动；表格里的静态单元格不要用它。
 */
export function formatElapsed(startedAt?: string | null, now: number = Date.now()): string {
  const start = parseTimestamp(startedAt)
  if (start === null) {
    return '—'
  }
  return formatSeconds((now - start) / 1000)
}

/** 是否已有结束时间（判断"进行中"与"已完成"） */
export function hasFinished(finishedAt?: string | null): boolean {
  return parseTimestamp(finishedAt) !== null
}
