import { Card, Table, Tag, Typography, Space, Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { fetchSignals } from '../api/admin';
import type { SignalEvent } from '../types';
import { useState } from 'react';

const TYPE_COLORS: Record<string, string> = {
  BUY: 'green',
  SELL: 'red',
  HOLD: 'blue',
};

export default function Signals() {
  const [limit, setLimit] = useState(50);

  const { data, isLoading } = useQuery({
    queryKey: ['signals', limit],
    queryFn: () => fetchSignals(limit),
    refetchInterval: 10000,
  });

  const columns = [
    {
      title: 'Time',
      dataIndex: 'timestamp',
      key: 'timestamp',
      width: 180,
      render: (ts: number) => new Date(ts).toLocaleString(),
      sorter: (a: SignalEvent, b: SignalEvent) => a.timestamp - b.timestamp,
      defaultSortOrder: 'descend' as const,
    },
    {
      title: 'Strategy',
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: 150,
    },
    {
      title: 'Symbol',
      key: 'symbol',
      width: 120,
      render: (_: unknown, record: SignalEvent) => (
        <Typography.Text strong>{record.instrument.symbol}</Typography.Text>
      ),
    },
    {
      title: 'Type',
      dataIndex: 'type',
      key: 'type',
      width: 80,
      render: (type: string) => (
        <Tag color={TYPE_COLORS[type] ?? 'default'}>{type}</Tag>
      ),
    },
    {
      title: 'Confidence',
      dataIndex: 'confidence',
      key: 'confidence',
      width: 110,
      render: (v: number) => `${(v * 100).toFixed(0)}%`,
      sorter: (a: SignalEvent, b: SignalEvent) => a.confidence - b.confidence,
    },
    {
      title: 'Reason',
      dataIndex: 'reason',
      key: 'reason',
      ellipsis: true,
    },
    {
      title: 'Factors',
      key: 'factors',
      width: 200,
      render: (_: unknown, record: SignalEvent) => {
        const entries = Object.entries(record.factorSnapshot ?? {});
        if (entries.length === 0) return '-';
        return (
          <Space size={[0, 2]} wrap>
            {entries.slice(0, 3).map(([k, v]) => (
              <Tag key={k} style={{ fontSize: 11 }}>
                {k}: {Number(v).toFixed(2)}
              </Tag>
            ))}
            {entries.length > 3 && <Tag>+{entries.length - 3}</Tag>}
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 24 }} align="center">
        <Typography.Title level={4} style={{ marginBottom: 0 }}>
          Signals
        </Typography.Title>
        <Select
          value={limit}
          onChange={setLimit}
          options={[
            { value: 20, label: 'Last 20' },
            { value: 50, label: 'Last 50' },
            { value: 100, label: 'Last 100' },
            { value: 200, label: 'Last 200' },
          ]}
          style={{ width: 120 }}
          size="small"
        />
      </Space>
      <Card size="small">
        <Table<SignalEvent>
          dataSource={data ?? []}
          columns={columns}
          rowKey={(r) => `${r.strategyName}-${r.timestamp}-${r.instrument.symbol}`}
          loading={isLoading}
          size="small"
          pagination={{ pageSize: 20, showSizeChanger: false }}
          scroll={{ x: 900 }}
        />
      </Card>
    </div>
  );
}
