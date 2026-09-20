import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Descriptions, Input, Popconfirm, Select, Space, Table, Typography, message } from 'antd'
import { api, errMsg } from '../api/client'

// CONTENT-07 离职资产面板：群文件只读盘点 + Document 资产交接（盘点 → 指纹确认 → 执行）。
// 文档交接走服务端既有的 plan/transfer 契约：计划指纹 CAS，冲突须重新盘点；
// 群文件属于群资产，不随文档交接转移，这里只展示该用户创建且仍活跃的分布。

const PRINCIPAL_USER = 1
const PRINCIPAL_ORGANIZATION_UNIT = 2

interface GroupUsage { chatId: string; activeEntries: number; activeVersionBytes: number }
interface Inventory { chats: GroupUsage[]; totalEntries: number; totalBytes: number }
interface PlanEntry { spaceId: string; name: string; stewardUid: string; custodyRevision: number }
interface Plan {
  sourceUid: string
  targetOwnerPrincipalType: number
  targetOwnerPrincipalId: string
  targetStewardUid: string
  planFingerprint: string
  spaces: PlanEntry[]
  directGrants: { spaceId: string }[]
}
interface Receipt {
  operationId: string
  planFingerprint: string
  revokedGrantCount: number
  items: { spaceId: string }[]
}

