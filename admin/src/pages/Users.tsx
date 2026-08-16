import { useCallback, useEffect, useState } from 'react'
import { Button, Drawer, Descriptions, Input, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, message } from 'antd'
import { api, errMsg } from '../api/client'

interface U { uid: string; username: string; name: string; phone?: string; status: number; createdAt?: number }
interface Detail { user: U; devices: any[]; friends: any[]; groups: any[]; online: boolean }

export default function Users() {
  const [data, setData] = useState<{ total: number; items: U[] }>({ total: 0, items: [] })
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [resetUid, setResetUid] = useState<string | null>(null)
  const [newPwd, setNewPwd] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const { data } = await api.get('/users', { params: { query: query || undefined, page, size: 20 } })
      setData(data)
    } catch (e) { message.error(errMsg(e)) } finally { setLoading(false) }
  }, [query, page])
  useEffect(() => { load() }, [load])

  const act = async (uid: string, op: string, body?: object) => {
    try {
      await api.post(`/users/${uid}/${op}`, body ?? {})
      message.success(`${op} 成功`)
      load()
      if (detail?.user.uid === uid) setDetail(await (await api.get(`/users/${uid}`)).data)
    } catch (e) { message.error(errMsg(e)) }
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search placeholder="用户名/昵称/UID" value={query}
          onChange={e => setQuery(e.target.value)} onSearch={() => { setPage(1); load() }} style={{ width: 260 }} />
      </Space>
      <Table rowKey="uid" loading={loading}
        pagination={{ total: data.total, current: page, pageSize: 20, onChange: setPage }}
        columns={[
          { title: 'UID', dataIndex: 'uid', width: 120 },
          { title: '用户名', dataIndex: 'username' },
          { title: '昵称', dataIndex: 'name' },
          { title: '手机', dataIndex: 'phone' },
          { title: '状态', dataIndex: 'status', width: 80, render: (s: number) =>
            s === 2 ? <Tag color="red">封禁</Tag> : <Tag color="green">正常</Tag> },
          { title: '操作', width: 320, render: (_: any, u: U) => (
            <Space>
              <Button size="small" onClick={async () => setDetail((await api.get(`/users/${u.uid}`)).data)}>详情</Button>
              {u.status === 2
                ? <Popconfirm title="解封该用户？" onConfirm={() => act(u.uid, 'unban')}><Button size="small">解封</Button></Popconfirm>
                : <Popconfirm title="封禁将踢全部设备并吊销 token" onConfirm={() => act(u.uid, 'ban')}><Button size="small" danger>封禁</Button></Popconfirm>}
              <Popconfirm title="踢全部在线设备？" onConfirm={() => act(u.uid, 'kick-all')}><Button size="small">踢线</Button></Popconfirm>
              <Button size="small" onClick={() => { setResetUid(u.uid); setNewPwd('') }}>重置密码</Button>
            </Space>) },
        ]}
        dataSource={data.items} />
      <Drawer open={!!detail} onClose={() => setDetail(null)} width={560} title="用户详情">
        {detail && (
          <Tabs items={[
            { key: 'base', label: '基本', children: (
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label="UID">{detail.user.uid}</Descriptions.Item>
                <Descriptions.Item label="用户名">{detail.user.username}</Descriptions.Item>
                <Descriptions.Item label="昵称">{detail.user.name}</Descriptions.Item>
                <Descriptions.Item label="状态">{detail.user.status === 2 ? '封禁' : '正常'}</Descriptions.Item>
                <Descriptions.Item label="在线">{detail.online ? '是' : '否'}</Descriptions.Item>
              </Descriptions>) },
            { key: 'devices', label: `设备(${detail.devices.length})`, children: (
              <Table rowKey="deviceId" size="small" pagination={false}
                columns={[{ title: '设备', dataIndex: 'deviceName' }, { title: 'ID', dataIndex: 'deviceId' }, { title: '最后登录', dataIndex: 'lastLogin' }]}
                dataSource={detail.devices} />) },
            { key: 'friends', label: `好友(${detail.friends.length})`, children: (
              <Table rowKey="friendUid" size="small" pagination={false}
                columns={[{ title: 'UID', dataIndex: 'friendUid' }, { title: '备注', dataIndex: 'remark' }]}
                dataSource={detail.friends} />) },
            { key: 'groups', label: `群(${detail.groups.length})`, children: (
              <Table rowKey="chatId" size="small" pagination={false}
                columns={[{ title: '群名', dataIndex: 'name' }, { title: 'chatId', dataIndex: 'chatId' }]}
                dataSource={detail.groups} />) },
          ]} />
        )}
      </Drawer>
      <Modal open={!!resetUid} title="重置密码" onCancel={() => setResetUid(null)}
        onOk={async () => { if (resetUid) { await act(resetUid, 'reset-password', { password: newPwd }); setResetUid(null) } }}>
        <Input.Password placeholder="新密码（≥6位，重置后全设备踢线）" value={newPwd} onChange={e => setNewPwd(e.target.value)} />
      </Modal>
    </div>
  )
}
