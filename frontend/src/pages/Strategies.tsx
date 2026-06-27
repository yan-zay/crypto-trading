import { Card, Table, Tag, Switch, Space, Typography, message } from 'antd';
import { useQuery, useMutation, useQueryClient, useQueries } from '@tanstack/react-query';
import { fetchStrategies, fetchStrategyStatus, enableStrategy, disableStrategy } from '../api/admin';

export default function Strategies() {
  const queryClient = useQueryClient();

  const { data: strategies, isLoading } = useQuery({
    queryKey: ['strategies'],
    queryFn: fetchStrategies,
  });

  // Fetch enabled status for each strategy
  const statusQueries = useQueries({
    queries: (strategies ?? []).map((s) => ({
      queryKey: ['strategy-status', s.name],
      queryFn: () => fetchStrategyStatus(s.name),
    })),
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

  const columns = [
    {
      title: 'Strategy',
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
    },
    {
      title: 'Listened Events',
      dataIndex: 'listenedEvents',
      key: 'listenedEvents',
      render: (events: string[]) => (
        <Space size={[0, 4]} wrap>
          {events.map((e) => (
            <Tag key={e}>{e}</Tag>
          ))}
        </Space>
      ),
    },
    {
      title: 'Enabled',
      key: 'enabled',
      width: 100,
      render: (_: unknown, record: (typeof dataSource)[number]) => (
        <Switch
          checked={record.enabled}
          loading={enableMutation.isPending || disableMutation.isPending}
          onChange={(checked) => {
            if (checked) {
              enableMutation.mutate(record.name);
            } else {
              disableMutation.mutate(record.name);
            }
          }}
        />
      ),
    },
  ];

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
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
    </div>
  );
}
