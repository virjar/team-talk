import { useCallback, useMemo, useRef, useState } from 'react'
import {
  Badge, Button, Card, Input, Modal, Popconfirm, Select, Space, Spin, Switch,
  Table, Tag, Typography, message,
} from 'antd'
import { UploadOutlined } from '@ant-design/icons'
import { api, errMsg, TOKEN_KEY } from '../api/client'
import { useRemoteQuery } from '../api/useRemoteQuery'

// 客户端发布注册中心：发布列表、通道指向/回滚/停用、CI 上传。
// 发布上传端点在 /api/v1/client/releases（同时接受管理会话与 CI 发布令牌）。

interface ReleaseRow {
  id: number
  clientType: string
  platform: string
  arch: string
  version: string
  build: number
  channel: string
  status: string
  forced: boolean
  fileCount: number
  totalBytes: number
  createdAt: number
  createdBy: string
}

interface ChannelRow {
  clientType: string
  platform: string
  arch: string
  channel: string
  enabled: boolean
  currentReleaseId: number | null
  updatedBy: string
  updatedAt: number
}

const CLIENT_LABEL: Record<string, string> = { desktop: '桌面端', android: 'Android', headless: '无头/CLI' }
const CHANNEL_LABEL: Record<string, string> = { stable: '正式', preview: '预览', snapshot: '内测快照' }
const STATUS_COLOR: Record<string, 'success' | 'error' | 'default'> = {
  ACTIVE: 'success',
  DISABLED: 'error',
  SUPERSEDED: 'default',
}

function fmtSize(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return Math.max(1, Math.round(bytes / 1024)) + ' KB'
}

