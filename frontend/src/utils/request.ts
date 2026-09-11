import axios from 'axios'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000,
  withCredentials: true
})

/** HTTP 状态码 → 中文文案 */
const STATUS_TEXT: Record<number, string> = {
  400: '请求参数有误',
  401: '登录状态已失效，请重新登录',
  403: '没有权限执行该操作',
  404: '资源不存在（可能已被删除）',
  405: '该操作不被允许',
  409: '数据状态冲突，请刷新后重试',
  412: '前置条件不满足，请刷新后重试',
  413: '内容过大，请减少提交量',
  429: '请求过于频繁，请稍后重试',
  500: '服务器内部错误',
  502: '网关错误，服务暂不可用',
  503: '服务暂不可用，请稍后重试',
  504: '服务响应超时，请稍后重试'
}

/**
 * 把 axios / HTTP 错误归一成中文可读文案。
 *
 * <p>为什么必须在这里做：各调用方都写 `message.error(e?.message || '中文兜底')`，
 * 而 axios 的 `e.message` **永远非空**（`Request failed with status code 500`、
 * `timeout of 30000ms exceeded`），所以中文兜底从不生效 —— 界面直接把英文抛给用户。
 *
 * <p>返回的是**字符串**而不是 Error：调用方只需要可展示的文案，再包一层 Error
 * 只会让"到底该读哪一层"变得含糊。
 */
export function toReadableError(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return error instanceof Error && error.message ? error.message : '请求失败'
  }
  if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message)) {
    return '请求超时，请稍后重试'
  }
  const status = error.response?.status
  if (!status) {
    // 没有响应对象 = 根本没连上（后端没起、网线断了、跨域被拦）
    return '无法连接服务器，请检查网络或后端服务是否已启动'
  }
  return STATUS_TEXT[status] || `请求失败（HTTP ${status}）`
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res?.code === 0) {
      return res.data
    }
    // 业务错误：后端 message 本身就是中文，直接用
    return Promise.reject(new Error(res?.message || '请求失败'))
  },
  // 网络/HTTP 层错误：归一成中文再抛，避免各调用方各自处理英文
  (error) => Promise.reject(new Error(toReadableError(error)))
)

export default request
