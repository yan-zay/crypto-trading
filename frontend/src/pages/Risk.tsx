import { Card, Col, Row, Statistic, Button, Space, Typography, message, Popconfirm, Descriptions, Divider, Badge } from 'antd';
import {
  WarningOutlined,
  StopOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  SafetyOutlined,
  LockOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchRiskConfigs, fetchKillSwitch, activateKillSwitch, deactivateKillSwitch } from '../api/admin';

const MODE_CONFIG = {
  HALT: {
    color: '#ff4d4f',
    label: 'HALT',
    description: 'All trading stopped. No new or closing orders.',
    icon: <StopOutlined />,
  },
  CLOSE_ONLY: {
    color: '#faad14',
    label: 'CLOSE ONLY',
    description: 'Only close existing positions. No new orders.',
    icon: <PauseCircleOutlined />,
  },
  NORMAL: {
    color: '#52c41a',
    label: 'NORMAL',
    description: 'Full trading active.',
    icon: <PlayCircleOutlined />,
  },
} as const;

export default function Risk() {
  const queryClient = useQueryClient();

  const { data: config, isLoading: configLoading } = useQuery({
    queryKey: ['risk-config'],
    queryFn: fetchRiskConfigs,
  });

  const { data: ks, isLoading: ksLoading } = useQuery({
    queryKey: ['kill-switch'],
    queryFn: fetchKillSwitch,
    refetchInterval: 3000,
  });

  const activateMutation = useMutation({
    mutationFn: (mode: 'HALT' | 'CLOSE_ONLY') => activateKillSwitch(mode),
    onSuccess: () => {
      message.warning('KillSwitch activated');
      queryClient.invalidateQueries({ queryKey: ['kill-switch'] });
    },
    onError: () => message.error('Failed to activate KillSwitch'),
  });

  const deactivateMutation = useMutation({
    mutationFn: deactivateKillSwitch,
    onSuccess: () => {
      message.success('KillSwitch deactivated — normal trading resumed');
      queryClient.invalidateQueries({ queryKey: ['kill-switch'] });
    },
    onError: () => message.error('Failed to deactivate KillSwitch'),
  });

  const currentMode = ks?.active ? ks.mode : 'NORMAL';
  const modeConfig = MODE_CONFIG[currentMode];

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        <SafetyOutlined style={{ marginRight: 8 }} />
        Risk Management
      </Typography.Title>

      {/* KillSwitch Status Banner */}
      <Card
        size="small"
        style={{
          marginBottom: 16,
          borderLeft: `4px solid ${modeConfig.color}`,
        }}
        loading={ksLoading}
      >
        <Row align="middle" gutter={16}>
          <Col flex="none">
            <Badge
              status={ks?.active ? 'error' : 'success'}
              text={
                <Typography.Title level={5} style={{ margin: 0 }}>
                  KillSwitch: {modeConfig.label}
                </Typography.Title>
              }
            />
          </Col>
          <Col flex="auto">
            <Typography.Text type="secondary">{modeConfig.description}</Typography.Text>
          </Col>
        </Row>
      </Card>

      {/* KillSwitch Big Buttons */}
      <Card
        title={
          <Space>
            <StopOutlined />
            <span>KillSwitch Controls</span>
          </Space>
        }
        size="small"
        style={{ marginBottom: 16 }}
      >
        <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
          KillSwitch immediately halts all trading activity. Use in emergency situations.
        </Typography.Paragraph>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={8}>
            <Popconfirm
              title="Activate HALT mode?"
              description="This will stop ALL trading immediately. No orders will be placed."
              onConfirm={() => activateMutation.mutate('HALT')}
              okText="Activate HALT"
              cancelText="Cancel"
              okButtonProps={{ danger: true }}
            >
              <Button
                block
                size="large"
                danger
                type={currentMode === 'HALT' ? 'primary' : 'default'}
                disabled={currentMode === 'HALT'}
                loading={activateMutation.isPending}
                icon={<StopOutlined />}
                style={{
                  height: 64,
                  fontSize: 16,
                  fontWeight: 'bold',
                  borderWidth: currentMode === 'HALT' ? 2 : 1,
                }}
              >
                HALT
              </Button>
            </Popconfirm>
            <Typography.Text type="secondary" style={{ display: 'block', textAlign: 'center', marginTop: 4, fontSize: 12 }}>
              Full Stop
            </Typography.Text>
          </Col>
          <Col xs={24} sm={8}>
            <Popconfirm
              title="Activate CLOSE_ONLY mode?"
              description="Only close positions, no new orders will be placed."
              onConfirm={() => activateMutation.mutate('CLOSE_ONLY')}
              okText="Activate"
              cancelText="Cancel"
            >
              <Button
                block
                size="large"
                type={currentMode === 'CLOSE_ONLY' ? 'primary' : 'default'}
                disabled={currentMode === 'CLOSE_ONLY'}
                loading={activateMutation.isPending}
                icon={<PauseCircleOutlined />}
                style={{
                  height: 64,
                  fontSize: 16,
                  fontWeight: 'bold',
                  color: currentMode === 'CLOSE_ONLY' ? '#faad14' : undefined,
                  borderColor: currentMode === 'CLOSE_ONLY' ? '#faad14' : undefined,
                  borderWidth: currentMode === 'CLOSE_ONLY' ? 2 : 1,
                }}
              >
                CLOSE ONLY
              </Button>
            </Popconfirm>
            <Typography.Text type="secondary" style={{ display: 'block', textAlign: 'center', marginTop: 4, fontSize: 12 }}>
              Close Positions Only
            </Typography.Text>
          </Col>
          <Col xs={24} sm={8}>
            <Popconfirm
              title="Resume normal trading?"
              description="This will deactivate KillSwitch and resume all trading."
              onConfirm={() => deactivateMutation.mutate()}
              okText="Resume"
              cancelText="Cancel"
            >
              <Button
                block
                size="large"
                type="primary"
                disabled={!ks?.active}
                loading={deactivateMutation.isPending}
                icon={<PlayCircleOutlined />}
                style={{
                  height: 64,
                  fontSize: 16,
                  fontWeight: 'bold',
                  backgroundColor: !ks?.active ? '#52c41a' : undefined,
                  borderColor: !ks?.active ? '#52c41a' : undefined,
                  borderWidth: !ks?.active ? 2 : 1,
                }}
              >
                NORMAL
              </Button>
            </Popconfirm>
            <Typography.Text type="secondary" style={{ display: 'block', textAlign: 'center', marginTop: 4, fontSize: 12 }}>
              Resume Trading
            </Typography.Text>
          </Col>
        </Row>
      </Card>

      {/* Risk Parameters */}
      <Card
        title={
          <Space>
            <LockOutlined />
            <span>Risk Parameters</span>
          </Space>
        }
        size="small"
        loading={configLoading}
      >
        <Row gutter={[16, 16]}>
          <Col xs={12} sm={6}>
            <Statistic
              title="Max Loss / Trade"
              value={config?.maxLossPerTradePct ?? 0}
              suffix="%"
              precision={2}
              valueStyle={{ color: '#cf1322' }}
              prefix={<WarningOutlined />}
            />
          </Col>
          <Col xs={12} sm={6}>
            <Statistic
              title="Max Daily Loss"
              value={config?.maxDailyLossPct ?? 0}
              suffix="%"
              precision={2}
              valueStyle={{ color: '#cf1322' }}
              prefix={<WarningOutlined />}
            />
          </Col>
          <Col xs={12} sm={6}>
            <Statistic
              title="Max Position Size"
              value={config?.maxSizePct ?? 0}
              suffix="%"
              precision={2}
              prefix={<ThunderboltOutlined />}
            />
          </Col>
          <Col xs={12} sm={6}>
            <Statistic
              title="Slippage Tolerance"
              value={config?.slippageBps ?? 0}
              suffix="bps"
              prefix={<SafetyOutlined />}
            />
          </Col>
        </Row>

        <Divider style={{ margin: '16px 0' }} />

        <Descriptions column={{ xs: 1, sm: 2 }} size="small" bordered>
          <Descriptions.Item label="Max Loss Per Trade">
            <Typography.Text strong style={{ color: '#cf1322' }}>
              {config?.maxLossPerTradePct ?? 0}%
            </Typography.Text>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              Any trade exceeding this loss threshold will be auto-closed.
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Max Daily Loss">
            <Typography.Text strong style={{ color: '#cf1322' }}>
              {config?.maxDailyLossPct ?? 0}%
            </Typography.Text>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              KillSwitch activates if daily PnL drops below this threshold.
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Max Position Size">
            <Typography.Text strong>
              {config?.maxSizePct ?? 0}%
            </Typography.Text>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              Maximum position size as percentage of total capital.
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Slippage Tolerance">
            <Typography.Text strong>
              {config?.slippageBps ?? 0} bps
            </Typography.Text>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              Maximum acceptable slippage in basis points for order execution.
            </Typography.Text>
          </Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  );
}
