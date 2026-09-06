import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Col, Form, Input, InputNumber, Modal, Row, Space, Table, Tag, Typography, message } from 'antd'
import { api, errMsg } from '../../api/client'
import { AdminPage, exactTime, shortIdentity, TELEMETRY_PAGE_SIZE, TelemetryPolicyItem } from './TelemetryModels'

interface EnablePolicyForm {
  uid?: string
  phone?: string
  deviceId?: string
  durationMinutes: number
  reason: string
}

function isFormValidationError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'errorFields' in error
}

export function TelemetryPolicyViewer() {
  const [result, setResult] = useState<AdminPage<TelemetryPolicyItem>>({ total: 0, items: [] })
  const [loading, setLoading] = useState(false)
  const [listError, setListError] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  const [page, setPage] = useState(1)
  const [enabling, setEnabling] = useState(false)
  const [disablingPolicyId, setDisablingPolicyId] = useState<string | null>(null)
  const requestGeneration = useRef(0)
  const requestedPage = useRef(1)
  const [form] = Form.useForm<EnablePolicyForm>()
  const load = useCallback(async (nextPage = page) => {
    const generation = ++requestGeneration.current
    requestedPage.current = nextPage
    setLoading(true)
    setListError(null)
    try {
      const { data } = await api.get<AdminPage<TelemetryPolicyItem>>('/telemetry/policies', { params: { page: nextPage, size: TELEMETRY_PAGE_SIZE } })
      if (generation !== requestGeneration.current) return
      setResult(data)
      setPage(nextPage)
    } catch (e) {
      if (generation === requestGeneration.current) setListError(errMsg(e))
    } finally {
      if (generation === requestGeneration.current) setLoading(false)
    }
  }, [page])
  useEffect(() => { void load(1) }, [])
  const enable = async () => {
    try {
      const values = await form.validateFields()
      setEnabling(true)
      await api.post('/telemetry/policies', {
        ...values,
        uid: values.uid?.trim() || undefined,
        phone: values.phone?.trim() || undefined,
        deviceId: values.deviceId?.trim() || undefined,
        reason: values.reason.trim(),
      })
      message.success('诊断采集已开启，客户端下次心跳生效')
      setOpen(false)
      form.resetFields()
      await load(1)
    } catch (e) {
      if (isFormValidationError(e)) return
      message.error(errMsg(e))
    } finally {
      setEnabling(false)
    }
  }
  const disable = async (policyId: string) => {
    setDisablingPolicyId(policyId)
    try {
      await api.delete(`/telemetry/policies/${encodeURIComponent(policyId)}`)
      message.success('诊断采集已关闭')
      await load(result.items.length === 1 && page > 1 ? page - 1 : page)
    } catch (e) {
      message.error(errMsg(e))
    } finally {
      setDisablingPolicyId(null)
    }
  }
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space>
        <Button type="primary" onClick={() => setOpen(true)}>开启定向诊断</Button>
        <Button loading={loading} onClick={() => load(page)}>刷新</Button>
        <Typography.Text type="secondary">诊断模式最长 24 小时，自动过期；仍不采集输入正文、消息内容、文件名、路径或凭据。</Typography.Text>
      </Space>
      {listError && <Alert
        showIcon
        type="error"
        message="诊断策略加载失败"
        description={listError}
        action={<Button size="small" onClick={() => load(requestedPage.current)}>重试</Button>}
      />}
      <Table<TelemetryPolicyItem>
        rowKey="policyId"
        size="small"
        loading={loading}
        pagination={{ current: page, pageSize: TELEMETRY_PAGE_SIZE, total: result.total, showSizeChanger: false, showTotal: total => `共 ${total} 条`, onChange: next => load(next) }}
        dataSource={result.items}
        columns={[
          { title: 'UID', dataIndex: 'uid', width: 180, render: value => <Typography.Text copyable>{shortIdentity(value)}</Typography.Text> },
          { title: '设备', dataIndex: 'deviceId', width: 180, render: value => value ? <Typography.Text copyable>{shortIdentity(value)}</Typography.Text> : '该用户全部设备' },
          { title: '模式', dataIndex: 'mode', width: 110, render: value => <Tag color={value === 'DIAGNOSTIC' ? 'orange' : 'default'}>{value}</Tag> },
          { title: '原因', dataIndex: 'reason', ellipsis: true, render: value => value || '—' },
          { title: '到期', dataIndex: 'expiresAt', width: 165, render: exactTime },
          { title: '操作者', dataIndex: 'updatedBy', width: 110 },
          { title: '状态', dataIndex: 'active', width: 90, render: value => value ? <Tag color="green">生效中</Tag> : <Tag>已结束</Tag> },
          { title: '操作', width: 90, render: (_, row) => row.active ? <Button danger type="link" loading={disablingPolicyId === row.policyId} disabled={disablingPolicyId !== null && disablingPolicyId !== row.policyId} onClick={() => disable(row.policyId)}>关闭</Button> : null },
        ]}
        expandable={{
          expandedRowRender: row => (
            <Space direction="vertical" size={2}>
              <Typography.Text>策略 ID：<Typography.Text copyable>{row.policyId}</Typography.Text></Typography.Text>
              <Typography.Text>revision：{row.revision} · 最后更新：{exactTime(row.updatedAt)}</Typography.Text>
            </Space>
          ),
        }}
      />
      <Modal
        title="开启定向诊断采集"
        open={open}
        confirmLoading={enabling}
        onOk={enable}
        onCancel={() => { setOpen(false); form.resetFields() }}
        okText="开启"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" initialValues={{ durationMinutes: 120 }}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item
                name="uid"
                label="UID"
                dependencies={['phone']}
                rules={[
                  { max: 36, message: 'UID 最多 36 个字符' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      const hasUid = Boolean(String(value ?? '').trim())
                      const hasPhone = Boolean(String(getFieldValue('phone') ?? '').trim())
                      return hasUid !== hasPhone
                        ? Promise.resolve()
                        : Promise.reject(new Error('UID 与手机号必须且只能填写一项'))
                    },
                  }),
                ]}
              >
                <Input maxLength={36} placeholder="UID 或填写右侧手机号" />
              </Form.Item>
            </Col>
            <Col span={12}><Form.Item name="phone" label="手机号" rules={[{ max: 20, message: '手机号最多 20 个字符' }]}><Input maxLength={20} placeholder="仅用于服务端解析 UID" /></Form.Item></Col>
          </Row>
          <Form.Item name="deviceId" label="设备 ID（可选）" rules={[{ max: 100, message: '设备 ID 最多 100 个字符' }]}><Input maxLength={100} placeholder="留空表示该用户全部设备" /></Form.Item>
          <Form.Item name="durationMinutes" label="有效分钟数" rules={[{ required: true }, { type: 'number', min: 1, max: 1440 }]}><InputNumber min={1} max={1440} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="reason" label="开启原因 / 工单" rules={[{ required: true, whitespace: true, max: 500 }]}><Input.TextArea maxLength={500} showCount /></Form.Item>
          <Typography.Text type="secondary">UID 与手机号二选一。服务端只保存解析后的 UID；设备目标始终绑定该 UID，deviceId 本身不是身份凭据。</Typography.Text>
        </Form>
      </Modal>
    </Space>
  )
}
