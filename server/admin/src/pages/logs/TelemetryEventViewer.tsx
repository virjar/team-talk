import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Alert, Button, Card, Col, DatePicker, Empty, Input, Row, Select, Space, Spin, Table, Tag, Typography } from 'antd'
import type { Dayjs } from 'dayjs'
import { api, errMsg } from '../../api/client'
import {
  AdminPage,
  ConnectionTraceLookup,
  exactTime,
  hasValidTelemetryHighlightSpans,
  shortIdentity,
  TELEMETRY_PAGE_SIZE,
  TELEMETRY_SEARCH_WINDOW,
  TelemetryEventCategory,
  TelemetryEventItem,
  TelemetryPlatform,
  TelemetryTextHighlight,
} from './TelemetryModels'

type TraceLookupState = {
  loading: boolean
  data: ConnectionTraceLookup | null
  error: string | null
}

type EventFilters = {
  keyword: string
  uid: string
  deviceId: string
  phone: string
  platform?: TelemetryPlatform
  osName: string
  osVersion: string
  appVersion: string
  gitCommit: string
  category?: TelemetryEventCategory
  eventName: string
}

const emptyFilters: EventFilters = {
  keyword: '', uid: '', deviceId: '', phone: '', osName: '', osVersion: '', appVersion: '', gitCommit: '', eventName: '',
}

function HighlightedText({ highlight, fallback }: { highlight: TelemetryTextHighlight | null, fallback: string }) {
  const value = highlight?.text ?? fallback
  const spans = highlight && hasValidTelemetryHighlightSpans(highlight) ? highlight.spans : []
  if (!spans.length) return <>{value}</>

  const parts: ReactNode[] = []
  let cursor = 0
  spans.forEach((span, index) => {
    if (cursor < span.start) parts.push(<span key={`plain-${index}`}>{value.slice(cursor, span.start)}</span>)
    parts.push(<mark key={`mark-${index}`}>{value.slice(span.start, span.end)}</mark>)
    cursor = span.end
  })
  if (cursor < value.length) parts.push(<span key="plain-tail">{value.slice(cursor)}</span>)
  return <>{parts}</>
}

