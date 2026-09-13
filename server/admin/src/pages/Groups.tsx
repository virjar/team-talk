import { useCallback, useState } from 'react'
import { Button, Drawer, Descriptions, Input, Popconfirm, Space, Table, Tag, message } from 'antd'
import { api, errMsg } from '../api/client'
import { useRemoteQuery } from '../api/useRemoteQuery'

interface G { chatId: string; name?: string; memberCount: number; mutedAll: boolean }
interface MemberRow { uid: string; role: number; nickname?: string; user?: { name?: string } }

export default function Groups() {
  const [search, setSearch] = useState({ query: '', page: 1 })
  const [detailTarget, setDetailTarget] = useState<{ chatId: string } | null>(null)
  const groups = useRemoteQuery(useCallback(async (signal: AbortSignal) => {
    const response = await api.get<{ total: number; items: G[] }>('/groups', {
      params: { query: search.query || undefined, page: search.page, size: 20 }, signal,
    })
    return response.data
  }, [search]))
  const loadDetail = useCallback(async (signal: AbortSignal) =>
    (await api.get<{ chat: G; members: MemberRow[] }>(`/groups/${detailTarget!.chatId}`, { signal })).data, [detailTarget])
  const details = useRemoteQuery(detailTarget ? loadDetail : null)
  const data = groups.data ?? { total: 0, items: [] }
  const detail = details.data
  const changeSearch = (next: typeof search) => { groups.cancel(); setSearch(next) }
  const selectDetail = (next: typeof detailTarget) => { details.cancel(); setDetailTarget(next) }

  const act = async (chatId: string, op: string) => {
    try { await api.post(`/groups/${chatId}/${op}`); message.success(`${op} 成功`); void groups.reload(); void details.reload() }
    catch (e) { message.error(errMsg(e)) }
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search placeholder="群名" value={search.query} onChange={e => changeSearch({ query: e.target.value, page: 1 })}
          onSearch={() => changeSearch({ ...search, page: 1 })} style={{ width: 240 }} />
      </Space>
      <Table rowKey="chatId" loading={groups.loading} size="small"
        pagination={{ total: data.total, current: search.page, pageSize: 20, onChange: page => changeSearch({ ...search, page }) }}
        columns={[
          { title: '群名', dataIndex: 'name' },
          { title: 'chatId', dataIndex: 'chatId', ellipsis: true },
          { title: '成员数', dataIndex: 'memberCount', width: 80 },
          { title: '全员禁言', dataIndex: 'mutedAll', width: 90, render: (m: boolean) => m ? <Tag color="orange">是</Tag> : <Tag>否</Tag> },
          { title: '操作', width: 300, render: (_: any, g: G) => (
            <Space>
              <Button size="small" onClick={() => selectDetail({ chatId: g.chatId })}>成员</Button>
              {g.mutedAll
                ? <Button size="small" onClick={() => act(g.chatId, 'unmute-all')}>解除全员禁言</Button>
                : <Button size="small" onClick={() => act(g.chatId, 'mute-all')}>全员禁言</Button>}
              <Popconfirm title="解散群将通知全部成员且不可恢复" onConfirm={() => act(g.chatId, 'dissolve')}>
                <Button size="small" danger>解散</Button>
              </Popconfirm>
            </Space>) },
        ]}
        dataSource={data.items} />
      <Drawer open={!!detailTarget} loading={details.loading} onClose={() => selectDetail(null)} width={480} title={`群成员（${detail?.chat.name ?? ''}）`}>
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
