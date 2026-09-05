import { useCallback, useEffect, useState } from 'react'
import { Button, Drawer, Descriptions, Input, Popconfirm, Space, Table, Tag, message } from 'antd'
import { api, errMsg } from '../api/client'

interface G { chatId: string; name?: string; memberCount: number; mutedAll: boolean }
interface MemberRow { uid: string; role: number; nickname?: string; user?: { name?: string } }

export default function Groups() {
  const [data, setData] = useState<{ total: number; items: G[] }>({ total: 0, items: [] })
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<{ chat: G; members: MemberRow[] } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const { data } = await api.get('/groups', { params: { query: query || undefined, page, size: 20 } })
      setData(data)
    } catch (e) { message.error(errMsg(e)) } finally { setLoading(false) }
  }, [query, page])
  useEffect(() => { load() }, [load])

  const act = async (chatId: string, op: string) => {
    try { await api.post(`/groups/${chatId}/${op}`); message.success(`${op} 成功`); load() }
    catch (e) { message.error(errMsg(e)) }
  }
  const openDetail = async (chatId: string) => {
    try { const { data } = await api.get(`/groups/${chatId}`); setDetail(data) }
    catch (e) { message.error(errMsg(e)) }
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search placeholder="群名" value={query} onChange={e => setQuery(e.target.value)}
          onSearch={() => { setPage(1); load() }} style={{ width: 240 }} />
      </Space>
      <Table rowKey="chatId" loading={loading} size="small"
        pagination={{ total: data.total, current: page, pageSize: 20, onChange: setPage }}
        columns={[
          { title: '群名', dataIndex: 'name' },
          { title: 'chatId', dataIndex: 'chatId', ellipsis: true },
          { title: '成员数', dataIndex: 'memberCount', width: 80 },
          { title: '全员禁言', dataIndex: 'mutedAll', width: 90, render: (m: boolean) => m ? <Tag color="orange">是</Tag> : <Tag>否</Tag> },
          { title: '操作', width: 300, render: (_: any, g: G) => (
            <Space>
              <Button size="small" onClick={() => openDetail(g.chatId)}>成员</Button>
              {g.mutedAll
                ? <Button size="small" onClick={() => act(g.chatId, 'unmute-all')}>解除全员禁言</Button>
                : <Button size="small" onClick={() => act(g.chatId, 'mute-all')}>全员禁言</Button>}
              <Popconfirm title="解散群将通知全部成员且不可恢复" onConfirm={() => act(g.chatId, 'dissolve')}>
                <Button size="small" danger>解散</Button>
              </Popconfirm>
            </Space>) },
        ]}
        dataSource={data.items} />
      <Drawer open={!!detail} onClose={() => setDetail(null)} width={480} title={`群成员（${detail?.chat.name ?? ''}）`}>
        <Table rowKey="uid" size="small" pagination={false}
          columns={[
            { title: '成员', render: (_: any, m: MemberRow) => m.user?.name ?? m.nickname ?? m.uid },
            { title: '角色', dataIndex: 'role', width: 80, render: (r: number) =>
              r === 2 ? <Tag color="gold">群主</Tag> : r === 1 ? <Tag color="blue">管理</Tag> : <Tag>成员</Tag> },
          ]}
          dataSource={detail?.members ?? []} />
      </Drawer>
    </div>
  )
}
