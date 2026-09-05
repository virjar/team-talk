import axios from 'axios'

export const TOKEN_KEY = 'tt-admin-token'

export const api = axios.create({ baseURL: '/api/admin' })

api.interceptors.request.use((cfg) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

api.interceptors.response.use(
  (resp) => resp,
  (err) => {
    // 登录接口自身的 401（凭据错误）交给调用方展示错误；只有会话期接口的
    // 401 才视为 token 失效——整页跳转会吞掉 antd message 提示（SPA 也不应刷新）。
    const isLoginCall = err.config?.url?.includes('/login')
    if (err.response?.status === 401 && !isLoginCall) {
      localStorage.removeItem(TOKEN_KEY)
      window.location.href = '/admin/'
    }
    return Promise.reject(err)
  },
)

export function errMsg(e: unknown): string {
  const any = e as any
  return any?.response?.data?.error ?? any?.message ?? String(e)
}
