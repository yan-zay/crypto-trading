import { useState } from 'react';
import { Card, Table, Tag, Typography, Space, Select, DatePicker, Descriptions, Empty } from 'antd';
import { AlertOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchSignals } from '../api/admin';
import type { SignalEvent } from '../types';
import dayjs from 'dayjs';

const { RangePicker } = DatePicker;

const TYPE_COLORS: Record<string, string> = {
  BUY: 'green',
  SELL: 'red',
  HOLD: 'default',
};

const TYPE_LABELS: Record<string, string> = {
  BUY: 'BUY',
  SELL: 'SELL',
  HOLD: 'HOLD',
};

export default function Signals() {
  const [limit, setLimit] = useState(50);
  const [timeRange, setTimeRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [strategyFilter, setStrategyFilter] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['signals', limit],
    queryFn: () => fetchSignals(limit),
    refetchInterval: 10000,
  });

  const filteredData = (data ?? []).filter((s) => {
    if (timeRange && timeRange[0] && timeRange[1]) {
      const ts = dayjs(s.timestamp);
      if (ts.isBefore(timeRange[0]) || ts.isAfter(timeRange[1])) return false;
    }
    if (strategyFilter && s.strategyName !== strategyFilter) return false;
    return true;
  });

  const uniqueStrategies = [...new Set((data ?? []).map((s) => s.strategyName))].sort();

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
      width: 90,
      render: (type: string) => (
        <Tag
          color={TYPE_COLORS[type] ?? 'default'}
          style={{ fontWeight: 'bold', minWidth: 50, textAlign: 'center' }}
        >
          {TYPE_LABELS[type] ?? type}
        </Tag>
      ),
    },
    {
      title: 'Confidence',
      dataIndex: 'confidence',
      key: 'confidence',
      width: 110,
      render: (v: number) => {
        const pct = v * 100;
        let color = '#000';
        if (pct >= 80) color = '#52c41a';
        else if (pct >= 50) color = '#faad14';
        else color = '#ff4d4f';
        return <Typography.Text style={{ color }}>{pct.toFixed(0)}%</Typography.Text>;
      },
      sorter: (a: SignalEvent, b: SignalEvent) => a.confidence - b.confidence,
    },
    {
      title: 'Reason',
      dataIndex: 'reason',
      key: 'reason',
      ellipsis: true,
    },
  ];

  const expandedRowRender = (record: SignalEvent) => {
    const entries = Object.entries(record.factorSnapshot ?? {});
    if (entries.length === 0) {
      return <Empty description="No factor snapshot" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
    }
    return (
      <Descriptions column={{ xs: 1, sm: 2, md: 3 }} size="small" bordered>
        {entries.map(([key, value]) => (
          <Descriptions.Item key={key} label={key}>
            <Typography.Text code>{Number(value).toFixed(4)}</Typography.Text>
          </Descriptions.Item>
        ))}
      </Descriptions>
    );
  };

  return (
    <div>
      <Space style={{ marginBottom: 16 }} align="center" wrap>
        <Typography.Title level={4} style={{ marginBottom: 0 }}>
          <AlertOutlined style={{ marginRight: 8 }} />
          Signals
        </Typography.Title>
      </Space>

      {/* Filters */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
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
          <Select
            value={strategyFilter}
            onChange={setStrategyFilter}
            placeholder="All Strategies"
            allowClear
            style={{ width: 180 }}
            size="small"
            options={uniqueStrategies.map((s) => ({ value: s, label: s }))}
          />
          <RangePicker
            size="small"
            showTime
            onChange={(dates) => {
              if (dates && dates[0] && dates[1]) {
                setTimeRange([dates[0], dates[1]]);
              } else {
                setTimeRange(null);
              }
            }}
            placeholder={['Start Time', 'End Time']}
          />
          {timeRange && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {filteredData.length} / {(data ?? []).length} signals shown
            </Typography.Text>
          )}
        </Space>
      </Card>

      {/* Signal Type Legend */}
      <Space style={{ marginBottom: 8 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>Legend:</Typography.Text>
        <Tag color="green" style={{ fontWeight: 'bold' }}>BUY</Tag>
        <Tag color="red" style={{ fontWeight: 'bold' }}>SELL</Tag>
        <Tag style={{ fontWeight: 'bold' }}>HOLD</Tag>
      </Space>

      <Card size="small">
        <Table<SignalEvent>
          dataSource={filteredData}
          columns={columns}
          rowKey={(r) => `${r.strategyName}-${r.timestamp}-${r.instrument.symbol}`}
          loading={isLoading}
          size="small"
          pagination={{ pageSize: 20, showSizeChanger: false }}
          scroll={{ x: 900 }}
          expandable={{
            expandedRowRender,
            rowExpandable: (record) => Object.keys(record.factorSnapshot ?? {}).length > 0,
          }}
        />
      </Card>
    </div>
  );
}