function fmtTime(millis: number): string {
  const d = new Date(millis)
  const pad = (n: number) => (n < 10 ? '0' + n : n)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function targetKey(row: { clientType: string; platform: string; arch: string }): string {
  return `${row.clientType}/${row.platform}/${row.arch}`
}

export default function Releases() {
  const [clientFilter, setClientFilter] = useState<string | undefined>(undefined)
  const [uploading, setUploading] = useState(false)
  const [pointerTarget, setPointerTarget] = useState<ChannelRow | null>(null)
  const [pointerReleaseId, setPointerReleaseId] = useState<number | null>(null)
  const [disableTarget, setDisableTarget] = useState<ReleaseRow | null>(null)
  const [disableFallback, setDisableFallback] = useState<number | null>(null)
  const fileInput = useRef<HTMLInputElement | null>(null)

  const listing = useRemoteQuery(useCallback(async (signal: AbortSignal) => {
    const [relResp, chResp] = await Promise.all([
      api.get<{ releases: ReleaseRow[] }>('/client-releases', {
        params: clientFilter ? { client: clientFilter } : undefined, signal,
      }),
      api.get<{ channels: ChannelRow[] }>('/client-channels', { signal }),
    ])
    return { releases: relResp.data.releases, channels: chResp.data.channels }
  }, [clientFilter]))
  const releases = listing.data?.releases ?? []
  const channels = listing.data?.channels ?? []
  const loading = listing.loading
  const load = listing.reload

  const releasesByTarget = useMemo(() => {
    const map = new Map<string, ReleaseRow[]>()
    for (const row of releases) {
      const key = targetKey(row)
      const list = map.get(key) ?? []
      list.push(row)
      map.set(key, list)
    }
    return map
  }, [releases])

  const upload = async (file: File) => {
    setUploading(true)
    try {
      const form = new FormData()
      form.append('release', file)
      const token = localStorage.getItem(TOKEN_KEY) ?? ''
      const resp = await fetch('/api/v1/client/releases', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
        body: form,
      })
      const body = await resp.json().catch(() => ({}))
      if (!resp.ok) throw new Error(body?.error ?? `上传失败 (${resp.status})`)
      message.success(`发布成功（releaseId=${body.releaseId}）`)
      load()
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setUploading(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  const disableRelease = async () => {
    if (!disableTarget) return
    try {
      await api.post(`/client-releases/${disableTarget.id}/disable`, { fallbackReleaseId: disableFallback })
      message.success('已停用')
      setDisableTarget(null)
      setDisableFallback(null)
      load()
    } catch (error) {
      message.error(errMsg(error))
    }
  }

  const enableRelease = async (row: ReleaseRow) => {
    try {
      await api.post(`/client-releases/${row.id}/enable`)
      message.success('已启用')
      load()
    } catch (error) { message.error(errMsg(error)) }
  }

  const deleteRelease = async (row: ReleaseRow) => {
    try {
      await api.delete(`/client-releases/${row.id}`)
      message.success('已删除')
      load()
    } catch (error) { message.error(errMsg(error)) }
  }

  const savePointer = async () => {
    if (!pointerTarget) return
    try {
      await api.put(
        `/client-channels/${pointerTarget.clientType}/${pointerTarget.platform}/${pointerTarget.arch}/${pointerTarget.channel}`,
        { releaseId: pointerReleaseId },
      )
      message.success('通道已更新')
      setPointerTarget(null)
      load()
    } catch (error) { message.error(errMsg(error)) }
  }

  const toggleChannel = async (row: ChannelRow, enabled: boolean) => {
    try {
      await api.post(
        `/client-channels/${row.clientType}/${row.platform}/${row.arch}/${row.channel}/enabled`,
        { enabled },
      )
      message.success(enabled ? '通道已启用' : '通道已禁用（客户端检查更新将返回 CHANNEL_DISABLED）')
      load()
    } catch (error) { message.error(errMsg(error)) }
  }

  const releaseColumns = [
    {
      title: '端', dataIndex: 'clientType', width: 90,
      render: (v: string) => CLIENT_LABEL[v] ?? v,
    },
    {
      title: '平台', width: 130,
      render: (_: unknown, row: ReleaseRow) => (
        <span>{row.platform}{row.arch !== 'any' ? `/${row.arch}` : ''}</span>
      ),
    },
    { title: '版本', width: 130, render: (_: unknown, row: ReleaseRow) => <span>v{row.version} ({row.build})</span> },
    {
      title: '通道', dataIndex: 'channel', width: 90,
      render: (v: string) => <Tag>{CHANNEL_LABEL[v] ?? v}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string) => <Badge status={STATUS_COLOR[v] ?? 'default'} text={v} />,
    },
    { title: '文件/体积', width: 130, render: (_: unknown, row: ReleaseRow) => `${row.fileCount} 项 · ${fmtSize(row.totalBytes)}` },
    { title: '发布时间', dataIndex: 'createdAt', width: 140, render: (v: number) => fmtTime(v) },
    { title: '来源', dataIndex: 'createdBy', width: 110, ellipsis: true },
    {
      title: '操作', width: 200,
      render: (_: unknown, row: ReleaseRow) => (
        <Space size="small" wrap>
          {row.status === 'ACTIVE' && (
            <a onClick={() => { setDisableTarget(row); setDisableFallback(null) }}>停用</a>
          )}
          {row.status === 'DISABLED' && (
            <>
              <a onClick={() => enableRelease(row)}>启用</a>
              <Popconfirm title="删除后不可恢复，且会清理未共享的制品文件。确定删除？" onConfirm={() => deleteRelease(row)}>
                <a style={{ color: '#cf1322' }}>删除</a>
              </Popconfirm>
            </>
          )}
        </Space>
      ),
    },
  ]

  const channelColumns = [
    {
      title: '端', dataIndex: 'clientType', width: 90,
      render: (v: string) => CLIENT_LABEL[v] ?? v,
    },
    {
      title: '平台', width: 130,
      render: (_: unknown, row: ChannelRow) => (
        <span>{row.platform}{row.arch !== 'any' ? `/${row.arch}` : ''}</span>
      ),
    },
    {
      title: '通道', dataIndex: 'channel', width: 90,
      render: (v: string) => <Tag>{CHANNEL_LABEL[v] ?? v}</Tag>,
    },
    {
      title: '当前发布', width: 160,
      render: (_: unknown, row: ChannelRow) => {
        const rel = releases.find((r) => r.id === row.currentReleaseId)
        if (!rel) return <Typography.Text type="secondary">（未指向）</Typography.Text>
        return <span>v{rel.version} ({rel.build}) · <Tag color={STATUS_COLOR[rel.status]}>{rel.status}</Tag></span>
      },
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 140, render: (v: number) => fmtTime(v) },
    { title: '更新人', dataIndex: 'updatedBy', width: 110, ellipsis: true },
    {
      title: '服务开关', width: 100,
      render: (_: unknown, row: ChannelRow) => (
        <Switch checked={row.enabled} onChange={(next) => toggleChannel(row, next)} />
      ),
    },
    {
      title: '操作', width: 120,
      render: (_: unknown, row: ChannelRow) => (
        <a onClick={() => { setPointerTarget(row); setPointerReleaseId(row.currentReleaseId) }}>切换/回滚</a>
      ),
    },
  ]

  const pointerCandidates = pointerTarget
    ? (releasesByTarget.get(targetKey(pointerTarget)) ?? []).filter((r) => r.status === 'ACTIVE')
    : []
  const disableCandidates = disableTarget
    ? (releasesByTarget.get(targetKey(disableTarget)) ?? [])
      .filter((r) => r.status === 'ACTIVE' && r.id !== disableTarget.id)
    : []

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        title="客户端发布"
        extra={(
          <Space>
            <Select
              allowClear
              placeholder="全部端"
              style={{ width: 140 }}
              value={clientFilter}
              onChange={value => { if (value !== clientFilter) { listing.cancel(); setClientFilter(value) } }}
              options={Object.entries(CLIENT_LABEL).map(([value, label]) => ({ value, label }))}
            />
            <input
              ref={fileInput}
              type="file"
              accept=".zip"
              style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f) }}
            />
            <Button icon={<UploadOutlined />} loading={uploading} onClick={() => fileInput.current?.click()}>
              上传发布包
            </Button>
            <Button onClick={load}>刷新</Button>
          </Space>
        )}
      >
        <Typography.Paragraph type="secondary">
          每次发布保留原始文件；snapshot 允许同一展示版本发布新的构建，已有构建不会被覆盖。
          通过切换通道选择用户收到的版本，停用时可指定回退版本；关闭通道后暂停提供更新。
        </Typography.Paragraph>
        {loading ? <Spin /> : (
          <Table
            rowKey="id"
            size="small"
            columns={releaseColumns}
            dataSource={releases}
            pagination={{ pageSize: 20, showSizeChanger: false }}
          />
        )}
      </Card>

      <Card title="通道与回滚">
        <Typography.Paragraph type="secondary">
          每个端点 × 通道一个指针；把指针切到旧版本即回滚（客户端服从服务端指令，
          支持降级），清空指针则该通道不再提供更新。
        </Typography.Paragraph>
        {loading ? <Spin /> : (
          <Table
            rowKey={(row) => targetKey(row) + '/' + row.channel}
            size="small"
            columns={channelColumns}
            dataSource={channels}
            pagination={false}
          />
        )}
      </Card>

      <Modal
        open={!!pointerTarget}
        title={pointerTarget ? `切换通道 ${targetKey(pointerTarget)} / ${pointerTarget.channel}` : ''}
        onOk={savePointer}
        onCancel={() => setPointerTarget(null)}
        okText="保存"
        cancelText="取消"
      >
        <Select
          allowClear
          style={{ width: '100%' }}
          placeholder="选择要指向的发布（清空 = 不提供更新）"
          value={pointerReleaseId}
          onChange={setPointerReleaseId}
          options={pointerCandidates.map((r) => ({
            value: r.id,
            label: `v${r.version} (${r.build}) · ${CHANNEL_LABEL[r.channel] ?? r.channel} · ${fmtTime(r.createdAt)}`,
          }))}
        />
      </Modal>

      <Modal
        open={!!disableTarget}
        title={disableTarget ? `停用发布 v${disableTarget.version} (${disableTarget.build})` : ''}
        onOk={disableRelease}
        onCancel={() => setDisableTarget(null)}
        okText="停用"
        okButtonProps={{ danger: true }}
        cancelText="取消"
      >
        <Typography.Paragraph>
          停用后该版本不再对客户端可见。正在指向它的通道可以改指回退目标：
        </Typography.Paragraph>
        <Select
          allowClear
          style={{ width: '100%' }}
          placeholder="（可选）选择回退目标"
          value={disableFallback}
          onChange={setDisableFallback}
          options={disableCandidates.map((r) => ({
            value: r.id,
            label: `v${r.version} (${r.build}) · ${CHANNEL_LABEL[r.channel] ?? r.channel}`,
          }))}
        />
      </Modal>
    </Space>
  )
}
