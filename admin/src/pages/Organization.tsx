import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Checkbox, Col, Descriptions, Form, Input, InputNumber, Modal,
  Popconfirm, Row, Select, Space, Table, Tag, Tree, Typography, message,
} from 'antd'
import { ApartmentOutlined, PlusOutlined, ReloadOutlined, TeamOutlined } from '@ant-design/icons'
import { api, errMsg } from '../api/client'

interface Unit {
  unitId: string
  parentId?: string
  name: string
  leaderUid?: string
  sortOrder: number
  groupChatId?: string
  status: number
}

interface OrgMember {
  unitId: string
  uid: string
  title?: string
  primary: boolean
  user?: { name?: string; username?: string }
}

interface UserOption { uid: string; name?: string; username: string }

function toTree(units: Unit[], parentId?: string): any[] {
  return units
    .filter(unit => unit.parentId === parentId)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name))
    .map(unit => ({
      key: unit.unitId,
      title: (
        <Space size={6}>
          <span>{unit.name}</span>
          {unit.groupChatId && <Tag color="blue">部门群</Tag>}
        </Space>
      ),
      icon: <ApartmentOutlined />,
      children: toTree(units, unit.unitId),
    }))
}

export default function Organization() {
  const [units, setUnits] = useState<Unit[]>([])
  const [selectedId, setSelectedId] = useState<string>()
  const [members, setMembers] = useState<OrgMember[]>([])
  const [loading, setLoading] = useState(false)
  const [unitModal, setUnitModal] = useState<{ mode: 'create' | 'edit'; parentId?: string; unit?: Unit }>()
  const [memberModal, setMemberModal] = useState(false)
  const [userOptions, setUserOptions] = useState<UserOption[]>([])
  const [unitForm] = Form.useForm()
  const [memberForm] = Form.useForm()

  const selected = units.find(unit => unit.unitId === selectedId)
  const treeData = useMemo(() => toTree(units), [units])

  const loadUnits = useCallback(async () => {
    setLoading(true)
    try {
      const { data } = await api.get('/organization/units')
      setUnits(data)
      setSelectedId(current => current && data.some((u: Unit) => u.unitId === current)
        ? current
        : data.find((u: Unit) => !u.parentId)?.unitId ?? data[0]?.unitId)
    } catch (e) { message.error(errMsg(e)) } finally { setLoading(false) }
  }, [])

  const loadMembers = useCallback(async (unitId?: string) => {
    if (!unitId) { setMembers([]); return }
    try {
      const { data } = await api.get(`/organization/units/${unitId}/members`)
      setMembers(data)
    } catch (e) { message.error(errMsg(e)) }
  }, [])

  useEffect(() => { loadUnits() }, [loadUnits])
  useEffect(() => { loadMembers(selectedId) }, [selectedId, loadMembers])

  const loadUserOptions = async (query = '') => {
    try {
      const { data } = await api.get('/users', { params: { query: query || undefined, page: 1, size: 30 } })
      setUserOptions(data.items)
    } catch (e) { message.error(errMsg(e)) }
  }

  const openCreate = (parentId?: string) => {
    setUnitModal({ mode: 'create', parentId })
    unitForm.setFieldsValue({ parentId, name: '', leaderUid: undefined, sortOrder: 0, enableGroup: false })
    loadUserOptions()
  }

  const openEdit = (unit: Unit) => {
    setUnitModal({ mode: 'edit', unit })
    unitForm.setFieldsValue(unit)
    loadUserOptions()
  }

  const saveUnit = async (values: any) => {
    try {
      if (unitModal?.mode === 'edit') await api.put(`/organization/units/${unitModal.unit?.unitId}`, values)
      else await api.post('/organization/units', values)
      message.success('组织节点已保存')
      setUnitModal(undefined)
      unitForm.resetFields()
      await loadUnits()
    } catch (e) { message.error(errMsg(e)) }
  }

  const saveMember = async (values: any) => {
    if (!selectedId) return
    try {
      await api.post(`/organization/units/${selectedId}/members`, values)
      message.success('成员归属已保存')
      setMemberModal(false)
      memberForm.resetFields()
      await loadMembers(selectedId)
    } catch (e) { message.error(errMsg(e)) }
  }

  const changeGroup = async (enable: boolean) => {
    if (!selectedId) return
    try {
      await api.post(`/organization/units/${selectedId}/group/${enable ? 'enable' : 'disable'}`)
      message.success(enable ? '部门群已启用' : '部门群已停用')
      await loadUnits()
    } catch (e) { message.error(errMsg(e)) }
  }

  const archiveUnit = async () => {
    if (!selectedId) return
    try {
      await api.delete(`/organization/units/${selectedId}`)
      message.success('组织节点已归档')
      setSelectedId(undefined)
      await loadUnits()
    } catch (e) { message.error(errMsg(e)) }
  }

  const reconcile = async () => {
    try {
      const { data } = await api.post('/organization/reconcile')
      if (data.ok) message.success('全部部门群已与组织架构同步')
      else message.warning(`以下节点同步失败：${data.failedUnitIds.join(', ')}`)
    } catch (e) { message.error(errMsg(e)) }
  }

  return (
    <div>
      <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>组织架构</Typography.Title>
          <Typography.Text type="secondary">组织归属是部门群成员的唯一事实源，结构变更会自动同步群成员。</Typography.Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reconcile}>同步部门群</Button>
          {!units.some(unit => !unit.parentId) && <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate()}>建立根组织</Button>}
        </Space>
      </Row>

      <Row gutter={16}>
        <Col xs={24} lg={8}>
          <Card title="部门结构" loading={loading} extra={selected && <Button size="small" icon={<PlusOutlined />} onClick={() => openCreate(selected.unitId)}>新增下级</Button>}>
            {treeData.length > 0
              ? <Tree showIcon defaultExpandAll selectedKeys={selectedId ? [selectedId] : []} treeData={treeData}
                  onSelect={keys => setSelectedId(keys[0]?.toString())} />
              : <div style={{ padding: 32, textAlign: 'center', color: '#8c8c8c' }}>尚未建立组织架构</div>}
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          {selected ? <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card title={selected.name} extra={<Space>
              <Button onClick={() => openEdit(selected)}>编辑</Button>
              {selected.groupChatId
                ? <Popconfirm title="停用后部门群将关闭" onConfirm={() => changeGroup(false)}><Button>停用部门群</Button></Popconfirm>
                : <Button icon={<TeamOutlined />} onClick={() => changeGroup(true)}>启用部门群</Button>}
              <Popconfirm title="仅空部门可以归档，确定继续？" onConfirm={archiveUnit}><Button danger>归档</Button></Popconfirm>
            </Space>}>
              <Descriptions column={2} size="small">
                <Descriptions.Item label="节点 ID">{selected.unitId}</Descriptions.Item>
                <Descriptions.Item label="负责人">{selected.leaderUid || '未设置'}</Descriptions.Item>
                <Descriptions.Item label="上级部门">{units.find(u => u.unitId === selected.parentId)?.name || '无（根组织）'}</Descriptions.Item>
                <Descriptions.Item label="部门群">{selected.groupChatId || '未启用'}</Descriptions.Item>
              </Descriptions>
            </Card>

            <Card title={`直属成员（${members.length}）`} extra={<Button type="primary" size="small" icon={<PlusOutlined />}
              onClick={() => { setMemberModal(true); memberForm.resetFields(); loadUserOptions() }}>添加成员</Button>}>
              <Table rowKey="uid" size="small" pagination={false} dataSource={members} columns={[
                { title: '成员', render: (_: unknown, row: OrgMember) => row.user?.name || row.user?.username || row.uid },
                { title: '用户 ID', dataIndex: 'uid', ellipsis: true },
                { title: '职位', dataIndex: 'title', render: value => value || '-' },
                { title: '归属', dataIndex: 'primary', width: 90, render: primary => primary ? <Tag color="blue">主部门</Tag> : <Tag>兼任</Tag> },
                { title: '操作', width: 80, render: (_: unknown, row: OrgMember) => (
                  <Popconfirm title="移出该部门？" onConfirm={async () => {
                    try { await api.delete(`/organization/units/${selected.unitId}/members/${row.uid}`); await loadMembers(selected.unitId) }
                    catch (e) { message.error(errMsg(e)) }
                  }}><Button type="link" danger size="small">移出</Button></Popconfirm>
                ) },
              ]} />
            </Card>
          </Space> : <Card><div style={{ padding: 80, textAlign: 'center', color: '#8c8c8c' }}>从左侧选择一个部门</div></Card>}
        </Col>
      </Row>

      <Modal title={unitModal?.mode === 'edit' ? '编辑组织节点' : '新增组织节点'} open={!!unitModal}
        onCancel={() => setUnitModal(undefined)} onOk={() => unitForm.submit()} destroyOnClose>
        <Form form={unitForm} layout="vertical" onFinish={saveUnit} preserve={false}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}><Input maxLength={120} /></Form.Item>
          <Form.Item name="parentId" label="上级部门"><Select allowClear options={units.filter(u => u.unitId !== unitModal?.unit?.unitId).map(u => ({ label: u.name, value: u.unitId }))} /></Form.Item>
          <Form.Item name="leaderUid" label="负责人"><Select allowClear showSearch filterOption={false} onSearch={loadUserOptions}
            options={userOptions.map(u => ({ label: `${u.name || u.username} · ${u.username}`, value: u.uid }))} /></Form.Item>
          <Form.Item name="sortOrder" label="排序"><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          {unitModal?.mode === 'create' && <Form.Item name="enableGroup" valuePropName="checked"><Checkbox>同时建立部门群（必须设置负责人）</Checkbox></Form.Item>}
        </Form>
      </Modal>

      <Modal title={`添加到 ${selected?.name ?? '部门'}`} open={memberModal} onCancel={() => setMemberModal(false)}
        onOk={() => memberForm.submit()} destroyOnClose>
        <Form form={memberForm} layout="vertical" onFinish={saveMember} preserve={false} initialValues={{ primary: true }}>
          <Form.Item name="uid" label="用户" rules={[{ required: true, message: '请选择用户' }]}>
            <Select showSearch filterOption={false} onSearch={loadUserOptions} options={userOptions.map(u => ({ label: `${u.name || u.username} · ${u.username}`, value: u.uid }))} />
          </Form.Item>
          <Form.Item name="title" label="职位"><Input maxLength={80} placeholder="例如：客户端工程师" /></Form.Item>
          <Form.Item name="primary" valuePropName="checked"><Checkbox>设为该用户的主部门</Checkbox></Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
