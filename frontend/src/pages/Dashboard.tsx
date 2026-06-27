import { Card, Col, Row, Statistic, Table, Tag, Typography } from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
  ThunderboltOutlined,
  ExperimentOutlined,
  AlertOutlined,
  ApiOutlined,
  SafetyOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchOverview } from '../api/admin';
import type { ConnectorStatusDTO } from '../types';

function formatUptime(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

const connectorColumns = [
  {
    title: 'Connector',
    dataIndex: 'name',
    key: 'name',
    render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
  },
  {
    title: 'Status',
    dataIndex: 'connected',
    key: 'connected',
    width: 100,
    render: (connected: boolean) =>
      connected ? (
        <Tag icon={<CheckCircleOutlined />} color="success">
          Connected
        </Tag>
      ) : (
        <Tag icon={<CloseCircleOutlined />} color="error">
          Disconnected
        </Tag>
      ),
  },
  {
    title: 'Messages',
    dataIndex: 'messagesReceived',
    key: 'messagesReceived',
    width: 120,
    render: (v: number) => v.toLocaleString(),
  },
  {
    title: 'Reconnects',
    dataIndex: 'reconnectCount',
    key: 'reconnectCount',
    width: 110,
  },
  {
    title: 'Last Message',
    dataIndex: 'lastMessageTimestamp',
    key: 'lastMessageTimestamp',
    width: 180,
    render: (ts: number) => (ts > 0 ? new Date(ts).toLocaleString() : '-'),
  },
  {
    title: 'Last Error',
    dataIndex: 'lastError',
    key: 'lastError',
    ellipsis: true,
    render: (err: string) => (err ? <Typography.Text type="danger">{err}</Typography.Text> : '-'),
  },
];

export default function Dashboard() {
  const { data, isLoading } = useQuery({
    queryKey: ['overview'],
    queryFn: fetchOverview,
    refetchInterval: 5000,
  });

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        System Overview
      </Typography.Title>

      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic
              title="Uptime"
              value={data ? formatUptime(data.uptimeMs) : '-'}
              prefix={<ClockCircleOutlined />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic
              title="Strategies"
              value={data?.enabledStrategyCount ?? 0}
              suffix={`/ ${data?.strategyCount ?? 0}`}
              prefix={<ThunderboltOutlined />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic title="Factors" value={data?.factorCount ?? 0} prefix={<ExperimentOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic title="Signals" value={data?.totalSignalCount ?? 0} prefix={<AlertOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic
              title="Connectors"
              value={data?.connectedConnectorCount ?? 0}
              suffix={`/ ${data?.connectorCount ?? 0}`}
              prefix={<ApiOutlined />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic
              title="Max Loss/Trade"
              value={data?.riskConfig?.maxLossPerTradePct ?? 0}
              suffix="%"
              precision={2}
              prefix={<SafetyOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Card title="Connectors" size="small" style={{ marginTop: 16 }}>
        <Table<ConnectorStatusDTO>
          dataSource={data?.connectors ?? []}
          columns={connectorColumns}
          rowKey="name"
          loading={isLoading}
          size="small"
          pagination={false}
        />
      </Card>
    </div>
  );
}
