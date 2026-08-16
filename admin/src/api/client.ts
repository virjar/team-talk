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
    if (err.response?.status === 401) {
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
