import { Empty, Flex, Segmented, Statistic, Table, Typography, type TableColumnsType } from 'antd';
import ReactECharts from 'echarts-for-react';
import { useMemo, useState } from 'react';
import type {
  PaperAttribution,
  PaperEquityPoint,
  PaperExecutionQuality,
  PaperLedgerEntry,
} from '../../types';

interface Props {
  equity: PaperEquityPoint[];
  attribution: Record<string, PaperAttribution[]>;
  execution?: PaperExecutionQuality;
  ledger: PaperLedgerEntry[];
  loading: boolean;
}

export default function PaperAnalytics({ equity, attribution, execution, ledger, loading }: Props) {
  const [dimension, setDimension] = useState('strategy');
  const chartOption = useMemo(() => ({
    animation: false,
    grid: { left: 55, right: 18, top: 20, bottom: 40 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'time',
      axisLabel: { hideOverlap: true },
    },
    yAxis: { type: 'value', scale: true, name: 'Equity' },
    series: [{
      name: 'Equity',
      type: 'line',
      showSymbol: false,
      lineStyle: { width: 2, color: '#1677ff' },
      areaStyle: { color: 'rgba(22,119,255,0.10)' },
      data: equity.map((point) => [point.eventTimeMs, point.equity]),
    }],
  }), [equity]);

  const attributionColumns: TableColumnsType<PaperAttribution> = [
    { title: dimension[0].toUpperCase() + dimension.slice(1), dataIndex: 'key' },
    { title: 'Trades', dataIndex: 'trades' },
    { title: 'W / L', render: (_, row) => `${row.wins} / ${row.losses}` },
    { title: 'Win rate', dataIndex: 'winRatePct', render: (value) => `${Number(value).toFixed(2)}%` },
    { title: 'Gross PnL', dataIndex: 'grossPnl' },
    { title: 'Fees', dataIndex: 'fees' },
    { title: 'Funding', dataIndex: 'funding' },
    {
      title: 'Net PnL', dataIndex: 'netPnl',
      render: (value) => <Typography.Text style={{ color: Number(value) >= 0 ? '#237804' : '#a8071a' }}>{value}</Typography.Text>,
    },
    { title: 'Profit factor', dataIndex: 'profitFactor', render: (value) => Number(value).toFixed(3) },
  ];
  const ledgerColumns: TableColumnsType<PaperLedgerEntry> = [
    { title: 'Time', dataIndex: 'createTime', width: 180, render: (value) => new Date(value).toLocaleString() },
    { title: 'Transaction', dataIndex: 'transactionId', width: 160, ellipsis: true },
    { title: 'Ledger account', dataIndex: 'ledgerAccount' },
    { title: 'Asset', dataIndex: 'asset', width: 90 },
    { title: 'Debit', dataIndex: 'debit', width: 120 },
    { title: 'Credit', dataIndex: 'credit', width: 120 },
  ];

  return (
    <div>
      <Typography.Title level={5}>Equity curve</Typography.Title>
      {equity.length ? (
        <ReactECharts option={chartOption} style={{ height: 300, width: '100%' }} notMerge />
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No equity snapshots" />
      )}

      <Typography.Title level={5} style={{ marginTop: 24 }}>Execution quality</Typography.Title>
      <Flex gap={30} wrap>
        <Statistic title="Fills" value={execution?.fills ?? 0} />
        <Statistic title="Notional" value={execution?.notional ?? 0} precision={2} prefix="$" />
        <Statistic title="Fees" value={execution?.fees ?? 0} precision={4} prefix="$" />
        <Statistic title="Spread" value={execution?.avgSpreadBps ?? 0} precision={3} suffix="bps" />
        <Statistic title="Impact" value={execution?.avgImpactBps ?? 0} precision={3} suffix="bps" />
        <Statistic title="Slippage" value={execution?.avgSlippageBps ?? 0} precision={3} suffix="bps" />
        <Statistic title="Maker ratio" value={execution?.makerRatioPct ?? 0} precision={2} suffix="%" />
      </Flex>

      <Flex justify="space-between" align="center" style={{ marginTop: 26, marginBottom: 12 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>Attribution</Typography.Title>
        <Segmented
          value={dimension}
          options={['strategy', 'symbol', 'side', 'day']}
          onChange={(value) => setDimension(String(value))}
        />
      </Flex>
      <Table
        rowKey={(row) => `${row.dimension}-${row.key}`}
        columns={attributionColumns}
        dataSource={attribution[dimension] ?? []}
        loading={loading}
        size="small"
        pagination={false}
        scroll={{ x: 950 }}
      />

      <Typography.Title level={5} style={{ marginTop: 26 }}>Double-entry ledger</Typography.Title>
      <Table
        rowKey="entryId"
        columns={ledgerColumns}
        dataSource={ledger}
        loading={loading}
        size="small"
        scroll={{ x: 900 }}
        pagination={{ pageSize: 25 }}
      />
    </div>
  );
}
