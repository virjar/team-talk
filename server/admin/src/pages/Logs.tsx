import { useEffect, useState } from 'react'
import { Card, Col, Row, Table, Tabs, message } from 'antd'
import dayjs from 'dayjs'
import { api, errMsg } from '../api/client'
import { TelemetryDeviceViewer } from './logs/TelemetryDeviceViewer'
import { TelemetryEventViewer } from './logs/TelemetryEventViewer'
import { TelemetryPolicyViewer } from './logs/TelemetryPolicyViewer'

interface FileInfo { name: string; sizeBytes: number; lastModified: number }

function ServerLogViewer() {
  const [files, setFiles] = useState<FileInfo[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [lines, setLines] = useState<string[]>([])
  useEffect(() => {
    api.get<FileInfo[]>('/logs/server').then(r => setFiles(r.data)).catch(e => message.error(errMsg(e)))
  }, [])
  const open = async (name: string) => {
    setSelected(name)
    try {
      const { data } = await api.get(`/logs/server/${encodeURIComponent(name)}`, { params: { lines: 300 } })
      setLines(data.lines)
    } catch (e) {
      message.error(errMsg(e))
    }
  }
  return (
    <Row gutter={16}>
      <Col span={8}>
        <Table rowKey="name" size="small" pagination={false}
          onRow={(row) => ({
            onClick: () => open(row.name),
            style: { cursor: 'pointer', background: row.name === selected ? '#e6f4ff' : undefined },
          })}
          columns={[
            { title: '文件', dataIndex: 'name', ellipsis: true },
            { title: '大小', dataIndex: 'sizeBytes', width: 80, render: (size: number) => `${(size / 1024).toFixed(0)}K` },
            { title: '修改', dataIndex: 'lastModified', width: 100, render: (time: number) => dayjs(time).format('MM-DD HH:mm') },
          ]}
          dataSource={files} />
      </Col>
      <Col span={16}>
        <Card title={selected ?? '选择左侧文件查看 tail（300 行）'} size="small">
          <pre style={{ maxHeight: 600, overflow: 'auto', fontSize: 12, whiteSpace: 'pre-wrap' }}>
            {lines.join('\n') || '（空）'}
          </pre>
        </Card>
      </Col>
    </Row>
  )
}

function ClientTelemetryViewer() {
  return <Tabs items={[
    { key: 'events', label: '事件检索', children: <TelemetryEventViewer /> },
    { key: 'devices', label: '客户端设备', children: <TelemetryDeviceViewer /> },
    { key: 'policies', label: '定向诊断', children: <TelemetryPolicyViewer /> },
  ]} />
}

export default function Logs() {
  return (
    <Tabs items={[
      { key: 'server', label: '服务端日志', children: <ServerLogViewer /> },
      { key: 'client', label: '客户端遥测', children: <ClientTelemetryViewer /> },
    ]} />
  )
}
