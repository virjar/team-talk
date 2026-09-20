import { useCallback, useEffect, useState } from 'react'
import { Card, Input, message, Space, Spin, Table, Typography, Button } from 'antd'
import { api, errMsg } from '../api/client'

// 服务号官方触达：欢迎语模板（新用户首次拉起服务号会话时送达）与全员广播。
// 广播按稳定身份对已送达用户幂等：重复推送只补齐未覆盖的用户，不产生重复消息。

interface BroadcastRecord {
  broadcastId: string
  markdown: string
  createdBy: string
  createdAt: number
  finishedAt: number | null
  totalUsers: number
  coveredUsers: number
  failedUsers: number
  lastError: string | null
}

export default function Service() {
  const [template, setTemplate] = useState('')
  const [templateLoading, setTemplateLoading] = useState(true)
  const [templateSaving, setTemplateSaving] = useState(false)
  const [templateDraft, setTemplateDraft] = useState('')
  const [broadcastDraft, setBroadcastDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [broadcasts, setBroadcasts] = useState<BroadcastRecord[]>([])
  const [broadcastsLoading, setBroadcastsLoading] = useState(true)

  const loadTemplate = useCallback(async () => {
    setTemplateLoading(true)
    try {
      const resp = await api.get('/service/welcome')
      setTemplate(resp.data?.template ?? '')
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setTemplateLoading(false)
    }
  }, [])

  const loadBroadcasts = useCallback(async () => {
    setBroadcastsLoading(true)
    try {
      const resp = await api.get('/service/broadcasts')
      setBroadcasts(resp.data?.broadcasts ?? [])
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setBroadcastsLoading(false)
    }
  }, [])

  useEffect(() => {
    loadTemplate()
    loadBroadcasts()
  }, [loadTemplate, loadBroadcasts])

  const saveTemplate = async () => {
    setTemplateSaving(true)
    try {
      const resp = await api.put('/service/welcome', { content: templateDraft })
      setTemplate(resp.data?.template ?? '')
      message.success('欢迎语模板已保存')
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setTemplateSaving(false)
    }
  }

  const sendBroadcast = async () => {
    setSending(true)
    try {
      await api.post('/service/broadcasts', { markdown: broadcastDraft })
      setBroadcastDraft('')
      message.success('广播已发起，正在后台推送给全部用户')
      setTimeout(loadBroadcasts, 1_000)
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setSending(false)
    }
  }

  const reissue = async (broadcastId: string) => {
    try {
      await api.post(`/service/broadcasts/${broadcastId}/reissue`)
      message.success('已重新推送（已送达用户自动跳过）')
      setTimeout(loadBroadcasts, 1_000)
    } catch (error) {
      message.error(errMsg(error))
    }
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="欢迎语模板" style={{ maxWidth: 720 }}>
        {templateLoading ? <Spin /> : (
          <>
            <Typography.Paragraph type="secondary">
              用户首次打开服务号会话时，服务号以官方身份发送以下内容；发送失败会在用户下次进入会话时自动补发。
              修改模板只影响之后首次收到欢迎语的用户，不会向已有用户重发。
            </Typography.Paragraph>
            <Input.TextArea
              rows={6}
              defaultValue={template}
              onChange={(e) => setTemplateDraft(e.target.value)}
              data-testid="service.welcome.input"
            />
            <Button
              style={{ marginTop: 12 }}
              type="primary"
              loading={templateSaving}
              onClick={saveTemplate}
              disabled={!templateDraft.trim()}
              data-testid="service.welcome.save"
            >
              保存模板
            </Button>
          </>
        )}
      </Card>

      <Card title="全员广播" style={{ maxWidth: 720 }}>
        <Typography.Paragraph type="secondary">
          以服务号官方身份向全部用户的服务号会话推送消息；离线用户在下次登录同步时收到。
          推送在后台执行，可在下方台账查看进度；中断后可重新推送，已送达用户不会收到重复消息。
        </Typography.Paragraph>
        <Input.TextArea
          rows={5}
          value={broadcastDraft}
          onChange={(e) => setBroadcastDraft(e.target.value)}
          placeholder="输入要广播给全体用户的消息（支持 Markdown）"
          data-testid="service.broadcast.input"
        />
        <Button
          style={{ marginTop: 12 }}
          type="primary"
          loading={sending}
          onClick={sendBroadcast}
          disabled={!broadcastDraft.trim()}
          data-testid="service.broadcast.send"
        >
          推送给全部用户
        </Button>
      </Card>

      <Card title="广播台账" style={{ maxWidth: 960 }}>
        <Table<BroadcastRecord>
          rowKey="broadcastId"
          size="small"
          loading={broadcastsLoading}
          dataSource={broadcasts}
          data-testid="service.broadcasts.table"
          columns={[
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 170,
              render: (v: number) => new Date(v).toLocaleString(),
            },
            {
              title: '内容',
              dataIndex: 'markdown',
              ellipsis: true,
              render: (v: string) => <Typography.Text style={{ maxWidth: 320 }}>{v.split('\n')[0]}</Typography.Text>,
            },
            {
              title: '覆盖',
              width: 120,
              render: (_, r) => `${r.coveredUsers}/${r.totalUsers}${r.failedUsers ? `（失败 ${r.failedUsers}）` : ''}`,
            },
            {
              title: '状态',
              dataIndex: 'finishedAt',
              width: 90,
              render: (v: number | null) => (v == null ? '进行中' : '已完成'),
            },
            {
              title: '操作',
              width: 110,
              render: (_, r) => (
                <Button size="small" onClick={() => reissue(r.broadcastId)} data-testid={`service.broadcast.reissue.${r.broadcastId}`}>
                  重新推送
                </Button>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  )
}
