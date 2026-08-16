import { useEffect, useState } from 'react'
import { Card, Col, Row, Select, Table, Tabs, Tree, Typography, message } from 'antd'
import { api, errMsg } from '../api/client'
import dayjs from 'dayjs'

interface FileInfo { name: string; sizeBytes: number; lastModified: number }

function ServerLogViewer() {
  const [files, setFiles] = useState<FileInfo[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [lines, setLines] = useState<string[]>([])
  useEffect(() => { api.get<FileInfo[]>('/logs/server').then(r => setFiles(r.data)).catch(e => message.error(errMsg(e))) }, [])
  const open = async (name: string) => {
    setSelected(name)
    try { const { data } = await api.get(`/logs/server/${encodeURIComponent(name)}`, { params: { lines: 300 } }); setLines(data.lines) }
    catch (e) { message.error(errMsg(e)) }
  }
  return (
    <Row gutter={16}>
      <Col span={8}>
        <Table rowKey="name" size="small" pagination={false}
          onRow={(r) => ({ onClick: () => open(r.name), style: { cursor: 'pointer', background: r.name === selected ? '#e6f4ff' : undefined } })}
          columns={[
            { title: '文件', dataIndex: 'name', ellipsis: true },
            { title: '大小', dataIndex: 'sizeBytes', width: 80, render: (s: number) => `${(s / 1024).toFixed(0)}K` },
            { title: '修改', dataIndex: 'lastModified', width: 90, render: (t: number) => dayjs(t).format('MM-DD HH:mm') },
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

function ClientLogViewer() {
  const [tree, setTree] = useState<Record<string, Record<string, string[]>>>({})
  const [content, setContent] = useState<{ title: string; lines: string[] } | null>(null)
  useEffect(() => { api.get('/logs/client').then(r => setTree(r.data)).catch(e => message.error(errMsg(e))) }, [])
  const open = async (uid: string, deviceId: string, date: string) => {
    try {
      const { data } = await api.get('/logs/client/content', { params: { uid, deviceId, date } })
      setContent({ title: `${uid}/${deviceId}/${date}`, lines: data.lines })
    } catch (e) { message.error(errMsg(e)) }
  }
  const treeData = Object.entries(tree).map(([uid, devices]) => ({
    title: uid, key: uid,
    children: Object.entries(devices).map(([dev, dates]) => ({
      title: dev, key: `${uid}/${dev}`,
      children: dates.map(d => ({ title: d, key: `${uid}/${dev}/${d}`, isLeaf: true })),
    })),
  }))
  return (
    <Row gutter={16}>
      <Col span={8}>
        <Typography.Text type="secondary">uid / 设备 / 日期</Typography.Text>
        <Tree treeData={treeData} onSelect={(keys) => {
          const parts = String(keys[0] ?? '').split('/')
          if (parts.length === 3) open(parts[0], parts[1], parts[2])
        }} />
      </Col>
      <Col span={16}>
        <Card title={content?.title ?? '选择日志文件'} size="small">
          <pre style={{ maxHeight: 600, overflow: 'auto', fontSize: 12, whiteSpace: 'pre-wrap' }}>
            {content?.lines.join('\n') ?? '（空）'}
          </pre>
        </Card>
      </Col>
    </Row>
  )
}

export default function Logs() {
  return (
    <Tabs items={[
      { key: 'server', label: '服务端日志', children: <ServerLogViewer /> },
      { key: 'client', label: '客户端日志', children: <ClientLogViewer /> },
    ]} />
  )
}
