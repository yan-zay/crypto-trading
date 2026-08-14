import { Table, Tag, Typography, type TableColumnsType } from 'antd';
import type { OmsFill, PaperTrade } from '../../types';

interface Props {
  fills: OmsFill[];
  trades: PaperTrade[];
  loading: boolean;
}

const colored = (value: number) => (
  <Typography.Text style={{ color: value >= 0 ? '#237804' : '#a8071a' }}>
    {value.toFixed(4)}
  </Typography.Text>
);

export default function PaperExecutions({ fills, trades, loading }: Props) {
  const fillColumns: TableColumnsType<OmsFill> = [
    { title: 'Time', dataIndex: 'fillTime', width: 168, render: (value) => new Date(value).toLocaleString() },
    { title: 'Order', dataIndex: 'orderId', width: 150, ellipsis: true },
    { title: 'Strategy', dataIndex: 'strategyId', width: 120 },
    { title: 'Price', dataIndex: 'fillPrice', width: 100 },
    { title: 'Quantity', dataIndex: 'fillQuantity', width: 100 },
    { title: 'Fee', dataIndex: 'fee', width: 90 },
    { title: 'Spread', dataIndex: 'spreadBps', width: 100, render: (value) => value == null ? '-' : `${value} bps` },
    { title: 'Impact', dataIndex: 'impactBps', width: 100, render: (value) => value == null ? '-' : `${value} bps` },
    { title: 'Slippage', dataIndex: 'slippageBps', width: 100, render: (value) => value == null ? '-' : `${value} bps` },
    { title: 'Role', dataIndex: 'liquidityRole', width: 90, render: (value) => <Tag>{value ?? '-'}</Tag> },
  ];
  const tradeColumns: TableColumnsType<PaperTrade> = [
    { title: 'Closed', dataIndex: 'closedAtMs', width: 168, render: (value) => new Date(value).toLocaleString() },
    { title: 'Strategy', dataIndex: 'strategyId', width: 120 },
    { title: 'Instrument', width: 180, render: (_, row) => `${row.exchange} · ${row.symbol}` },
    { title: 'Side', dataIndex: 'side', width: 80, render: (value) => <Tag color={value === 'LONG' ? 'green' : 'red'}>{value}</Tag> },
    { title: 'Quantity', dataIndex: 'quantity', width: 100 },
    { title: 'Entry', dataIndex: 'entryPrice', width: 100 },
    { title: 'Exit', dataIndex: 'exitPrice', width: 100 },
    { title: 'Gross PnL', dataIndex: 'grossPnl', width: 110, render: colored },
    { title: 'Fees', width: 90, render: (_, row) => (row.openFee + row.closeFee).toFixed(4) },
    { title: 'Funding', dataIndex: 'funding', width: 90 },
    { title: 'Net PnL', dataIndex: 'netPnl', width: 110, render: colored },
  ];

  return (
    <div>
      <Typography.Title level={5}>Fills</Typography.Title>
      <Table
        rowKey="fillId"
        columns={fillColumns}
        dataSource={fills}
        loading={loading}
        size="small"
        scroll={{ x: 1200 }}
        pagination={{ pageSize: 20 }}
      />
      <Typography.Title level={5} style={{ marginTop: 24 }}>Closed trades</Typography.Title>
      <Table
        rowKey="tradeId"
        columns={tradeColumns}
        dataSource={trades}
        loading={loading}
        size="small"
        scroll={{ x: 1250 }}
        pagination={{ pageSize: 20 }}
      />
    </div>
  );
}
