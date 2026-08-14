import { useEffect, useState } from 'react';
import { Alert, Card, Empty, Select, Space, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { BarChartOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchBacktestResult, fetchBacktestResults } from '../api/admin';
import BacktestResultDetail from '../components/backtest/BacktestResultDetail';
import type { BacktestResultDTO } from '../types';

const summaryColumns: ColumnsType<BacktestResultDTO> = [
  {
    title: 'Strategy', dataIndex: 'strategyName',
    render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
  },
  { title: 'Exchange', dataIndex: 'exchange', width: 110 },
  { title: 'Market', dataIndex: 'marketType', width: 110 },
  { title: 'Symbol', dataIndex: 'symbol', width: 110 },
  { title: 'Timeframe', dataIndex: 'timeframe', width: 90 },
  {
    title: 'Period', key: 'period', width: 210,
    render: (_, result) => `${result.startDate} ~ ${result.endDate}`,
  },
  {
    title: 'Return', dataIndex: 'totalReturnPct', width: 105,
    defaultSortOrder: 'descend',
    sorter: (a, b) => a.totalReturnPct - b.totalReturnPct,
    render: (value: number) => (
      <Typography.Text type={value >= 0 ? 'success' : 'danger'} strong>
        {value >= 0 ? '+' : ''}{(value * 100).toFixed(2)}%
      </Typography.Text>
    ),
  },
  {
    title: 'Win rate', dataIndex: 'winRatePct', width: 95,
    render: (value: number) => `${(value * 100).toFixed(1)}%`,
  },
  { title: 'Sharpe', dataIndex: 'sharpeRatio', width: 80, render: (value: number) => value.toFixed(2) },
  { title: 'Trades', dataIndex: 'totalTrades', width: 80 },
];

export default function BacktestResult() {
  const [selectedId, setSelectedId] = useState<string>();
  const resultsQuery = useQuery({
    queryKey: ['backtest-results'],
    queryFn: fetchBacktestResults,
  });
  const detailQuery = useQuery({
    queryKey: ['backtest-result', selectedId],
    queryFn: () => fetchBacktestResult(selectedId!),
    enabled: Boolean(selectedId),
  });

  useEffect(() => {
    if (!selectedId && resultsQuery.data?.length) setSelectedId(resultsQuery.data[0].id);
  }, [resultsQuery.data, selectedId]);

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 20 }}>
        <BarChartOutlined style={{ marginRight: 8 }} />
        Backtest Results
      </Typography.Title>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Typography.Text type="secondary">Research run</Typography.Text>
          <Select
            value={selectedId}
            onChange={setSelectedId}
            placeholder="Select result"
            loading={resultsQuery.isLoading}
            style={{ width: 360, maxWidth: '100%' }}
            options={(resultsQuery.data ?? []).map((result) => ({
              value: result.id,
              label: `${result.strategyName} · ${result.exchange} ${result.symbol} · ${result.startDate}`,
            }))}
          />
        </Space>
        <Table
          rowKey="id"
          size="small"
          loading={resultsQuery.isLoading}
          dataSource={resultsQuery.data ?? []}
          columns={summaryColumns}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 1180 }}
          locale={{ emptyText: <Empty description="No backtest results" /> }}
          onRow={(result) => ({
            style: { cursor: 'pointer' },
            onClick: () => setSelectedId(result.id),
          })}
        />
      </Card>

      {detailQuery.error && <Alert type="error" showIcon message="Failed to load backtest detail" />}
      {selectedId && (
        <Card size="small" loading={detailQuery.isLoading}>
          {detailQuery.data && <BacktestResultDetail result={detailQuery.data} />}
        </Card>
      )}
      {!selectedId && !resultsQuery.isLoading && (
        <Card size="small"><Empty description="Run or select a backtest to inspect its report" /></Card>
      )}
    </div>
  );
}
