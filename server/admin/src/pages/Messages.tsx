import { useState } from 'react'
import { Button, Form, Input, Modal, Space, Table, Tag, message } from 'antd'
import { api, errMsg } from '../api/client'
import dayjs from 'dayjs'

interface Msg { chatId: string; clientMsgId: string; serverSeq: number; senderUid: string; messageType: number; timestamp: number; flags: number; body?: any }
interface SearchResult { total: number; items: Msg[]; highlights: Record<string, string> }

const bodyText = (m: Msg) => m.body?.text ?? `[${m.body?.constructor?.name ?? m.messageType}]`
const messageIdentity = (m: Msg) => `${m.chatId}:${m.serverSeq}`

export default function Messages() {
  const [form] = Form.useForm()
  const [data, setData] = useState<SearchResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [ctx, setCtx] = useState<{ chatId: string; seq: number; items: Msg[] } | null>(null)

  const search = async (p = 1) => {
    const v = form.getFieldsValue()
    setLoading(true); setPage(p)
    try {
      const { data } = await api.get<SearchResult>('/messages', { params: {
        keyword: v.keyword || undefined, chatId: v.chatId || undefined, senderUid: v.senderUid || undefined, page: p, size: 20 } })
      setData(data)
    } catch (e) { message.error(errMsg(e)) } finally { setLoading(false) }
  }

  const showContext = async (chatId: string, seq: number) => {
    try {
      const { data: items } = await api.get<Msg[]>(`/messages/${chatId}/${seq}/context`)
      setCtx({ chatId, seq, items })
    } catch (e) { message.error(errMsg(e)) }
  }

  const revoke = async (chatId: string, seq: number) => {
    try {
      await api.post(`/messages/${chatId}/${seq}/revoke`)
      message.success('已撤回并广播')
      search(page)
    } catch (e) { message.error(errMsg(e)) }
  }

  return (
    <div>
      <Form form={form} layout="inline" style={{ marginBottom: 16 }}
        onFinish={() => search(1)}>
        <Form.Item name="keyword"><Input placeholder="关键词" style={{ width: 180 }} /></Form.Item>
        <Form.Item name="chatId"><Input placeholder="会话 ID" style={{ width: 220 }} /></Form.Item>
        <Form.Item name="senderUid"><Input placeholder="发送者 UID" style={{ width: 140 }} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>搜索</Button>
      </Form>
      <Table rowKey={messageIdentity} size="small" loading={loading}
        pagination={{ total: data?.total ?? 0, current: page, pageSize: 20, onChange: p => search(p) }}
        columns={[
          { title: '时间', dataIndex: 'timestamp', width: 150, render: (t: number) => dayjs(t).format('MM-DD HH:mm:ss') },
          { title: '会话', dataIndex: 'chatId', width: 150, ellipsis: true },
          { title: '发送者', dataIndex: 'senderUid', width: 100 },
          { title: '内容', width: 350, ellipsis: true, render: (_: any, m: Msg) =>
            data?.highlights[messageIdentity(m)] ?? bodyText(m) },
          { title: '标记', dataIndex: 'flags', width: 90, render: (f: number) => (
            <Space size={4}>
              {(f & 1) > 0 && <Tag color="orange">撤回</Tag>}
              {(f & 2) > 0 && <Tag>编辑</Tag>}
            </Space>) },
          { title: '操作', width: 150, render: (_: any, m: Msg) => (
            <Space>
              <Button size="small" onClick={() => showContext(m.chatId, m.serverSeq)}>上下文</Button>
              {(m.flags & 1) === 0 && <Button size="small" danger onClick={() => revoke(m.chatId, m.serverSeq)}>撤回</Button>}
            </Space>) },
        ]}
        dataSource={data?.items ?? []} />
      <Modal open={!!ctx} onCancel={() => setCtx(null)} width={640} footer={null}
        title={`消息上下文（${ctx?.chatId.slice(0, 12)}… #${ctx?.seq}）`}>
        <div style={{ maxHeight: 480, overflow: 'auto' }}>
          {ctx?.items.map(m => (
            <div key={m.clientMsgId} style={{
              padding: '4px 8px', background: m.serverSeq === ctx.seq ? '#e6f4ff' : undefined,
              borderRadius: 4, marginBottom: 2, fontSize: 13 }}>
              <span style={{ color: '#888' }}>{dayjs(m.timestamp).format('HH:mm:ss')} {m.senderUid.slice(6)}: </span>
              {bodyText(m)} {(m.flags & 1) > 0 && <Tag style={{ marginLeft: 4 }}>已撤回</Tag>}
            </div>
          ))}
        </div>
      </Modal>
    </div>
  )
}