// crypto.randomUUID 仅在安全上下文可用；内网 http 部署需要回退生成器。
function newOperationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function CustodyPanel({ uid, groupNames }: { uid: string; groupNames: Record<string, string> }) {
  const [inventory, setInventory] = useState<Inventory | null>(null)
  const [inventoryLoading, setInventoryLoading] = useState(false)

  const [ownerType, setOwnerType] = useState(PRINCIPAL_USER)
  const [steward, setSteward] = useState('')
  const [unitId, setUnitId] = useState('')
  const [plan, setPlan] = useState<Plan | null>(null)
  const [planning, setPlanning] = useState(false)
  const [transferring, setTransferring] = useState(false)
  const [receipt, setReceipt] = useState<Receipt | null>(null)

  const loadInventory = useCallback(async () => {
    setInventoryLoading(true)
    try {
      const resp = await api.get<Inventory>(`/users/${uid}/group-file-inventory`)
      setInventory(resp.data)
    } catch (error) {
      message.error(errMsg(error))
    } finally {
      setInventoryLoading(false)
    }
  }, [uid])

  useEffect(() => { loadInventory() }, [loadInventory])

  const targetParams = () => {
    if (!steward.trim()) { message.warning('请先填写目标责任人 UID'); return null }
    if (ownerType === PRINCIPAL_USER) {
      return { targetOwnerPrincipalType: PRINCIPAL_USER, targetOwnerPrincipalId: steward.trim(), targetStewardUid: steward.trim() }
    }
    if (!unitId.trim()) { message.warning('组织节点归属需要填写节点 ID'); return null }
    return { targetOwnerPrincipalType: PRINCIPAL_ORGANIZATION_UNIT, targetOwnerPrincipalId: unitId.trim(), targetStewardUid: steward.trim() }
  }

  const runPlan = async () => {
    const params = targetParams()
    if (!params) return
    setPlanning(true)
    setReceipt(null)
    try {
      const resp = await api.get<Plan>(`/users/${uid}/document-custody-plan`, { params })
      setPlan(resp.data)
      if (resp.data.spaces.length === 0) message.info('该用户名下没有需要交接的活跃文档空间')
    } catch (error) {
      message.error(errMsg(error))
      setPlan(null)
    } finally {
      setPlanning(false)
    }
  }

  const runTransfer = async () => {
    if (!plan) return
    const params = targetParams()
    if (!params) return
    setTransferring(true)
    try {
      const resp = await api.post<Receipt>(`/users/${uid}/document-custody-transfer`, {
        operationId: newOperationId(),
        expectedPlanFingerprint: plan.planFingerprint,
        ...params,
      })
      setReceipt(resp.data)
      message.success(`交接完成：${resp.data.items.length} 个空间`)
      setPlan(null)
      void loadInventory()
    } catch (error: any) {
      if (error?.response?.status === 409) {
        message.error('资产计划已发生变化（期间有新的归属或授权变更），请重新盘点')
        setPlan(null)
      } else {
        message.error(errMsg(error))
      }
    } finally {
      setTransferring(false)
    }
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <div>
        <Typography.Title level={5}>群共享文件（只读盘点）</Typography.Title>
        <Typography.Paragraph type="secondary">
          该用户创建且仍活跃的群文件；群文件属于所在群的资产，保留在群内，不随文档交接转移。
        </Typography.Paragraph>
        <Table<GroupUsage> rowKey="chatId" size="small" pagination={false} loading={inventoryLoading}
          data-testid="custody.groupfile.table"
          dataSource={inventory?.chats ?? []}
          locale={{ emptyText: '没有活跃群文件' }}
          columns={[
            { title: '群', render: (_: any, r: GroupUsage) => groupNames[r.chatId] ?? r.chatId },
            { title: 'chatId', dataIndex: 'chatId' },
            { title: '活跃条目', dataIndex: 'activeEntries', width: 90 },
            { title: '占用', dataIndex: 'activeVersionBytes', width: 110,
              render: (v: number) => formatBytes(v) },
          ]}
          summary={() => inventory && inventory.chats.length > 0 ? (
            <Table.Summary.Row>
              <Table.Summary.Cell index={0}>合计</Table.Summary.Cell>
              <Table.Summary.Cell index={1} />
              <Table.Summary.Cell index={2}>{inventory.totalEntries}</Table.Summary.Cell>
              <Table.Summary.Cell index={3}>{formatBytes(inventory.totalBytes)}</Table.Summary.Cell>
            </Table.Summary.Row>
          ) : null} />
      </div>

      <div>
        <Typography.Title level={5}>文档资产交接</Typography.Title>
        <Typography.Paragraph type="secondary">
          将该用户名下的活跃文档空间与直接授权移交给显式指定的目标；目标由操作者明确选择，
          不从组织架构推测。执行前先盘点并核对计划指纹，期间资产发生变化会拒绝并要求重新盘点。
        </Typography.Paragraph>
        <Space wrap>
          <Select value={ownerType} onChange={(v) => setOwnerType(v)} style={{ width: 140 }}
            options={[
              { value: PRINCIPAL_USER, label: '个人持有' },
              { value: PRINCIPAL_ORGANIZATION_UNIT, label: '组织节点持有' },
            ]} />
          <Input placeholder="目标责任人 UID" value={steward} onChange={(e) => setSteward(e.target.value)}
            style={{ width: 220 }} data-testid="custody.steward.input" />
          {ownerType === PRINCIPAL_ORGANIZATION_UNIT && (
            <Input placeholder="组织节点 ID（unitId）" value={unitId} onChange={(e) => setUnitId(e.target.value)}
              style={{ width: 200 }} data-testid="custody.unit.input" />
          )}
          <Button loading={planning} onClick={runPlan} data-testid="custody.plan.button">盘点</Button>
        </Space>

        {plan && (
          <div style={{ marginTop: 16 }} data-testid="custody.plan.result">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="计划指纹">
                <Typography.Text code copyable style={{ fontSize: 11 }}>{plan.planFingerprint}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="移交空间">{plan.spaces.length}</Descriptions.Item>
              <Descriptions.Item label="撤销直接授权">{plan.directGrants.length}</Descriptions.Item>
            </Descriptions>
            {plan.spaces.length > 0 && (
              <Table rowKey="spaceId" size="small" pagination={false} style={{ marginTop: 8 }}
                dataSource={plan.spaces}
                columns={[
                  { title: '空间', dataIndex: 'name' },
                  { title: 'spaceId', dataIndex: 'spaceId' },
                  { title: '当前责任人', dataIndex: 'stewardUid', width: 120 },
                ]} />
            )}
            <Popconfirm title={`确认将 ${plan.spaces.length} 个空间移交给 ${plan.targetStewardUid}？`}
              onConfirm={runTransfer}>
              <Button type="primary" danger loading={transferring} style={{ marginTop: 12 }}
                data-testid="custody.transfer.button">
                确认执行交接
              </Button>
            </Popconfirm>
          </div>
        )}

        {receipt && (
          <Alert type="success" style={{ marginTop: 16 }} data-testid="custody.receipt"
            message={`交接完成：${receipt.items.length} 个空间已移交，撤销 ${receipt.revokedGrantCount} 条直接授权`}
            description={`操作 ID：${receipt.operationId}（已审计，可凭此重试确认）`} />
        )}
      </div>
    </Space>
  )
}
