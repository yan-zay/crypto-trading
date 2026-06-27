import { Card, Col, Row, Statistic, Table, Tag, Typography, Badge, List, Space, Tooltip } from 'antd';
import {
  ClockCircleOutlined,
  ThunderboltOutlined,
  ExperimentOutlined,
  AlertOutlined,
  ApiOutlined,
  SafetyOutlined,
  WarningOutlined,
  InfoCircleOutlined,
  ExclamationCircleOutlined,
  FireOutlined,
  CloudServerOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchOverview, fetchAlerts } from '../api/admin';
import type { ConnectorStatusDTO, AlertEvent } from '../types';

function formatUptime(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

const ALERT_LEVEL_CONFIG: Record<string, { color: string; icon: React.ReactNode }> = {
  INFO: { color: 'blue', icon: <InfoCircleOutlined /> },
  WARN: { color: 'orange', icon: <WarningOutlined /> },
  ERROR: { color: 'red', icon: <ExclamationCircleOutlined /> },
  CRITICAL: { color: 'red', icon: <FireOutlined /> },
};

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
    width: 120,
    render: (connected: boolean) =>
      connected ? (
        <Badge status="success" text={<Typography.Text type="success">Connected</Typography.Text>} />
      ) : (
        <Badge status="error" text={<Typography.Text type="danger">Disconnected</Typography.Text>} />
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

  const { data: alerts } = useQuery({
    queryKey: ['alerts'],
    queryFn: () => fetchAlerts(10),
    refetchInterval: 10000,
  });

  const connectedCount = data?.connectors?.filter((c) => c.connected).length ?? 0;
  const totalCount = data?.connectors?.length ?? 0;

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        System Overview
      </Typography.Title>

      {/* System Status Row */}
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
              title="Version"
              value="1.0.0"
              prefix={<CodeOutlined />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic
              title="Environment"
              value="Dev"
              prefix={<CloudServerOutlined />}
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
      </Row>

      {/* Event Throughput Row */}
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="Connectors Online"
              value={connectedCount}
              suffix={`/ ${totalCount}`}
              prefix={<ApiOutlined />}
              valueStyle={{ color: connectedCount === totalCount ? '#3f8600' : '#cf1322' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="Max Loss/Trade"
              value={data?.riskConfig?.maxLossPerTradePct ?? 0}
              suffix="%"
              precision={2}
              prefix={<SafetyOutlined />}
              valueStyle={{ color: '#cf1322' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="Max Daily Loss"
              value={data?.riskConfig?.maxDailyLossPct ?? 0}
              suffix="%"
              precision={2}
              prefix={<SafetyOutlined />}
              valueStyle={{ color: '#cf1322' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="Total Messages"
              value={data?.connectors?.reduce((sum, c) => sum + c.messagesReceived, 0) ?? 0}
              prefix={<ThunderboltOutlined />}
            />
          </Card>
        </Col>
      </Row>

      {/* Connectors Table */}
      <Card
        title={
          <Space>
            <span>Connectors</span>
            <Tooltip title="WebSocket connection status">
              <Badge
                count={totalCount - connectedCount}
                showZero
                style={{ backgroundColor: connectedCount === totalCount ? '#52c41a' : '#ff4d4f' }}
              />
            </Tooltip>
          </Space>
        }
        size="small"
        style={{ marginTop: 16 }}
      >
        <Table<ConnectorStatusDTO>
          dataSource={data?.connectors ?? []}
          columns={connectorColumns}
          rowKey="name"
          loading={isLoading}
          size="small"
          pagination={false}
        />
      </Card>

      {/* Recent Alerts */}
      <Card title="Recent Alerts" size="small" style={{ marginTop: 16 }}>
        <List<AlertEvent>
          dataSource={alerts ?? []}
          loading={!alerts}
          size="small"
          locale={{ emptyText: 'No alerts' }}
          renderItem={(item) => {
            const config = ALERT_LEVEL_CONFIG[item.level] ?? ALERT_LEVEL_CONFIG.INFO;
            return (
              <List.Item>
                <Space>
                  <Tag color={config.color} icon={config.icon}>
                    {item.level}
                  </Tag>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {new Date(item.timestamp).toLocaleString()}
                  </Typography.Text>
                  <Typography.Text>[{item.source}]</Typography.Text>
                  <Typography.Text>{item.message}</Typography.Text>
                </Space>
              </List.Item>
            );
          }}
        />
      </Card>
    </div>
  );
}
