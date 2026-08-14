import { Button, Popconfirm, Space, Table, Tag, Tooltip, type TableColumnsType } from 'antd';
import { CloseCircleOutlined, EyeOutlined } from '@ant-design/icons';
import type { OmsOrder } from '../../types';

interface Props {
  orders: OmsOrder[];
  loading: boolean;
  cancelling: boolean;
  onInspect: (orderId: string) => void;
  onCancel: (orderId: string) => void;
}

const statusColors: Record<string, string> = {
  FILLED: 'success', PARTIALLY_FILLED: 'processing', REJECTED: 'error',
  CANCELLED: 'default', ACKNOWLEDGED: 'blue', SUBMITTED: 'cyan', UNKNOWN: 'warning',
};
const cancellable = new Set(['ACKNOWLEDGED', 'PARTIALLY_FILLED']);

export default function PaperOrdersTable({ orders, loading, cancelling, onInspect, onCancel }: Props) {
  const columns: TableColumnsType<OmsOrder> = [
    { title: 'Time', dataIndex: 'createdAtMs', width: 168, render: (value) => new Date(value).toLocaleString() },
    { title: 'Strategy', dataIndex: 'strategyId', width: 120, ellipsis: true },
    { title: 'Instrument', width: 190, render: (_, row) => `${row.exchange} · ${row.symbol} · ${row.marketType}` },
    {
      title: 'Action', width: 125,
      render: (_, row) => (
        <Space size={4}>
          <Tag color={row.tradeSide === 'BUY' ? 'green' : 'red'}>{row.tradeSide}</Tag>
          {row.reduceOnly && <Tag>Reduce</Tag>}
        </Space>
      ),
    },
    { title: 'Type', dataIndex: 'orderType', width: 90 },
    { title: 'Price', dataIndex: 'price', width: 100 },
    { title: 'Quantity', dataIndex: 'quantity', width: 100 },
    {
      title: 'Fill', width: 150,
      render: (_, row) => row.avgFillPrice == null
        ? `${row.filledQuantity} / ${row.quantity}`
        : `${row.filledQuantity} @ ${row.avgFillPrice}`,
    },
    { title: 'Status', dataIndex: 'status', width: 145, render: (value) => <Tag color={statusColors[value]}>{value}</Tag> },
    {
      title: '', fixed: 'right', width: 82,
      render: (_, row) => (
        <Space size={0}>
          <Tooltip title="Order details">
            <Button type="text" icon={<EyeOutlined />} onClick={() => onInspect(row.orderId)} />
          </Tooltip>
          {cancellable.has(row.status) && (
            <Popconfirm title="Cancel this order?" onConfirm={() => onCancel(row.orderId)}>
              <Tooltip title="Cancel order">
                <Button type="text" danger loading={cancelling} icon={<CloseCircleOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Table<OmsOrder>
      data-testid="paper-orders-table"
      rowKey="orderId"
      columns={columns}
      dataSource={orders}
      loading={loading}
      size="small"
      scroll={{ x: 1250 }}
      pagination={{ pageSize: 25, showSizeChanger: true }}
      onRow={(row) => ({ onDoubleClick: () => onInspect(row.orderId) })}
    />
  );
}
