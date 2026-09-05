import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Input, Space, Table, Tag, Typography } from 'antd'
import { api, errMsg } from '../../api/client'
import {
  AdminPage,
  exactTime,
  shortIdentity,
  TELEMETRY_PAGE_SIZE,
  TelemetryDeviceItem,
} from './TelemetryModels'

export function TelemetryDeviceViewer() {
  const [query, setQuery] = useState('')
  const [phone, setPhone] = useState('')
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<AdminPage<TelemetryDeviceItem>>({ total: 0, items: [] })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const requestGeneration = useRef(0)
  const requestedPage = useRef(1)
  const load = useCallback(async (nextPage = page) => {
    const generation = ++requestGeneration.current
    requestedPage.current = nextPage
    setLoading(true)
    setError(null)
    try {
      const { data } = await api.get<AdminPage<TelemetryDeviceItem>>('/telemetry/devices', {
        params: {
          query: query.trim() || undefined,
          phone: phone.trim() || undefined,
          page: nextPage,
          size: TELEMETRY_PAGE_SIZE,
        },
      })
      if (generation !== requestGeneration.current) return
      setResult(data)
      setPage(nextPage)
    } catch (e) {
      if (generation === requestGeneration.current) setError(errMsg(e))
    } finally {
      if (generation === requestGeneration.current) setLoading(false)
    }
  }, [page, phone, query])
  useEffect(() => { void load(1) }, [])
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space>
        <Input allowClear maxLength={128} style={{ width: 340 }} placeholder="UID、设备 ID、版本或系统" value={query} onChange={e => setQuery(e.target.value)} onPressEnter={() => load(1)} />
        <Input allowClear maxLength={20} style={{ width: 220 }} placeholder="手机号（精确）" value={phone} onChange={e => setPhone(e.target.value)} onPressEnter={() => load(1)} />
        <Button type="primary" loading={loading} onClick={() => load(1)}>查询设备</Button>
        <Typography.Text type="secondary">画像由每次认证心跳更新，不依赖日志正文猜测版本。</Typography.Text>
      </Space>
      {error && <Alert
        showIcon
        type="error"
        message="客户端设备加载失败"
        description={error}
        action={<Button size="small" onClick={() => load(requestedPage.current)}>重试</Button>}
      />}
      <Table<TelemetryDeviceItem>
        rowKey={row => `${row.uid}:${row.deviceId}`}
        size="small"
        loading={loading}
        dataSource={result.items}
        pagination={{ current: page, pageSize: TELEMETRY_PAGE_SIZE, total: result.total, showSizeChanger: false, showTotal: total => `共 ${total} 台`, onChange: next => load(next) }}
        columns={[
          { title: 'UID', dataIndex: 'uid', width: 170, ellipsis: true, render: value => <Typography.Text copyable>{shortIdentity(value)}</Typography.Text> },
          { title: '设备 ID', dataIndex: 'deviceId', width: 180, ellipsis: true, render: value => <Typography.Text copyable>{shortIdentity(value)}</Typography.Text> },
          { title: '平台', dataIndex: 'platform', width: 100 },
          { title: '系统 / 设备', width: 200, render: (_, row) => <span>{row.osName || '—'} {row.osVersion || ''}<br /><Typography.Text type="secondary">{row.deviceModel || '—'} · {row.architecture || '—'}</Typography.Text></span> },
          { title: '客户端', width: 220, render: (_, row) => <span>{row.appVersion || '—'}<br /><Typography.Text type="secondary">{row.distribution || '—'} · {row.buildNumber || '—'}</Typography.Text></span> },
          { title: 'Commit', dataIndex: 'gitCommit', width: 135, render: value => <Typography.Text copyable={{ text: value }}>{shortIdentity(value)}</Typography.Text> },
          { title: '最后出现', dataIndex: 'lastSeenAt', width: 165, render: exactTime },
          { title: '采集', width: 150, render: (_, row) => <span><Tag color={row.policyMode === 'DIAGNOSTIC' ? 'orange' : 'default'}>{row.policyMode}</Tag>{row.policyExpiresAt ? <div>{exactTime(row.policyExpiresAt)}</div> : null}</span> },
        ]}
        expandable={{
          expandedRowRender: row => (
            <Space direction="vertical" size={2}>
              <Typography.Text>首次出现：{exactTime(row.firstSeenAt)} · 最近事件：{exactTime(row.lastEventAt)}</Typography.Text>
              <Typography.Text>协议：{row.protocolVersion} · 构建时间：{row.buildTime || '—'}</Typography.Text>
              <Typography.Text>build identity：<Typography.Text copyable>{row.buildIdentity || '—'}</Typography.Text></Typography.Text>
            </Space>
          ),
        }}
      />
    </Space>
  )
}
