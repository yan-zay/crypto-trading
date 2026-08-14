import { Button, Checkbox, Form, Input, InputNumber, Segmented, Select, Space, Typography } from 'antd';
import { DollarOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { placePaperOrder, updatePaperMark } from '../../api/admin';
import { notify } from '../../feedback/notify';
import type { PaperMark, PaperMarkCommand, PaperOrderCommand } from '../../types';

interface Props {
  accountId?: string;
  running: boolean;
  marks: PaperMark[];
  onChanged: () => void;
}

const instruments = ['BTCUSDT', 'ETHUSDT'].map((value) => ({ value, label: value }));

export default function PaperOrderTicket({ accountId, running, marks, onChanged }: Props) {
  const [mode, setMode] = useState<'ORDER' | 'MARK'>('ORDER');
  const [orderForm] = Form.useForm<PaperOrderCommand>();
  const orderType = Form.useWatch('orderType', orderForm);

  const markMutation = useMutation({
    mutationFn: updatePaperMark,
    onSuccess: (mark) => {
      notify.success(`Mark updated: ${mark.symbol} ${mark.price}`);
      onChanged();
    },
  });
  const orderMutation = useMutation({
    mutationFn: placePaperOrder,
    onSuccess: (order) => {
      if (order.status === 'REJECTED') notify.warning(`Order rejected: ${order.rejectReason}`);
      else notify.success(`Order ${order.status.toLowerCase()}`);
      onChanged();
    },
  });

  const currentMarks = marks.map((mark) => `${mark.exchange} ${mark.symbol} ${mark.marketType}: ${mark.price}`);

  return (
    <section style={{ minWidth: 320 }}>
      <Space style={{ marginBottom: 16 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>Paper ticket</Typography.Title>
        <Segmented
          size="small"
          value={mode}
          options={[{ value: 'ORDER', label: 'Order' }, { value: 'MARK', label: 'Market data' }]}
          onChange={(value) => setMode(value as 'ORDER' | 'MARK')}
        />
      </Space>

      {mode === 'ORDER' ? (
        <Form<PaperOrderCommand>
          form={orderForm}
          layout="vertical"
          requiredMark={false}
          initialValues={{
            strategyId: 'MANUAL', exchange: 'BINANCE', marketType: 'PERPETUAL',
            symbol: 'BTCUSDT', side: 'BUY', orderType: 'MARKET', quantity: 0.01,
            leverage: 2, reduceOnly: false,
          }}
          onFinish={(values) => orderMutation.mutate({ ...values, accountId })}
        >
          <Space.Compact block>
            <Form.Item name="exchange" label="Venue" style={{ width: '34%' }}>
              <Select options={['BINANCE', 'OKX'].map((value) => ({ value, label: value }))} />
            </Form.Item>
            <Form.Item name="marketType" label="Market" style={{ width: '33%' }}>
              <Select options={['PERPETUAL', 'SPOT'].map((value) => ({ value, label: value }))} />
            </Form.Item>
            <Form.Item name="symbol" label="Instrument" style={{ width: '33%' }}>
              <Select options={instruments} />
            </Form.Item>
          </Space.Compact>
          <Space.Compact block>
            <Form.Item name="side" label="Side" style={{ width: '34%' }}>
              <Select options={[{ value: 'BUY', label: 'Buy' }, { value: 'SELL', label: 'Sell' }]} />
            </Form.Item>
            <Form.Item name="orderType" label="Type" style={{ width: '33%' }}>
              <Select options={[{ value: 'MARKET', label: 'Market' }, { value: 'LIMIT', label: 'Limit' }]} />
            </Form.Item>
            <Form.Item name="leverage" label="Leverage" style={{ width: '33%' }}>
              <InputNumber min={1} max={125} suffix="x" style={{ width: '100%' }} />
            </Form.Item>
          </Space.Compact>
          <Space.Compact block>
            <Form.Item
              name="quantity"
              label="Quantity"
              rules={[{ required: true }]}
              style={{ width: orderType === 'LIMIT' ? '50%' : '100%' }}
            >
              <InputNumber min={0.000001} step={0.001} stringMode style={{ width: '100%' }} />
            </Form.Item>
            {orderType === 'LIMIT' && (
              <Form.Item name="limitPrice" label="Limit price" rules={[{ required: true }]} style={{ width: '50%' }}>
                <InputNumber min={0.01} step={10} prefix="$" style={{ width: '100%' }} />
              </Form.Item>
            )}
          </Space.Compact>
          <Form.Item name="strategyId" label="Strategy">
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="reduceOnly" valuePropName="checked">
            <Checkbox>Reduce only</Checkbox>
          </Form.Item>
          <Button
            data-testid="paper-order-submit"
            type="primary"
            htmlType="submit"
            icon={<SendOutlined />}
            block
            disabled={!running || !accountId}
            loading={orderMutation.isPending}
          >
            Place paper order
          </Button>
        </Form>
      ) : (
        <Form<PaperMarkCommand>
          layout="vertical"
          requiredMark={false}
          initialValues={{
            exchange: 'BINANCE', marketType: 'PERPETUAL', symbol: 'BTCUSDT',
            price: 60_000, baseVolume: 1,
          }}
          onFinish={(values) => markMutation.mutate(values)}
        >
          <Space.Compact block>
            <Form.Item name="exchange" label="Venue" style={{ width: '34%' }}>
              <Select options={['BINANCE', 'OKX'].map((value) => ({ value, label: value }))} />
            </Form.Item>
            <Form.Item name="marketType" label="Market" style={{ width: '33%' }}>
              <Select options={['PERPETUAL', 'SPOT'].map((value) => ({ value, label: value }))} />
            </Form.Item>
            <Form.Item name="symbol" label="Instrument" style={{ width: '33%' }}>
              <Select options={instruments} />
            </Form.Item>
          </Space.Compact>
          <Space.Compact block>
            <Form.Item name="price" label="Price" rules={[{ required: true }]} style={{ width: '50%' }}>
              <InputNumber min={0.01} step={10} prefix="$" style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="baseVolume" label="Base volume" rules={[{ required: true }]} style={{ width: '50%' }}>
              <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Space.Compact>
          <Button
            data-testid="paper-mark-submit"
            type="primary"
            htmlType="submit"
            icon={<DollarOutlined />}
            block
            loading={markMutation.isPending}
          >
            Publish mark
          </Button>
          <Typography.Paragraph type="secondary" ellipsis={{ rows: 3 }} style={{ marginTop: 12 }}>
            {currentMarks.length ? currentMarks.join(' · ') : 'No marks'}
          </Typography.Paragraph>
        </Form>
      )}
    </section>
  );
}
