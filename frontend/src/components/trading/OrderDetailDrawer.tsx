import { Descriptions, Drawer, Table, Typography, type TableColumnsType } from 'antd';
import type { OmsFill, OmsOrderDetail, OmsOrderEvent } from '../../types';

interface Props {
  open: boolean;
  loading: boolean;
  detail?: OmsOrderDetail;
  onClose: () => void;
}

export default function OrderDetailDrawer({ open, loading, detail, onClose }: Props) {
  const eventColumns: TableColumnsType<OmsOrderEvent> = [
    { title: 'Time', dataIndex: 'eventTime', render: (value) => new Date(value).toLocaleString() },
    { title: 'Event', dataIndex: 'eventType' },
    { title: 'Status', dataIndex: 'orderStatus' },
    { title: 'Fill price', dataIndex: 'fillPrice' },
    { title: 'Fill qty', dataIndex: 'fillQuantity' },
    { title: 'Reject reason', dataIndex: 'rejectReason' },
  ];
  const fillColumns: TableColumnsType<OmsFill> = [
    { title: 'Time', dataIndex: 'fillTime', render: (value) => new Date(value).toLocaleString() },
    { title: 'Price', dataIndex: 'fillPrice' },
    { title: 'Quantity', dataIndex: 'fillQuantity' },
    { title: 'Fee', dataIndex: 'fee' },
    { title: 'Slippage', dataIndex: 'slippageBps', render: (value) => value == null ? '-' : `${value} bps` },
    { title: 'Liquidity', dataIndex: 'liquidityRole' },
  ];

  return (
    <Drawer title="Order details" size="large" open={open} loading={loading} onClose={onClose}>
      {detail && (
        <>
          <Descriptions size="small" column={2} bordered>
            <Descriptions.Item label="Order ID">{detail.order.orderId}</Descriptions.Item>
            <Descriptions.Item label="Client ID">{detail.order.clientOrderId}</Descriptions.Item>
            <Descriptions.Item label="Account">{detail.order.accountId}</Descriptions.Item>
            <Descriptions.Item label="Correlation">{detail.order.correlationId ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="Strategy">{detail.order.strategyId}</Descriptions.Item>
            <Descriptions.Item label="Instrument">{detail.order.exchange} · {detail.order.symbol}</Descriptions.Item>
            <Descriptions.Item label="Direction">{detail.order.tradeSide} {detail.order.positionSide}</Descriptions.Item>
            <Descriptions.Item label="Reduce only">{detail.order.reduceOnly ? 'Yes' : 'No'}</Descriptions.Item>
            <Descriptions.Item label="Quantity">{detail.order.quantity}</Descriptions.Item>
            <Descriptions.Item label="Average fill">{detail.order.avgFillPrice ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="Status">{detail.order.status}</Descriptions.Item>
            <Descriptions.Item label="Reject reason">{detail.order.rejectReason ?? '-'}</Descriptions.Item>
          </Descriptions>
          <Typography.Title level={5} style={{ marginTop: 20 }}>Lifecycle</Typography.Title>
          <Table rowKey="eventId" columns={eventColumns} dataSource={detail.events} pagination={false} size="small" />
          <Typography.Title level={5} style={{ marginTop: 20 }}>Fills</Typography.Title>
          <Table rowKey="fillId" columns={fillColumns} dataSource={detail.fills} pagination={false} size="small" />
        </>
      )}
    </Drawer>
  );
}
