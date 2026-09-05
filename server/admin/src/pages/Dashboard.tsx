import { useEffect, useState } from 'react'
import { Alert, Card, Col, Row, Statistic, Table, Typography } from 'antd'
import { api, errMsg } from '../api/client'
import dayjs from 'dayjs'

interface Overview {
  onlineCount: number; userCount: number; groupCount: number; todayEvents: number
  storageRocksdbBytes: number; storageFileStoreBytes: number
  storageScanTruncated?: boolean
}

const mb = (b: number) => (b / 1024 / 1024).toFixed(1)

export default function Dashboard() {
  const [ov, setOv] = useState<Overview | null>(null)
  useEffect(() => { api.get('/overview').then(r => setOv(r.data)).catch(e => console.error(errMsg(e))) }, [])
  if (!ov) return null
  return (
    <div>
      {ov.storageScanTruncated && <Alert type="warning" showIcon style={{ marginBottom: 16 }}
        message="存储目录达到诊断扫描预算，以下容量是已扫描下限" />}
      <Row gutter={16}>
        <Col span={4}><Card><Statistic title="在线用户" value={ov.onlineCount} /></Card></Col>
        <Col span={4}><Card><Statistic title="用户总数" value={ov.userCount} /></Card></Col>
        <Col span={4}><Card><Statistic title="群组数" value={ov.groupCount} /></Card></Col>
        <Col span={4}><Card><Statistic title="今日事件量" value={ov.todayEvents} suffix="（含消息分发）" /></Card></Col>
        <Col span={4}><Card><Statistic title="消息存储" value={mb(ov.storageRocksdbBytes)} suffix="MB" /></Card></Col>
        <Col span={4}><Card><Statistic title="文件存储" value={mb(ov.storageFileStoreBytes)} suffix="MB" /></Card></Col>
      </Row>
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        快照时间：{dayjs().format('YYYY-MM-DD HH:mm:ss')} · 刷新即重新查询
      </Typography.Paragraph>
    </div>
  )
}
