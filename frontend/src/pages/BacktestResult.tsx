import { useState } from 'react';
import { Card, Table, Tag, Typography, Space, Statistic, Row, Col, Select, Empty, Descriptions, Divider } from 'antd';
import {
  TrophyOutlined,
  FallOutlined,
  PercentageOutlined,
  BarChartOutlined,
  SwapOutlined,
  DollarOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchBacktestResults } from '../api/admin';
import type { BacktestResultDTO, BacktestTradeDTO } from '../types';

export default function BacktestResult() {
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const { data: results, isLoading } = useQuery({
    queryKey: ['backtest-results'],
    queryFn: fetchBacktestResults,
  });

  const selectedResult = results?.find((r) => r.id === selectedId) ?? null;

  const tradeColumns = [
    {
      title: '#',
      key: 'index',
      width: 50,
      render: (_: unknown, __: unknown, index: number) => index + 1,
    },
    {
      title: 'Side',
      dataIndex: 'side',
      key: 'side',
      width: 80,
      render: (side: string) => (
        <Tag color={side === 'LONG' ? 'green' : 'red'}>{side}</Tag>
      ),
    },
    {
      title: 'Entry Time',
      dataIndex: 'entryTime',
      key: 'entryTime',
      width: 170,
      render: (ts: number) => new Date(ts).toLocaleString(),
    },
    {
      title: 'Exit Time',
      dataIndex: 'exitTime',
      key: 'exitTime',
      width: 170,
      render: (ts: number) => new Date(ts).toLocaleString(),
    },
    {
      title: 'Entry Price',
      dataIndex: 'entryPrice',
      key: 'entryPrice',
      width: 120,
      render: (v: number) => v.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
    },
    {
      title: 'Exit Price',
      dataIndex: 'exitPrice',
      key: 'exitPrice',
      width: 120,
      render: (v: number) => v.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
    },
    {
      title: 'Quantity',
      dataIndex: 'quantity',
      key: 'quantity',
      width: 100,
      render: (v: number) => v.toFixed(4),
    },
    {
      title: 'PnL',
      dataIndex: 'pnl',
      key: 'pnl',
      width: 110,
      render: (v: number) => (
        <Typography.Text style={{ color: v >= 0 ? '#3f8600' : '#cf1322' }}>
          {v >= 0 ? '+' : ''}{v.toFixed(2)}
        </Typography.Text>
      ),
      sorter: (a: BacktestTradeDTO, b: BacktestTradeDTO) => a.pnl - b.pnl,
    },
    {
      title: 'PnL %',
      dataIndex: 'pnlPct',
      key: 'pnlPct',
      width: 90,
      render: (v: number) => (
        <Typography.Text style={{ color: v >= 0 ? '#3f8600' : '#cf1322' }}>
          {v >= 0 ? '+' : ''}{(v * 100).toFixed(2)}%
        </Typography.Text>
      ),
    },
    {
      title: 'Fees',
      dataIndex: 'fees',
      key: 'fees',
      width: 80,
      render: (v: number) => v.toFixed(2),
    },
  ];

  const summaryColumns = [
    {
      title: 'Strategy',
      dataIndex: 'strategyName',
      key: 'strategyName',
      render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
    },
    {
      title: 'Symbol',
      dataIndex: 'symbol',
      key: 'symbol',
      width: 120,
    },
    {
      title: 'Timeframe',
      dataIndex: 'timeframe',
      key: 'timeframe',
      width: 100,
    },
    {
      title: 'Period',
      key: 'period',
      width: 200,
      render: (_: unknown, record: BacktestResultDTO) =>
        `${record.startDate} ~ ${record.endDate}`,
    },
    {
      title: 'Return',
      dataIndex: 'totalReturnPct',
      key: 'totalReturnPct',
      width: 110,
      render: (v: number) => (
        <Typography.Text style={{ color: v >= 0 ? '#3f8600' : '#cf1322', fontWeight: 'bold' }}>
          {v >= 0 ? '+' : ''}{(v * 100).toFixed(2)}%
        </Typography.Text>
      ),
      sorter: (a: BacktestResultDTO, b: BacktestResultDTO) => a.totalReturnPct - b.totalReturnPct,
      defaultSortOrder: 'descend' as const,
    },
    {
      title: 'Win Rate',
      dataIndex: 'winRatePct',
      key: 'winRatePct',
      width: 100,
      render: (v: number) => `${(v * 100).toFixed(1)}%`,
    },
    {
      title: 'Sharpe',
      dataIndex: 'sharpeRatio',
      key: 'sharpeRatio',
      width: 80,
      render: (v: number) => v.toFixed(2),
    },
    {
      title: 'Trades',
      dataIndex: 'totalTrades',
      key: 'totalTrades',
      width: 80,
    },
  ];

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        <BarChartOutlined style={{ marginRight: 8 }} />
        Backtest Results
      </Typography.Title>

      {/* Summary Table */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space style={{ marginBottom: 12 }}>
          <Typography.Text type="secondary">Select a backtest to view details:</Typography.Text>
          <Select
            value={selectedId}
            onChange={setSelectedId}
            placeholder="Select backtest result"
            allowClear
            style={{ width: 300 }}
            size="small"
            loading={isLoading}
            options={(results ?? []).map((r) => ({
              value: r.id,
              label: `${r.strategyName} - ${r.symbol} (${r.startDate} ~ ${r.endDate})`,
            }))}
          />
        </Space>
        <Table<BacktestResultDTO>
          dataSource={results ?? []}
          columns={summaryColumns}
          rowKey="id"
          loading={isLoading}
          size="small"
          pagination={false}
          locale={{ emptyText: <Empty description="No backtest results available" /> }}
          onRow={(record) => ({
            style: { cursor: 'pointer' },
            onClick: () => setSelectedId(record.id),
          })}
        />
      </Card>

      {/* Detail View */}
      {selectedResult && (
        <Card
          title={
            <Space>
              <TrophyOutlined />
              <span>
                {selectedResult.strategyName} — {selectedResult.symbol} ({selectedResult.timeframe})
              </span>
            </Space>
          }
          size="small"
        >
          {/* Performance Metrics */}
          <Row gutter={[16, 16]}>
            <Col xs={12} sm={6}>
              <Statistic
                title="Total Return"
                value={selectedResult.totalReturnPct * 100}
                precision={2}
                suffix="%"
                prefix={<DollarOutlined />}
                valueStyle={{ color: selectedResult.totalReturnPct >= 0 ? '#3f8600' : '#cf1322' }}
              />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic
                title="Max Drawdown"
                value={selectedResult.maxDrawdownPct * 100}
                precision={2}
                suffix="%"
                prefix={<FallOutlined />}
                valueStyle={{ color: '#cf1322' }}
              />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic
                title="Win Rate"
                value={selectedResult.winRatePct * 100}
                precision={1}
                suffix="%"
                prefix={<PercentageOutlined />}
                valueStyle={{ color: selectedResult.winRatePct >= 0.5 ? '#3f8600' : '#cf1322' }}
              />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic
                title="Sharpe Ratio"
                value={selectedResult.sharpeRatio}
                precision={2}
                prefix={<SwapOutlined />}
                valueStyle={{ color: selectedResult.sharpeRatio >= 1 ? '#3f8600' : '#cf1322' }}
              />
            </Col>
          </Row>

          <Divider style={{ margin: '16px 0' }} />

          {/* Additional Metrics */}
          <Descriptions column={{ xs: 1, sm: 2, md: 4 }} size="small" bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label="Initial Capital">
              ${selectedResult.initialCapital.toLocaleString()}
            </Descriptions.Item>
            <Descriptions.Item label="Final Capital">
              <Typography.Text style={{ color: selectedResult.finalCapital >= selectedResult.initialCapital ? '#3f8600' : '#cf1322' }}>
                ${selectedResult.finalCapital.toLocaleString()}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Total Trades">{selectedResult.totalTrades}</Descriptions.Item>
            <Descriptions.Item label="Win / Loss">
              <Typography.Text style={{ color: '#3f8600' }}>{selectedResult.winningTrades}</Typography.Text>
              {' / '}
              <Typography.Text style={{ color: '#cf1322' }}>{selectedResult.losingTrades}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Avg Win">
              <Typography.Text style={{ color: '#3f8600' }}>
                +{(selectedResult.avgWinPct * 100).toFixed(2)}%
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Avg Loss">
              <Typography.Text style={{ color: '#cf1322' }}>
                {(selectedResult.avgLossPct * 100).toFixed(2)}%
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Profit Factor">
              <Typography.Text style={{ color: selectedResult.profitFactor >= 1 ? '#3f8600' : '#cf1322' }}>
                {selectedResult.profitFactor.toFixed(2)}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Period">
              {selectedResult.startDate} ~ {selectedResult.endDate}
            </Descriptions.Item>
          </Descriptions>

          {/* Trade List */}
          <Table<BacktestTradeDTO>
            dataSource={selectedResult.trades ?? []}
            columns={tradeColumns}
            rowKey={(_, i) => `trade-${i}`}
            size="small"
            pagination={{ pageSize: 20, showSizeChanger: false }}
            scroll={{ x: 1100 }}
          />
        </Card>
      )}

      {!selectedResult && !isLoading && (
        <Card size="small">
          <Empty description="Select a backtest result above to view details" />
        </Card>
      )}
    </div>
  );
}
