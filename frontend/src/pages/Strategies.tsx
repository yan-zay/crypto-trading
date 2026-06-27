import { useState } from 'react';
import { Card, Table, Tag, Switch, Space, Typography, message, Drawer, Descriptions, Popconfirm, Empty, Badge } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient, useQueries } from '@tanstack/react-query';
import {
  fetchStrategies,
  fetchStrategyStatus,
  enableStrategy,
  disableStrategy,
  fetchStrategySignals,
} from '../api/admin';
import type { SignalEvent } from '../types';

const SIGNAL_TYPE_COLORS: Record<string, string> = {
  BUY: 'green',
  SELL: 'red',
  HOLD: 'default',
};

export default function Strategies() {
  const queryClient = useQueryClient();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedStrategy, setSelectedStrategy] = useState<string | null>(null);

  const { data: strategies, isLoading } = useQuery({
    queryKey: ['strategies'],
    queryFn: fetchStrategies,
  });

  const statusQueries = useQueries({
    queries: (strategies ?? []).map((s) => ({
      queryKey: ['strategy-status', s.name],
      queryFn: () => fetchStrategyStatus(s.name),
    })),
  });

  const { data: strategySignals, isLoading: signalsLoading } = useQuery({
    queryKey: ['strategy-signals', selectedStrategy],
    queryFn: () => fetchStrategySignals(selectedStrategy!, 10),
    enabled: !!selectedStrategy,
  });

  const enableMutation = useMutation({
    mutationFn: enableStrategy,
    onSuccess: () => {
      message.success('Strategy enabled');
      queryClient.invalidateQueries({ queryKey: ['strategy-status'] });
      queryClient.invalidateQueries({ queryKey: ['overview'] });
    },
    onError: () => message.error('Failed to enable strategy'),
  });

  const disableMutation = useMutation({
    mutationFn: disableStrategy,
    onSuccess: () => {
      message.success('Strategy disabled');
      queryClient.invalidateQueries({ queryKey: ['strategy-status'] });
      queryClient.invalidateQueries({ queryKey: ['overview'] });
    },
    onError: () => message.error('Failed to disable strategy'),
  });

  const statusMap = new Map(
    statusQueries.filter((q) => q.data).map((q) => [q.data!.name, q.data!]),
  );

  const dataSource = (strategies ?? []).map((s) => ({
    ...s,
    enabled: statusMap.get(s.name)?.enabled ?? false,
  }));

  const openDrawer = (name: string) => {
    setSelectedStrategy(name);
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setSelectedStrategy(null);
  };

  const selectedStatus = selectedStrategy ? statusMap.get(selectedStrategy) : null;

  const signalColumns = [
    {
      title: 'Time',
      dataIndex: 'timestamp',
      key: 'timestamp',
      width: 170,
      render: (ts: number) => new Date(ts).toLocaleString(),
    },
    {
      title: 'Symbol',
      key: 'symbol',
      width: 110,
      render: (_: unknown, record: SignalEvent) => record.instrument.symbol,
    },
    {
      title: 'Type',
      dataIndex: 'type',
      key: 'type',
      width: 80,
      render: (type: string) => (
        <Tag color={SIGNAL_TYPE_COLORS[type] ?? 'default'}>{type}</Tag>
      ),
    },
    {
      title: 'Confidence',
      dataIndex: 'confidence',
      key: 'confidence',
      width: 100,
      render: (v: number) => `${(v * 100).toFixed(0)}%`,
    },
    {
      title: 'Reason',
      dataIndex: 'reason',
      key: 'reason',
      ellipsis: true,
    },
  ];

  const columns = [
    {
      title: 'Strategy',
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => (
        <Typography.Text
          strong
          style={{ cursor: 'pointer', color: '#1677ff' }}
          onClick={() => openDrawer(name)}
        >
          {name}
        </Typography.Text>
      ),
    },
    {
      title: 'Listened Events',
      dataIndex: 'listenedEvents',
      key: 'listenedEvents',
      render: (events: string[]) => (
        <Space size={[0, 4]} wrap>
          {events.map((e) => (
            <Tag key={e} color="blue">
              {e}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: 'Status',
      key: 'status',
      width: 100,
      render: (_: unknown, record: (typeof dataSource)[number]) => (
        <Badge
          status={record.enabled ? 'success' : 'default'}
          text={record.enabled ? 'Active' : 'Inactive'}
        />
      ),
    },
    {
      title: 'Enabled',
      key: 'enabled',
      width: 100,
      render: (_: unknown, record: (typeof dataSource)[number]) => (
        <Popconfirm
          title={record.enabled ? 'Disable this strategy?' : 'Enable this strategy?'}
          description={
            record.enabled
              ? 'The strategy will stop processing events.'
              : 'The strategy will start processing events.'
          }
          onConfirm={() => {
            if (record.enabled) {
              disableMutation.mutate(record.name);
            } else {
              enableMutation.mutate(record.name);
            }
          }}
          okText="Confirm"
          cancelText="Cancel"
        >
          <Switch
            checked={record.enabled}
            loading={enableMutation.isPending || disableMutation.isPending}
          />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        <ThunderboltOutlined style={{ marginRight: 8 }} />
        Strategies
      </Typography.Title>
      <Card size="small">
        <Table
          dataSource={dataSource}
          columns={columns}
          rowKey="name"
          loading={isLoading}
          size="small"
          pagination={false}
        />
      </Card>

      <Drawer
        title={
          <Space>
            <ThunderboltOutlined />
            <span>{selectedStrategy}</span>
            {selectedStatus && (
              <Badge
                status={selectedStatus.enabled ? 'success' : 'default'}
                text={selectedStatus.enabled ? 'Active' : 'Inactive'}
              />
            )}
          </Space>
        }
        open={drawerOpen}
        onClose={closeDrawer}
        width={640}
      >
        {selectedStrategy && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="Strategy Name">{selectedStrategy}</Descriptions.Item>
              <Descriptions.Item label="Status">
                <Badge
                  status={selectedStatus?.enabled ? 'success' : 'default'}
                  text={selectedStatus?.enabled ? 'Active' : 'Inactive'}
                />
              </Descriptions.Item>
              <Descriptions.Item label="Listened Events">
                <Space size={[0, 4]} wrap>
                  {(selectedStatus?.listenedEvents ?? []).map((e) => (
                    <Tag key={e} color="blue">{e}</Tag>
                  ))}
                </Space>
              </Descriptions.Item>
            </Descriptions>

            <Card title="Recent Signals (Last 10)" size="small">
              <Table<SignalEvent>
                dataSource={strategySignals ?? []}
                columns={signalColumns}
                rowKey={(r) => `${r.timestamp}-${r.instrument.symbol}`}
                loading={signalsLoading}
                size="small"
                pagination={false}
                locale={{ emptyText: <Empty description="No signals yet" /> }}
              />
            </Card>
          </Space>
        )}
      </Drawer>
    </div>
  );
}