export function TelemetryEventViewer() {
  const [filters, setFilters] = useState<EventFilters>(emptyFilters)
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null)
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<AdminPage<TelemetryEventItem>>({ total: 0, items: [] })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const requestGeneration = useRef(0)
  const requestedPage = useRef(1)
  const [traceLookups, setTraceLookups] = useState<Record<number, TraceLookupState>>({})

  const load = useCallback(async (nextPage = page) => {
    const generation = ++requestGeneration.current
    requestedPage.current = nextPage
    if (filters.uid.trim() && filters.phone.trim()) {
      setLoading(false)
      setError('UID 和手机号不能同时作为事件筛选条件')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const params = Object.fromEntries(Object.entries(filters).filter(([, value]) => value)) as Record<string, unknown>
      if (range) {
        params.start = range[0].startOf('second').valueOf()
        params.end = range[1].endOf('second').valueOf()
      }
      params.page = nextPage
      params.size = TELEMETRY_PAGE_SIZE
      const { data } = await api.get<AdminPage<TelemetryEventItem>>('/telemetry/events', { params })
      if (generation !== requestGeneration.current) return
      setResult(data)
      setPage(nextPage)
    } catch (e) {
      if (generation === requestGeneration.current) setError(errMsg(e))
    } finally {
      if (generation === requestGeneration.current) setLoading(false)
    }
  }, [filters, page, range])

  const loadConnectionTraces = useCallback(async (row: TelemetryEventItem, force = false) => {
    if (!row.connectionTraceContext) return
    const existing = traceLookups[row.id]
    if (!force && (existing?.loading || existing?.data)) return
    setTraceLookups(current => ({
      ...current,
      [row.id]: { loading: true, data: existing?.data ?? null, error: null },
    }))
    try {
      const { data } = await api.get<ConnectionTraceLookup>(
        `/telemetry/events/${encodeURIComponent(row.id)}/connection-traces`,
      )
      setTraceLookups(current => ({
        ...current,
        [row.id]: { loading: false, data, error: null },
      }))
    } catch (e) {
      setTraceLookups(current => ({
        ...current,
        [row.id]: { loading: false, data: null, error: errMsg(e) },
      }))
    }
  }, [traceLookups])

  useEffect(() => { void load(1) }, [])

  const columns = useMemo(() => [
    {
      title: '时间', dataIndex: 'occurredAt', width: 165,
      render: (value: number, row: TelemetryEventItem) => (
        <Typography.Text title={`服务端接收：${exactTime(row.receivedAt)}`}>{exactTime(value)}</Typography.Text>
      ),
    },
    {
      title: '用户 / 设备', width: 205,
      render: (_: unknown, row: TelemetryEventItem) => (
        <div>
          <Typography.Text copyable={{ text: row.uid }}>{shortIdentity(row.uid)}</Typography.Text><br />
          <Typography.Text type="secondary" copyable={{ text: row.deviceId }}>{shortIdentity(row.deviceId)}</Typography.Text>
        </div>
      ),
    },
    {
      title: '事件', width: 230,
      render: (_: unknown, row: TelemetryEventItem) => (
        <div><Tag>{row.category}</Tag><Typography.Text code>{row.eventName}</Typography.Text></div>
      ),
    },
    {
      title: '客户端', width: 190,
      render: (_: unknown, row: TelemetryEventItem) => (
        <div>{row.platform} {row.osName} {row.osVersion}<br />
          <Typography.Text type="secondary">{row.appVersion} · {shortIdentity(row.gitCommit)}</Typography.Text>
        </div>
      ),
    },
    {
      title: '上下文', dataIndex: 'message', ellipsis: true,
      render: (value: string | null, row: TelemetryEventItem) => (
        <Typography.Text><HighlightedText highlight={row.highlight} fallback={value || '—'} /></Typography.Text>
      ),
    },
  ], [])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card size="small">
        <Row gutter={[8, 8]}>
          <Col span={6}><Input allowClear maxLength={256} placeholder="全文关键词" value={filters.keyword} onChange={e => setFilters({ ...filters, keyword: e.target.value })} /></Col>
          <Col span={6}><Input allowClear maxLength={36} placeholder="UID" value={filters.uid} onChange={e => setFilters({ ...filters, uid: e.target.value })} /></Col>
          <Col span={6}><Input allowClear maxLength={100} placeholder="设备 ID" value={filters.deviceId} onChange={e => setFilters({ ...filters, deviceId: e.target.value })} /></Col>
          <Col span={6}><Input allowClear maxLength={20} placeholder="手机号（仅解析 UID）" value={filters.phone} onChange={e => setFilters({ ...filters, phone: e.target.value })} /></Col>
          <Col span={4}><Select<TelemetryPlatform> allowClear style={{ width: '100%' }} placeholder="平台" value={filters.platform} onChange={platform => setFilters({ ...filters, platform })} options={['ANDROID', 'DESKTOP', 'HEADLESS', 'UNKNOWN'].map(value => ({ value: value as TelemetryPlatform }))} /></Col>
          <Col span={4}><Input allowClear maxLength={128} placeholder="系统名称" value={filters.osName} onChange={e => setFilters({ ...filters, osName: e.target.value })} /></Col>
          <Col span={4}><Input allowClear maxLength={128} placeholder="系统版本" value={filters.osVersion} onChange={e => setFilters({ ...filters, osVersion: e.target.value })} /></Col>
          <Col span={4}><Input allowClear maxLength={128} placeholder="客户端版本" value={filters.appVersion} onChange={e => setFilters({ ...filters, appVersion: e.target.value })} /></Col>
          <Col span={4}><Input allowClear maxLength={80} placeholder="Git commit" value={filters.gitCommit} onChange={e => setFilters({ ...filters, gitCommit: e.target.value })} /></Col>
          <Col span={4}><Select<TelemetryEventCategory> allowClear style={{ width: '100%' }} placeholder="事件类别" value={filters.category} onChange={category => setFilters({ ...filters, category })} options={['FAULT', 'LOG', 'PAGE_DWELL', 'ACTION', 'SYSTEM', 'USER_NOTICE', 'MEDIA', 'OUTGOING_QUEUE'].map(value => ({ value: value as TelemetryEventCategory }))} /></Col>
          <Col span={6}><Input allowClear maxLength={96} placeholder="事件名称" value={filters.eventName} onChange={e => setFilters({ ...filters, eventName: e.target.value })} /></Col>
          <Col span={12}><DatePicker.RangePicker showTime placeholder={['接收时间起', '接收时间止']} style={{ width: '100%' }} value={range} onChange={value => setRange(value as [Dayjs, Dayjs] | null)} /></Col>
          <Col span={24}>
            <Space>
              <Button type="primary" loading={loading} onClick={() => load(1)}>查询</Button>
              <Button onClick={() => { setFilters(emptyFilters); setRange(null); setError(null) }}>清空条件</Button>
              <Typography.Text type="secondary">日志按服务端接收时间精确保留 7×24 小时；手机号不会写入事件。</Typography.Text>
            </Space>
          </Col>
        </Row>
      </Card>
      {error && <Alert
        showIcon
        type="error"
        message="遥测事件加载失败"
        description={error}
        action={<Button size="small" onClick={() => load(requestedPage.current)}>重试</Button>}
      />}
      <Table<TelemetryEventItem>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={result.items}
        pagination={{
          current: page,
          pageSize: TELEMETRY_PAGE_SIZE,
          total: Math.min(result.total, TELEMETRY_SEARCH_WINDOW),
          showSizeChanger: false,
          showTotal: () => result.total > TELEMETRY_SEARCH_WINDOW
            ? `共 ${result.total} 条，仅可浏览最新 ${TELEMETRY_SEARCH_WINDOW} 条`
            : `共 ${result.total} 条`,
          onChange: next => load(next),
        }}
        expandable={{
          onExpand: (expanded, row) => {
            if (expanded) void loadConnectionTraces(row)
          },
          expandedRowRender: row => {
            const lookup = traceLookups[row.id]
            const context = row.connectionTraceContext
            return (
              <Space direction="vertical" size="small" style={{ width: '100%' }}>
                <Space direction="vertical" size={2}>
                  <Typography.Text>eventId：<Typography.Text copyable>{row.eventId}</Typography.Text></Typography.Text>
                  <Typography.Text>batchId：<Typography.Text copyable>{row.batchId}</Typography.Text></Typography.Text>
                  <Typography.Text>runId：<Typography.Text copyable>{row.runId}</Typography.Text> · sequence {row.sequence}</Typography.Text>
                  <Typography.Text>设备：{row.deviceModel || '—'} · {row.architecture || '—'} · protocol {row.protocolVersion}</Typography.Text>
                  <Typography.Text>构建：{row.buildNumber || '—'} / {row.distribution || '—'} / <Typography.Text copyable>{row.gitCommit || '—'}</Typography.Text></Typography.Text>
                  <Typography.Text>build identity：<Typography.Text copyable>{row.buildIdentity || '—'}</Typography.Text> · {row.buildTime || '—'}</Typography.Text>
                  {row.message && <Typography.Paragraph copyable>{row.message}</Typography.Paragraph>}
                </Space>

                <Space align="center" style={{ marginTop: 8 }}>
                  <Typography.Title level={5} style={{ margin: 0 }}>同代服务端轨迹</Typography.Title>
                  {context && <Button
                    size="small"
                    loading={lookup?.loading}
                    onClick={() => loadConnectionTraces(row, true)}
                  >
                    刷新轨迹
                  </Button>}
                </Space>
                {!context && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该客户端事件未命中连接级诊断，没有服务端轨迹" />}
                {context && <Space wrap>
                  <Typography.Text>trace：<Typography.Text copyable>{context.traceId}</Typography.Text></Typography.Text>
                  <Typography.Text>session：<Typography.Text copyable>{context.sessionId}</Typography.Text></Typography.Text>
                  <Typography.Text>generation：{context.connectionGeneration}</Typography.Text>
                  <Typography.Text>policy：{context.policyRevision}</Typography.Text>
                </Space>}
                {context && lookup?.loading && <Spin tip="正在查询同代轨迹" />}
                {context && lookup?.error && <Alert
                  showIcon
                  type="error"
                  message="服务端轨迹查询失败"
                  description={lookup.error}
                  action={<Button size="small" onClick={() => loadConnectionTraces(row, true)}>重试</Button>}
                />}
                {context && lookup?.data?.truncated && <Alert
                  showIcon
                  type="warning"
                  message="轨迹已达单次查询上限，仅显示有界结果"
                />}
                {context && lookup?.data && lookup.data.traces.length === 0 && (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该连接代际暂无可用服务端轨迹" />
                )}
                {context && lookup?.data && lookup.data.traces.length > 0 && <Table
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={lookup.data.traces}
                  columns={[
                    { title: '时间', dataIndex: 'occurredAt', width: 165, render: exactTime },
                    { title: '阶段', dataIndex: 'phase', width: 140, render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
                    { title: '结果', dataIndex: 'outcome', width: 110, render: (value: string) => <Tag>{value}</Tag> },
                    { title: '脱敏详情', dataIndex: 'detail', render: (value: string | null) => value || '—' },
                  ]}
                />}
              </Space>
            )
          },
        }}
      />
    </Space>
  )
}
