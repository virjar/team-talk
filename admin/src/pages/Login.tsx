import { useState } from 'react'
import { Card, Form, Input, Button, Typography, message } from 'antd'
import { api, errMsg, TOKEN_KEY } from '../api/client'

export default function Login() {
  const [loading, setLoading] = useState(false)
  const onFinish = async (v: { username: string; password: string }) => {
    setLoading(true)
    try {
      const { data } = await api.post('/login', v)
      localStorage.setItem(TOKEN_KEY, data.token)
      window.location.reload()
    } catch (e) { message.error(errMsg(e)) } finally { setLoading(false) }
  }
  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card style={{ width: 360 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>TeamTalk 管理后台</Typography.Title>
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item name="username" label="账号" rules={[{ required: true }]}>
            <Input placeholder="ADMIN_USER（默认 admin）" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true }]}>
            <Input.Password placeholder="ADMIN_PASSWORD" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>登录</Button>
        </Form>
      </Card>
    </div>
  )
}
