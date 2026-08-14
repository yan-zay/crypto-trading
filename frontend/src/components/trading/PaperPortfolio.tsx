import { Empty, Table, Tag, Typography, type TableColumnsType } from 'antd';
import type { PaperBalance, PaperMark, PaperPosition } from '../../types';

interface Props {
  balances: PaperBalance[];
  positions: PaperPosition[];
  marks: PaperMark[];
  loading: boolean;
}

const pnl = (value: number) => (
  <Typography.Text style={{ color: value >= 0 ? '#237804' : '#a8071a' }}>
    {value.toFixed(2)}
  </Typography.Text>
);

export default function PaperPortfolio({ balances, positions, marks, loading }: Props) {
  const positionColumns: TableColumnsType<PaperPosition> = [
    { title: 'Instrument', render: (_, row) => `${row.exchange} · ${row.symbol}` },
    { title: 'Side', dataIndex: 'side', render: (value) => <Tag color={value === 'LONG' ? 'green' : 'red'}>{value}</Tag> },
    { title: 'Qty', dataIndex: 'quantity' },
    { title: 'Entry', dataIndex: 'entryPrice' },
    { title: 'Mark', dataIndex: 'markPrice' },
    { title: 'Lev', dataIndex: 'leverage', render: (value) => `${value}x` },
    { title: 'Margin', dataIndex: 'initialMargin' },
    { title: 'Unrealized', dataIndex: 'unrealizedPnl', render: pnl },
  ];
  const balanceColumns: TableColumnsType<PaperBalance> = [
    { title: 'Asset', dataIndex: 'asset' },
    { title: 'Total', dataIndex: 'totalBalance' },
    { title: 'Available', dataIndex: 'availableBalance' },
    { title: 'Locked', dataIndex: 'lockedBalance' },
  ];
  const markColumns: TableColumnsType<PaperMark> = [
    { title: 'Instrument', render: (_, row) => `${row.exchange} · ${row.symbol} · ${row.marketType}` },
    { title: 'Price', dataIndex: 'price' },
    { title: 'Volume', dataIndex: 'baseVolume' },
    { title: 'Age', dataIndex: 'eventTimeMs', render: (value) => `${Math.max(0, Math.round((Date.now() - value) / 1000))}s` },
  ];

  return (
    <section>
      <Typography.Title level={5}>Positions</Typography.Title>
      <Table
        rowKey="positionId"
        columns={positionColumns}
        dataSource={positions}
        loading={loading}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No open positions" /> }}
        size="small"
        pagination={false}
        scroll={{ x: 850 }}
      />
      <Typography.Title level={5} style={{ marginTop: 20 }}>Balances</Typography.Title>
      <Table rowKey="asset" columns={balanceColumns} dataSource={balances} size="small" pagination={false} />
      <Typography.Title level={5} style={{ marginTop: 20 }}>Market marks</Typography.Title>
      <Table
        rowKey={(row) => `${row.exchange}-${row.marketType}-${row.symbol}`}
        columns={markColumns}
        dataSource={marks}
        size="small"
        pagination={false}
      />
    </section>
  );
}
