import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { errMsg } from './client'

type Request<T> = (signal: AbortSignal) => Promise<T>

/** 一个页面查询只接收当前请求的结果、错误和结束状态；写操作完成后 reload 使用最新条件。 */
export function useRemoteQuery<T>(request: Request<T> | null) {
  const currentRequest = useRef(request)
  const active = useRef<AbortController | null>(null)
  const [state, setState] = useState<{
    request: Request<T> | null; data: T | undefined; loading: boolean
  }>({ request: null, data: undefined, loading: false })

  const cancel = useCallback(() => {
    const previous = active.current
    active.current = null
    previous?.abort()
  }, [])

  const reload = useCallback(async () => {
    const load = currentRequest.current
    if (!load) return
    cancel()
    const controller = new AbortController()
    active.current = controller
    setState(previous => ({
      request: load,
      data: previous.request === load ? previous.data : undefined,
      loading: true,
    }))
    try {
      const data = await load(controller.signal)
      if (active.current === controller) setState({ request: load, data, loading: false })
    } catch (error) {
      if (active.current === controller) message.error(errMsg(error))
    } finally {
      if (active.current === controller) {
        active.current = null
        setState(previous => ({ ...previous, loading: false }))
      }
    }
  }, [cancel])

  useLayoutEffect(() => {
    currentRequest.current = request
    void reload()
    return () => {
      currentRequest.current = null
      cancel()
    }
  }, [request, reload, cancel])

  // 条件已经改变而新请求尚未启动时，也不能让旧数据与新页码/筛选一起显示。
  return {
    data: state.request === request ? state.data : undefined,
    loading: request !== null && (state.request !== request || state.loading),
    reload,
    cancel,
  }
}
