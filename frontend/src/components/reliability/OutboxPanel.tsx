import { Button, Flex, Statistic, Table, Tag, Tooltip, type TableColumnsType } from 'antd';
import { RedoOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchOutbox, fetchOutboxBacklog, retryOutboxEvent } from '../../api/admin';
import { notify } from '../../feedback/notify';
import type { OutboxEvent } from '../../types';

export default function OutboxPanel() {
  const queryClient = useQueryClient();
  const backlogQuery = useQuery({
    queryKey: ['outbox-backlog'], queryFn: fetchOutboxBacklog, refetchInterval: 5000,
  });
  const eventsQuery = useQuery({
    queryKey: ['outbox-events'], queryFn: () => fetchOutbox(undefined, 300), refetchInterval: 5000,
  });
  const retryMutation = useMutation({
    mutationFn: retryOutboxEvent,
    onSuccess: () => {
      notify.success('Outbox event queued for retry');
      void queryClient.invalidateQueries({ queryKey: ['outbox'] });
    },
  });
  const columns: TableColumnsType<OutboxEvent> = [
    { title: 'Sequence', dataIndex: 'eventSequence', width: 100 },
    { title: 'Event', dataIndex: 'eventType', width: 220 },
    { title: 'Aggregate', width: 220, render: (_, row) => `${row.aggregateType} · ${row.aggregateId}` },
    { title: 'Status', dataIndex: 'status', width: 110, render: (value) => <Tag color={value === 'PUBLISHED' ? 'success' : value === 'DEAD_LETTER' ? 'error' : 'processing'}>{value}</Tag> },
    { title: 'Attempts', dataIndex: 'attempts', width: 90 },
    { title: 'Available', dataIndex: 'availableAtMs', width: 170, render: (value) => new Date(value).toLocaleString() },
    { title: 'Error', dataIndex: 'lastError', ellipsis: true },
    {
      title: '', width: 58, fixed: 'right',
      render: (_, row) => row.status === 'DEAD_LETTER' ? (
        <Tooltip title="Retry dead letter">
          <Button type="text" icon={<RedoOutlined />} loading={retryMutation.isPending} onClick={() => retryMutation.mutate(row.eventId)} />
        </Tooltip>
      ) : null,
    },
  ];
  const pending = Number(backlogQuery.data?.pendingEvents ?? 0);
  const oldest = Number(backlogQuery.data?.oldestAgeMs ?? 0);

  return (
    <div>
      <Flex gap={32} wrap style={{ marginBottom: 18 }}>
        <Statistic title="Pending events" value={pending} styles={{ content: { color: pending ? '#ad6800' : '#237804' } }} />
        <Statistic title="Oldest age" value={(oldest / 1000).toFixed(1)} suffix="s" />
      </Flex>
      <Table
        rowKey="eventId"
        columns={columns}
        dataSource={eventsQuery.data ?? []}
        loading={eventsQuery.isLoading}
        size="small"
        scroll={{ x: 1150 }}
        pagination={{ pageSize: 25 }}
      />
    </div>
  );
}
