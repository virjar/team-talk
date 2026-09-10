import { useCallback, useEffect, useState } from 'react'
import { Card, message, Space, Spin, Switch, Typography } from 'antd'
import { api, errMsg } from '../api/client'

// 文档空间导出（T035）：唯一后台开关；开启后空间责任人（steward）可导出，
// 超级管理员的 /api/admin/documents/spaces/{id}/export 入口始终可用。
export default function Settings() {
  const [enabled, setEnabled] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const resp = await api.get('/settings/document-export')
      setEnabled(resp.data?.enabled === true)
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const toggle = async (next: boolean) => {
    setSaving(true)
    try {
      const resp = await api.put('/settings/document-export', { enabled: next })
      setEnabled(resp.data?.enabled === true)
      message.success(next ? '已允许文档空间导出' : '已禁止文档空间导出')
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card title="文档空间导出" style={{ maxWidth: 640 }}>
      {loading ? <Spin /> : (
        <Space size="middle">
          <Switch checked={enabled} loading={saving} onChange={toggle} data-testid="settings.export.toggle" />
          <Typography.Text>允许将文档空间导出为 Markdown 压缩包</Typography.Text>
        </Space>
      )}
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        开启后，每个空间的责任人（唯一管理员）可在客户端导出本空间的全部文档与附件；
        超级管理员可通过管理 API 导出任意空间。关闭立即生效，已下发的导出包不受影响。
      </Typography.Paragraph>
    </Card>
  )
}
