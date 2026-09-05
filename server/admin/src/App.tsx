import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Layout, Menu, Button, Space, Spin, Typography } from 'antd'
import {
  ApartmentOutlined, DashboardOutlined, UserOutlined, MessageOutlined, FileTextOutlined,
  TeamOutlined, LogoutOutlined, RobotOutlined,
} from '@ant-design/icons'
import { TOKEN_KEY } from './api/client'

// Route-level boundaries keep the public login and each operations surface out of the initial
// bundle. This also prevents a rarely used diagnostics page from delaying every Admin startup.
const Login = lazy(() => import('./pages/Login'))
const Dashboard = lazy(() => import('./pages/Dashboard'))
const Users = lazy(() => import('./pages/Users'))
const Messages = lazy(() => import('./pages/Messages'))
const Logs = lazy(() => import('./pages/Logs'))
const Groups = lazy(() => import('./pages/Groups'))
const Organization = lazy(() => import('./pages/Organization'))
const Bots = lazy(() => import('./pages/Bots'))

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
          { key: '/organization', icon: <ApartmentOutlined />, label: '组织架构' },
          { key: '/bots', icon: <RobotOutlined />, label: '通知机器人' },
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
          <Suspense fallback={<LoadingSurface />}>
            <Routes>
              <Route path="/" element={<Navigate to="dashboard" replace />} />
              <Route path="dashboard" element={<Dashboard />} />
              <Route path="users" element={<Users />} />
              <Route path="organization" element={<Organization />} />
              <Route path="bots" element={<Bots />} />
              <Route path="messages" element={<Messages />} />
              <Route path="logs" element={<Logs />} />
              <Route path="groups" element={<Groups />} />
              <Route path="*" element={<Navigate to="dashboard" replace />} />
            </Routes>
          </Suspense>
        </Content>
      </Layout>
    </Layout>
  )
}

function LoadingSurface() {
  return (
    <div style={{ minHeight: 160, display: 'grid', placeItems: 'center' }}>
      <Spin size="large" />
    </div>
  )
}

export default function App() {
  const logged = !!localStorage.getItem(TOKEN_KEY)
  return (
    <BrowserRouter basename="/admin">
      {logged ? <Shell /> : <Suspense fallback={<LoadingSurface />}><Login /></Suspense>}
    </BrowserRouter>
  )
}
