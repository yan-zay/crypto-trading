import { Card, Col, Row, Statistic, Button, Space, Tag, Typography, message, Popconfirm } from 'antd';
import {
  WarningOutlined,
  CheckCircleOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchRiskConfigs, fetchKillSwitch, activateKillSwitch, deactivateKillSwitch } from '../api/admin';

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

  const isHalted = ks?.active && ks.mode === 'HALT';
  const isCloseOnly = ks?.active && ks.mode === 'CLOSE_ONLY';
  const isNormal = !ks?.active;

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        Risk Management
      </Typography.Title>

      {/* KillSwitch */}
      <Card
        title={
          <Space>
            <StopOutlined />
            KillSwitch
            {ks?.active ? (
              <Tag color="error" icon={<WarningOutlined />}>
                ACTIVE — {ks.mode}
              </Tag>
            ) : (
              <Tag color="success" icon={<CheckCircleOutlined />}>
                INACTIVE
              </Tag>
            )}
          </Space>
        }
        size="small"
        style={{ marginBottom: 16 }}
        loading={ksLoading}
      >
        <Typography.Paragraph type="secondary">
          KillSwitch immediately halts all trading activity. Use in emergency situations.
        </Typography.Paragraph>
        <Space>
          <Popconfirm
            title="Activate HALT mode?"
            description="This will stop ALL trading immediately."
            onConfirm={() => activateMutation.mutate('HALT')}
            okText="Activate"
            cancelText="Cancel"
            okButtonProps={{ danger: true }}
          >
            <Button danger type="primary" disabled={isHalted} loading={activateMutation.isPending}>
              HALT (Full Stop)
            </Button>
          </Popconfirm>
          <Popconfirm
            title="Activate CLOSE_ONLY mode?"
            description="Only close positions, no new orders."
            onConfirm={() => activateMutation.mutate('CLOSE_ONLY')}
            okText="Activate"
            cancelText="Cancel"
          >
            <Button danger disabled={isCloseOnly} loading={activateMutation.isPending}>
              CLOSE_ONLY
            </Button>
          </Popconfirm>
          <Popconfirm
            title="Deactivate KillSwitch?"
            description="Resume normal trading operations."
            onConfirm={() => deactivateMutation.mutate()}
            okText="Resume"
            cancelText="Cancel"
          >
            <Button type="primary" disabled={isNormal} loading={deactivateMutation.isPending}>
              Resume Trading
            </Button>
          </Popconfirm>
        </Space>
      </Card>

      {/* Risk Config */}
      <Card title="Risk Parameters" size="small" loading={configLoading}>
        <Row gutter={[16, 16]}>
          <Col xs={12} sm={6}>
            <Statistic
              title="Max Loss / Trade"
              value={config?.maxLossPerTradePct ?? 0}
              suffix="%"
              precision={2}
              valueStyle={{ color: '#cf1322' }}
            />
          </Col>
          <Col xs={12} sm={6}>
            <Statistic
              title="Max Daily Loss"
              value={config?.maxDailyLossPct ?? 0}
              suffix="%"
              precision={2}
              valueStyle={{ color: '#cf1322' }}
            />
          </Col>
          <Col xs={12} sm={6}>
            <Statistic
              title="Max Position Size"
              value={config?.maxSizePct ?? 0}
              suffix="%"
              precision={2}
            />
          </Col>
          <Col xs={12} sm={6}>
            <Statistic title="Slippage" value={config?.slippageBps ?? 0} suffix="bps" />
          </Col>
        </Row>
      </Card>
    </div>
  );
}
