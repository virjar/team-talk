import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, message, Popconfirm, Space, Table, Tabs, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { api, clearAdminSession, errMsg } from '../api/client'

interface AdminSession {
  id: string
  createdAt: number
  expiresAt: number
  source: string
  current: boolean
}

interface SecurityStatus {
  username: string
  passwordUpdatedAt: number
  sessions: AdminSession[]
}

interface AuditRecord {
  id: number
  actor: string
  action: string
  target: string
  createdAt: number
  completedAt?: number
  result: 'STARTED' | 'SUCCESS' | 'REJECTED' | 'FAILED'
  httpStatus?: number
  failureReason?: string
}

const time = (value: number) => dayjs(value).format('YYYY-MM-DD HH:mm:ss')
const actions: Record<string, string> = {
  'admin.credentials.initialize': '初始化管理凭据', 'admin.credentials.recover': '恢复管理凭据',
  'admin.credentials.rotate': '管理密码已更改', 'admin.credentials.rotate-request': '更改管理密码',
  'admin.login': '管理员登录', 'admin.logout': '管理员登出',
  'admin.session.revoke': '吊销管理会话', 'admin.sessions.revoke-all': '吊销全部管理会话',
  'user.ban': '封禁用户', 'user.unban': '解除封禁', 'user.kick-all': '用户全部下线',
  'user.reset-password': '重置用户密码', 'user.document-custody-transfer': '交接文档资产',
  'organization.create': '创建组织', 'organization.update': '编辑组织', 'organization.archive': '归档组织',
  'organization.member.assign': '设置组织成员', 'organization.member.remove': '移除组织成员',
  'organization.group.enable': '启用部门群', 'organization.group.disable': '停用部门群',
  'organization.reconcile': '同步部门群', 'bot.create': '创建机器人', 'bot.rotate-token': '轮换机器人凭据',
  'bot.disable': '停用机器人', 'bot.grants': '授权机器人', 'bot.grant.revoke': '取消机器人授权',
  'message.revoke': '撤回消息', 'group.dissolve': '解散群组', 'group.mute-all': '全员禁言',
  'group.unmute-all': '解除全员禁言',
  'document.export.setting': '文档导出开关',
  'document.space.export': '导出文档空间',
}
const failureReasons: Record<string, string> = {
  INVALID_CREDENTIALS: '凭据不正确', RATE_LIMITED: '请求过于频繁', UNAUTHENTICATED: '管理会话已失效',
  FORBIDDEN: '没有操作权限', INVALID_REQUEST: '请求参数不合法', NOT_FOUND: '目标不存在',
  CONFLICT: '状态已变化或操作冲突', SERVICE_UNAVAILABLE: '服务暂不可用', INTERNAL_ERROR: '服务内部异常',
}
const results = {
  STARTED: { color: 'warning', label: '结果待确认' }, SUCCESS: { color: 'success', label: '成功' },
  REJECTED: { color: 'default', label: '请求被拒绝' }, FAILED: { color: 'error', label: '执行异常' },
}

