import { Card, Typography, Empty, Form, Input, InputNumber, Button, Space, message, Descriptions } from 'antd';
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { triggerBackfill, fetchCoverage } from '../api/admin';
import type { CoverageReport } from '../types';

export default function Backtests() {
  const [symbol, setSymbol] = useState('BTCUSDT');
  const [timeframe, setTimeframe] = useState('1m');
  const [days, setDays] = useState(30);
  const [report, setReport] = useState<CoverageReport | null>(null);

  const coverageMutation = useMutation({
    mutationFn: () => fetchCoverage(symbol, timeframe, days),
    onSuccess: (data) => {
      setReport(data);
    },
    onError: () => message.error('Failed to fetch coverage'),
  });

  const backfillMutation = useMutation({
    mutationFn: () => triggerBackfill(symbol, timeframe, days),
    onSuccess: (data) => {
      message.success(`Backfill complete: ${data.barsFilled} bars filled`);
      coverageMutation.mutate(); // refresh coverage
    },
    onError: () => message.error('Backfill failed'),
  });

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        Data Coverage & Backfill
      </Typography.Title>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline" style={{ flexWrap: 'wrap', gap: 8 }}>
          <Form.Item label="Symbol">
            <Input value={symbol} onChange={(e) => setSymbol(e.target.value.toUpperCase())} style={{ width: 140 }} placeholder="BTCUSDT" />
          </Form.Item>
          <Form.Item label="Timeframe">
            <Input value={timeframe} onChange={(e) => setTimeframe(e.target.value)} style={{ width: 80 }} placeholder="1m" />
          </Form.Item>
          <Form.Item label="Days">
            <InputNumber value={days} onChange={(v) => setDays(v ?? 30)} min={1} max={365} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button onClick={() => coverageMutation.mutate()} loading={coverageMutation.isPending}>
                Check Coverage
              </Button>
              <Button type="primary" onClick={() => backfillMutation.mutate()} loading={backfillMutation.isPending}>
                Backfill
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {report && (
        <Card size="small" title="Coverage Report">
          <Descriptions column={{ xs: 1, sm: 2, md: 4 }} size="small">
            <Descriptions.Item label="Symbol">{report.symbol}</Descriptions.Item>
            <Descriptions.Item label="Timeframe">{report.timeframe}</Descriptions.Item>
            <Descriptions.Item label="Expected Bars">{report.expectedBars.toLocaleString()}</Descriptions.Item>
            <Descriptions.Item label="Actual Bars">{report.actualBars.toLocaleString()}</Descriptions.Item>
            <Descriptions.Item label="Coverage">
              <Typography.Text type={report.coveragePct >= 95 ? 'success' : 'danger'}>
                {report.coveragePct.toFixed(2)}%
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Gaps">{report.gaps.length}</Descriptions.Item>
          </Descriptions>
        </Card>
      )}

      {!report && !coverageMutation.isPending && (
        <Card size="small">
          <Empty description="Enter a symbol and check coverage to see results" />
        </Card>
      )}
    </div>
  );
}
