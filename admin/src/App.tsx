import { BrowserRouter, Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Layout, Menu, Button, Space, Typography } from 'antd'
import {
  DashboardOutlined, UserOutlined, MessageOutlined, FileTextOutlined, TeamOutlined, LogoutOutlined,
} from '@ant-design/icons'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Users from './pages/Users'
import Messages from './pages/Messages'
import Logs from './pages/Logs'
import Groups from './pages/Groups'
import { TOKEN_KEY } from './api/client'

const { Header, Sider, Content } = Layout

function Shell() {
  const loc = useLocation()
  const navigate = useNavigate()
  // BrowserRouter(basename=/admin) 的 useLocation 已剥 basename：pathname 即 '/users' 等
  const selected = loc.pathname === '/' ? '/dashboard' : loc.pathname
  const logout = () => { localStorage.removeItem(TOKEN_KEY); window.location.href = '/admin/' }
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark">
        <div style={{ color: '#fff', padding: 16, fontSize: 16, fontWeight: 600 }}>TeamTalk 运维</div>
        <Menu theme="dark" selectedKeys={[selected]} items={[
          { key: '/dashboard', icon: <DashboardOutlined />, label: 'Dashboard' },
          { key: '/users', icon: <UserOutlined />, label: '用户' },
          { key: '/messages', icon: <MessageOutlined />, label: '消息' },
          { key: '/logs', icon: <FileTextOutlined />, label: '日志' },
          { key: '/groups', icon: <TeamOutlined />, label: '群组' },
        ]} onClick={({ key }) => navigate(key)} />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
          <Space>
            <Typography.Text type="secondary">TeamTalk Admin</Typography.Text>
            <Button icon={<LogoutOutlined />} onClick={logout}>登出</Button>
          </Space>
        </Header>
        <Content style={{ margin: 16 }}>
          <Routes>
            <Route path="/" element={<Navigate to="dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="users" element={<Users />} />
            <Route path="messages" element={<Messages />} />
            <Route path="logs" element={<Logs />} />
            <Route path="groups" element={<Groups />} />
            <Route path="*" element={<Navigate to="dashboard" replace />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  )
}

export default function App() {
  const logged = !!localStorage.getItem(TOKEN_KEY)
  return (
    <BrowserRouter basename="/admin">
      {logged ? <Shell /> : <Login />}
    </BrowserRouter>
  )
}