export default function Security() {
  const [status, setStatus] = useState<SecurityStatus>()
  const [audits, setAudits] = useState<AuditRecord[]>([])
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [olderAvailable, setOlderAvailable] = useState(false)
  const [form] = Form.useForm()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [security, audit] = await Promise.all([
        api.get<SecurityStatus>('/security'), api.get<AuditRecord[]>('/security/audits'),
      ])
      setStatus(security.data)
      setAudits(audit.data)
      setOlderAvailable(audit.data.length === 50)
    } catch (error) { message.error(errMsg(error)) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { void refresh() }, [refresh])

  const revoke = async (session?: AdminSession) => {
    setBusy(true)
    try {
      await api.delete(session ? `/security/sessions/${encodeURIComponent(session.id)}` : '/security/sessions')
      if (!session || session.current) { clearAdminSession(); return }
      message.success('会话已吊销')
      await refresh()
    } catch (error) { message.error(errMsg(error)) }
    finally { setBusy(false) }
  }

  const rotate = async (values: { currentPassword: string, newPassword: string }) => {
    setBusy(true)
    try {
      await api.post('/security/password', { currentPassword: values.currentPassword, newPassword: values.newPassword })
      form.resetFields()
      clearAdminSession()
    } catch (error) { message.error(errMsg(error)) }
    finally { setBusy(false) }
  }

  const loadOlder = async () => {
    setLoading(true)
    try {
      const { data } = await api.get<AuditRecord[]>('/security/audits', { params: { beforeId: audits[audits.length - 1]?.id } })
      setAudits(previous => [...previous, ...data])
      setOlderAvailable(data.length === 50)
    } catch (error) { message.error(errMsg(error)) }
    finally { setLoading(false) }
  }

  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Space style={{ width: '100%', justifyContent: 'space-between' }}>
      <Typography.Title level={3} style={{ margin: 0 }}>管理安全</Typography.Title>
      <Button onClick={() => void refresh()} loading={loading}>刷新</Button>
    </Space>
    <Tabs items={[
      { key: 'account', label: '管理凭据与会话', children: <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Card title="更改管理密码" loading={!status && loading}>
          {status && <Typography.Paragraph>管理员：{status.username} · 密码更新时间：{time(status.passwordUpdatedAt)}</Typography.Paragraph>}
          <Alert type="info" showIcon message="更改密码后，所有管理会话会立即失效，请使用新密码重新登录。" style={{ marginBottom: 20 }} />
          <Form form={form} layout="vertical" onFinish={rotate} style={{ maxWidth: 480 }}>
            <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
              <Input.Password autoComplete="current-password" />
            </Form.Item>
            <Form.Item name="newPassword" label="新密码" rules={[
              { required: true, min: 6, message: '新密码至少 6 个字符' },
              { validator: (_, value) => !value || new TextEncoder().encode(value).length <= 72
                ? Promise.resolve() : Promise.reject(new Error('新密码不能超过 72 个 UTF-8 字节')) },
            ]}>
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Form.Item name="confirmPassword" label="确认新密码" dependencies={['newPassword']} rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({ validator: (_, value) => !value || getFieldValue('newPassword') === value
                ? Promise.resolve() : Promise.reject(new Error('两次输入的新密码不一致')) }),
            ]}>
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>更改密码并重新登录</Button>
          </Form>
        </Card>
        <Card title={`有效管理会话（${status?.sessions.length ?? 0}）`} extra={
          <Popconfirm title="吊销全部管理会话？" description="包括当前会话，随后需要重新登录。" onConfirm={() => revoke()}>
            <Button danger disabled={busy || !status}>吊销全部会话</Button>
          </Popconfirm>
        }>
          <Typography.Paragraph type="secondary">会话最长有效 12 小时，服务重启后失效。来源显示直接连接对端；经反向代理时通常显示代理地址。吊销阻止后续请求，不撤回已受理的操作。</Typography.Paragraph>
          <Table<AdminSession> rowKey="id" dataSource={status?.sessions ?? []} pagination={{ pageSize: 10 }} scroll={{ x: 850 }} columns={[
            { title: '会话', dataIndex: 'id', render: (id, row) => <Space><Typography.Text code>{id.slice(0, 8)}</Typography.Text>{row.current && <Tag color="blue">当前会话</Tag>}</Space> },
            { title: '连接来源', dataIndex: 'source' },
            { title: '登录时间', dataIndex: 'createdAt', render: time },
            { title: '到期时间', dataIndex: 'expiresAt', render: time },
            { title: '操作', render: (_, row) => <Popconfirm title={row.current ? '吊销当前会话并退出？' : '吊销此管理会话？'} onConfirm={() => revoke(row)}>
              <Button danger size="small" disabled={busy}>吊销</Button>
            </Popconfirm> },
          ]} />
        </Card>
      </Space> },
      { key: 'audits', label: '管理操作审计', children: <Card>
        <Typography.Paragraph type="secondary">保留最近 10,000 条记录。操作会先记入审计再执行；“结果待确认”表示尚在执行或执行被中断，需要核对实际业务状态。记录不包含密码、token 或请求正文。</Typography.Paragraph>
        <Table<AuditRecord> rowKey="id" dataSource={audits} pagination={false} loading={loading} scroll={{ x: 1000 }} columns={[
          { title: '时间', dataIndex: 'createdAt', render: time, width: 180 },
          { title: '管理员', dataIndex: 'actor', width: 130 },
          { title: '动作', dataIndex: 'action', render: value => actions[value] ?? value, width: 180 },
          { title: '目标', dataIndex: 'target', render: value => <Typography.Text style={{ overflowWrap: 'anywhere' }}>{value}</Typography.Text> },
          { title: '结果', dataIndex: 'result', width: 150, render: (value: AuditRecord['result'], row) => <Space><Tag color={results[value].color}>{results[value].label}</Tag>{row.httpStatus}</Space> },
          { title: '原因', dataIndex: 'failureReason', width: 190, render: value => value ? failureReasons[value] ?? value : '—' },
        ]} />
        {olderAvailable && <Button onClick={() => void loadOlder()} loading={loading} style={{ marginTop: 16 }}>加载更早记录</Button>}
      </Card> },
    ]} />
  </Space>
}
