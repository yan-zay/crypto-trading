import {
  Button,
  Card,
  Col,
  Form,
  InputNumber,
  Row,
  Select,
  Space,
  Switch,
  Typography,
} from 'antd';
import { DatabaseOutlined, SearchOutlined } from '@ant-design/icons';
import type { BacktestCapabilities, FactorBacktestRequest } from '../../types';

interface BacktestScopePanelProps {
  value: FactorBacktestRequest;
  capabilities: BacktestCapabilities;
  checkingCoverage: boolean;
  backfilling: boolean;
  onChange: (value: FactorBacktestRequest) => void;
  onCheckCoverage: () => void;
  onBackfill: () => void;
}

export default function BacktestScopePanel({
  value,
  capabilities,
  checkingCoverage,
  backfilling,
  onChange,
  onCheckCoverage,
  onBackfill,
}: BacktestScopePanelProps) {
  return (
    <Card size="small" title="Dataset & execution scope" style={{ marginBottom: 16 }}>
      <Form layout="vertical">
        <Row gutter={[12, 0]}>
          <Col xs={12} sm={8} lg={4}>
            <Form.Item label="Exchange">
              <Select
                value={value.exchange}
                onChange={(exchange) => onChange({ ...value, exchange })}
                options={capabilities.exchanges.map((item) => ({ value: item, label: item }))}
              />
            </Form.Item>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Form.Item label="Market">
              <Select
                value={value.marketType}
                onChange={(marketType) => onChange({ ...value, marketType })}
                options={capabilities.marketTypes.map((item) => ({ value: item, label: item }))}
              />
            </Form.Item>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Form.Item label="Symbol">
              <Select
                value={value.symbol}
                onChange={(symbol) => onChange({ ...value, symbol })}
                options={capabilities.symbols.map((item) => ({ value: item, label: item }))}
              />
            </Form.Item>
          </Col>
          <Col xs={12} sm={8} lg={3}>
            <Form.Item label="Timeframe">
              <Select
                value={value.timeframe}
                onChange={(timeframe) => onChange({ ...value, timeframe })}
                options={capabilities.timeframes.map((item) => ({ value: item, label: item }))}
              />
            </Form.Item>
          </Col>
          <Col xs={12} sm={8} lg={3}>
            <Form.Item label="Days">
              <InputNumber
                min={1}
                max={3650}
                value={value.days}
                onChange={(days) => onChange({ ...value, days: days ?? 30 })}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={12} sm={8} lg={3}>
            <Form.Item label="Warmup bars">
              <InputNumber
                min={1}
                max={10_000}
                value={value.warmupBars}
                onChange={(warmupBars) => onChange({ ...value, warmupBars: warmupBars ?? 200 })}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={12} sm={8} lg={3}>
            <Form.Item label="Initial balance">
              <InputNumber
                min={100}
                value={value.initialBalance}
                onChange={(initialBalance) => onChange({ ...value, initialBalance: initialBalance ?? 10_000 })}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
        </Row>
        <Space wrap>
          <Button icon={<SearchOutlined />} onClick={onCheckCoverage} loading={checkingCoverage}>
            Check coverage
          </Button>
          <Button icon={<DatabaseOutlined />} onClick={onBackfill} loading={backfilling}>
            Backfill
          </Button>
          <Space>
            <Switch
              checked={value.autoBackfill}
              onChange={(autoBackfill) => onChange({ ...value, autoBackfill })}
            />
            <Typography.Text>Auto-backfill before factor run</Typography.Text>
          </Space>
        </Space>
      </Form>
    </Card>
  );
}
