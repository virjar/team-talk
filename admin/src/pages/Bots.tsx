import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Descriptions, Drawer, Form, Input, Modal, Popconfirm,
  Space, Table, Tag, Typography, message,
} from 'antd'
import { CopyOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons'
import { api, errMsg } from '../api/client'

interface Bot {
  botId: string
  userUid: string
  name: string
  status: number
  grantedChatIds: string[]
  lastUsedAt?: number
  createdAt: number
}

interface CreatedBot { bot: Bot; webhookToken: string }

export default function Bots() {
  const [bots, setBots] = useState<Bot[]>([])
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [managedBotId, setManagedBotId] = useState<string>()
  const [revealed, setRevealed] = useState<CreatedBot>()
  const [form] = Form.useForm()
  const [grantForm] = Form.useForm()
  const managed = bots.find(bot => bot.botId === managedBotId)

  const load = useCallback(async () => {
    setLoading(true)
    try { const { data } = await api.get('/bots'); setBots(data) }
    catch (e) { message.error(errMsg(e)) } finally { setLoading(false) }
  }, [])
  useEffect(() => { load() }, [load])

  const createBot = async ({ name }: { name: string }) => {
    try {
      const { data } = await api.post('/bots', { name })
      setCreateOpen(false)
      form.resetFields()
      setRevealed(data)
      await load()
    } catch (e) { message.error(errMsg(e)) }
  }

  const rotateToken = async (botId: string) => {
    try { const { data } = await api.post(`/bots/${botId}/rotate-token`); setRevealed(data) }
    catch (e) { message.error(errMsg(e)) }
  }

  const disable = async (botId: string) => {
    try { await api.post(`/bots/${botId}/disable`); message.success('机器人已停用并撤销全部群授权'); await load() }
    catch (e) { message.error(errMsg(e)) }
  }

  const grant = async ({ chatId }: { chatId: string }) => {
    if (!managed) return
    try {
      await api.post(`/bots/${managed.botId}/grants`, { chatId: chatId.trim() })
      message.success('群授权已生效')
      grantForm.resetFields()
      await load()
    } catch (e) { message.error(errMsg(e)) }
  }

  const revoke = async (botId: string, chatId: string) => {
    try { await api.delete(`/bots/${botId}/grants/${chatId}`); message.success('群授权已撤销'); await load() }
    catch (e) { message.error(errMsg(e)) }
  }

  return (
    <div>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card>
          <Space style={{ width: '100%', justifyContent: 'space-between' }} align="start">
            <div>
              <Typography.Title level={3} style={{ margin: 0 }}>通知机器人</Typography.Title>
              <Typography.Text type="secondary">外部系统只能用独立凭据向明确授权的群发送 Markdown 通知，不能使用普通用户登录。</Typography.Text>
            </div>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建机器人</Button>
          </Space>
        </Card>

        <Table rowKey="botId" loading={loading} dataSource={bots} columns={[
          { title: '机器人', render: (_: unknown, bot: Bot) => <Space><RobotOutlined /><strong>{bot.name}</strong></Space> },
          { title: '状态', dataIndex: 'status', width: 90, render: status => status === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag> },
          { title: '群授权', dataIndex: 'grantedChatIds', width: 100, render: grants => `${grants.length} 个` },
          { title: '最近调用', dataIndex: 'lastUsedAt', render: value => value ? new Date(value).toLocaleString() : '从未调用' },
          { title: '操作', width: 300, render: (_: unknown, bot: Bot) => <Space>
            <Button size="small" onClick={() => setManagedBotId(bot.botId)}>管理授权</Button>
            {bot.status === 1 && <Popconfirm title="旧凭据会立即失效，继续？" onConfirm={() => rotateToken(bot.botId)}><Button size="small">轮换凭据</Button></Popconfirm>}
            {bot.status === 1 && <Popconfirm title="停用会撤销全部群授权，且不可恢复" onConfirm={() => disable(bot.botId)}><Button danger size="small">停用</Button></Popconfirm>}
          </Space> },
        ]} />
      </Space>

      <Modal title="创建通知机器人" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={() => form.submit()} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={createBot} preserve={false}>
          <Form.Item name="name" label="机器人名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input maxLength={100} placeholder="例如：发布通知机器人" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="保存机器人凭据" open={!!revealed} onCancel={() => setRevealed(undefined)} footer={<Button type="primary" onClick={() => setRevealed(undefined)}>我已安全保存</Button>}>
        <Alert type="warning" showIcon message="该凭据只显示一次" description="服务端只保存哈希，关闭后无法找回；遗失时请轮换凭据。" style={{ marginBottom: 16 }} />
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label="Bot ID"><Typography.Text copyable>{revealed?.bot.botId}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="Bearer Token"><Typography.Text code copyable={{ icon: <CopyOutlined /> }}>{revealed?.webhookToken}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="端点"><Typography.Text copyable>/api/v1/bots/{revealed?.bot.botId}/messages</Typography.Text></Descriptions.Item>
        </Descriptions>
      </Modal>

      <Drawer title={`群授权 · ${managed?.name ?? ''}`} width={560} open={!!managed} onClose={() => setManagedBotId(undefined)}>
        {managed && <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <Alert type="info" showIcon message="最小权限" description="机器人只有加入授权群后才能发送；撤权会同时把服务身份移出群。" />
          <Form form={grantForm} layout="inline" onFinish={grant} style={{ display: 'flex' }}>
            <Form.Item name="chatId" style={{ flex: 1 }} rules={[{ required: true, message: '请输入群 chatId' }]}>
              <Input placeholder="群 chatId" />
            </Form.Item>
            <Button htmlType="submit" type="primary">授权群</Button>
          </Form>
          <Table rowKey={value => value} size="small" pagination={false} dataSource={managed.grantedChatIds} columns={[
            { title: '已授权群 chatId', render: value => <Typography.Text copyable>{value}</Typography.Text> },
            { title: '操作', width: 90, render: value => <Popconfirm title="撤销该群授权？" onConfirm={() => revoke(managed.botId, value)}><Button type="link" danger>撤销</Button></Popconfirm> },
          ]} locale={{ emptyText: '尚未授权任何群' }} />
          <Card size="small" title="Webhook 请求契约">
            <Typography.Paragraph code style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{`POST /api/v1/bots/${managed.botId}/messages\nAuthorization: Bearer <token>\nContent-Type: application/json\n\n{"chatId":"<授权群>","markdown":"## 通知","idempotencyKey":"业务唯一键"}`}</Typography.Paragraph>
          </Card>
        </Space>}
      </Drawer>
    </div>
  )
}
