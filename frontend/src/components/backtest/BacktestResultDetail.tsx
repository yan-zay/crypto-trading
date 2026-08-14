import { useState } from 'react';
import {
  Button,
  Alert,
  Col,
  Collapse,
  Descriptions,
  Divider,
  Dropdown,
  Row,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DownloadOutlined,
  FallOutlined,
  PercentageOutlined,
  SwapOutlined,
  TrophyOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { downloadBacktestReport, fetchBacktestResearchMetadata } from '../../api/admin';
import { notify } from '../../feedback/notify';
import type {
  BacktestResultDTO,
  BacktestSignalDTO,
  BacktestTradeDTO,
} from '../../types';
import EquityCurveChart from './EquityCurveChart';

interface BacktestResultDetailProps {
  result: BacktestResultDTO;
}

const percent = (value: number) => `${value >= 0 ? '+' : ''}${(value * 100).toFixed(2)}%`;
const duration = (milliseconds: number) => {
  if (milliseconds < 60_000) return `${Math.round(milliseconds / 1000)}s`;
  if (milliseconds < 3_600_000) return `${(milliseconds / 60_000).toFixed(1)}m`;
  return `${(milliseconds / 3_600_000).toFixed(1)}h`;
};

const tradeColumns: ColumnsType<BacktestTradeDTO> = [
  {
    title: 'Side', dataIndex: 'side', width: 76,
    render: (side: string) => <Tag color={side === 'LONG' ? 'green' : 'red'}>{side}</Tag>,
  },
  {
    title: 'Entry time', dataIndex: 'entryTime', width: 170,
    render: (timestamp: number) => new Date(timestamp).toLocaleString(),
  },
  {
    title: 'Exit time', dataIndex: 'exitTime', width: 170,
    render: (timestamp: number) => new Date(timestamp).toLocaleString(),
  },
  {
    title: 'Entry price', dataIndex: 'entryPrice', width: 120,
    render: (value: number) => value.toLocaleString(undefined, { maximumFractionDigits: 6 }),
  },
  {
    title: 'Exit price', dataIndex: 'exitPrice', width: 120,
    render: (value: number) => value.toLocaleString(undefined, { maximumFractionDigits: 6 }),
  },
  {
    title: 'Quantity', dataIndex: 'quantity', width: 105,
    render: (value: number) => value.toLocaleString(undefined, { maximumFractionDigits: 8 }),
  },
  {
    title: 'PnL', dataIndex: 'pnl', width: 110,
    sorter: (a, b) => a.pnl - b.pnl,
    render: (value: number) => (
      <Typography.Text type={value >= 0 ? 'success' : 'danger'}>
        {value >= 0 ? '+' : ''}{value.toFixed(2)}
      </Typography.Text>
    ),
  },
  {
    title: 'PnL %', dataIndex: 'pnlPct', width: 95,
    render: (value: number) => (
      <Typography.Text type={value >= 0 ? 'success' : 'danger'}>{percent(value)}</Typography.Text>
    ),
  },
  { title: 'Fees', dataIndex: 'fees', width: 90, render: (value: number) => value.toFixed(4) },
];

const signalColumns: ColumnsType<BacktestSignalDTO> = [
  {
    title: 'Time', dataIndex: 'timestamp', width: 180,
    render: (timestamp: number) => new Date(timestamp).toLocaleString(),
  },
  {
    title: 'Signal', dataIndex: 'type', width: 90,
    render: (type: string) => <Tag color={type === 'BUY' ? 'green' : type === 'SELL' ? 'red' : 'default'}>{type}</Tag>,
  },
  {
    title: 'Confidence', dataIndex: 'confidence', width: 105,
    render: (value: number) => `${(value * 100).toFixed(1)}%`,
  },
  { title: 'Reason', dataIndex: 'reason', ellipsis: true },
  {
    title: 'Factor snapshot', dataIndex: 'factorSnapshot', width: 300,
    render: (snapshot: Record<string, number>) => Object.entries(snapshot ?? {})
      .map(([name, value]) => `${name}=${Number(value).toFixed(4)}`).join(', '),
  },
];

export default function BacktestResultDetail({ result }: BacktestResultDetailProps) {
  const [downloading, setDownloading] = useState(false);
  const metadataQuery = useQuery({
    queryKey: ['backtest-research-metadata', result.id],
    queryFn: () => fetchBacktestResearchMetadata(result.id),
  });

  const exportReport = async (format: 'json' | 'csv' | 'markdown') => {
    setDownloading(true);
    try {
      await downloadBacktestReport(result.id, format);
      notify.success(`${format.toUpperCase()} report exported`);
    } catch {
      notify.error('Report export failed');
    } finally {
      setDownloading(false);
    }
  };

  const monthlyRows = Object.entries(result.monthlyReturnsPct ?? {}).map(([month, returnPct]) => ({
    month,
    returnPct,
  }));

  return (
    <section>
      <Row justify="space-between" align="middle" gutter={[12, 12]}>
        <Col>
          <Space>
            <TrophyOutlined />
            <Typography.Title level={5} style={{ margin: 0 }}>
              {result.strategyName} · {result.exchange} {result.marketType} · {result.symbol} {result.timeframe}
            </Typography.Title>
          </Space>
        </Col>
        <Col>
          <Dropdown
            menu={{
              items: [
                { key: 'json', label: 'JSON' },
                { key: 'csv', label: 'CSV' },
                { key: 'markdown', label: 'Markdown' },
              ],
              onClick: ({ key }) => exportReport(key as 'json' | 'csv' | 'markdown'),
            }}
          >
            <Button icon={<DownloadOutlined />} loading={downloading}>Export report</Button>
          </Dropdown>
        </Col>
      </Row>

      <Divider style={{ margin: '14px 0' }} />
      <Row gutter={[12, 16]}>
        <Col xs={12} md={6} xl={3}>
          <Statistic
            title="Total return"
            value={result.totalReturnPct * 100}
            precision={2}
            suffix="%"
            styles={{ content: { color: result.totalReturnPct >= 0 ? '#389e0d' : '#cf1322' } }}
          />
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Statistic title="Annualized" value={result.annualizedReturnPct * 100} precision={2} suffix="%" />
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Statistic title="Max drawdown" value={result.maxDrawdownPct * 100} precision={2} suffix="%" prefix={<FallOutlined />} />
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Statistic title="Win rate" value={result.winRatePct * 100} precision={1} suffix="%" prefix={<PercentageOutlined />} />
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Statistic title="Sharpe" value={result.sharpeRatio} precision={2} prefix={<SwapOutlined />} />
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Statistic title="Sortino" value={result.sortinoRatio} precision={2} />
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Statistic title="Calmar" value={result.calmarRatio} precision={2} />
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Statistic title="Profit factor" value={result.profitFactor} precision={2} />
        </Col>
      </Row>

      <Descriptions bordered size="small" column={{ xs: 1, sm: 2, lg: 4 }} style={{ marginTop: 18 }}>
        <Descriptions.Item label="Period">{result.startDate} ~ {result.endDate}</Descriptions.Item>
        <Descriptions.Item label="Initial / final">
          {result.initialCapital.toLocaleString()} / {result.finalCapital.toLocaleString()}
        </Descriptions.Item>
        <Descriptions.Item label="Trades / signals">{result.totalTrades} / {result.signalCount}</Descriptions.Item>
        <Descriptions.Item label="Wins / losses">{result.winningTrades} / {result.losingTrades}</Descriptions.Item>
        <Descriptions.Item label="Average win / loss">
          {percent(result.avgWinPct)} / {percent(result.avgLossPct)}
        </Descriptions.Item>
        <Descriptions.Item label="Win / loss streak">{result.maxWinStreak} / {result.maxLoseStreak}</Descriptions.Item>
        <Descriptions.Item label="Average duration">{duration(result.avgTradeDurationMs)}</Descriptions.Item>
        <Descriptions.Item label="Total fees">{result.totalFees.toFixed(4)}</Descriptions.Item>
      </Descriptions>

      <Tabs
        style={{ marginTop: 16 }}
        items={[
          {
            key: 'equity',
            label: 'Equity & monthly returns',
            children: (
              <Row gutter={[16, 16]}>
                <Col xs={24} xl={17}>
                  <EquityCurveChart points={result.equityCurve ?? []} />
                </Col>
                <Col xs={24} xl={7}>
                  <Table
                    rowKey="month"
                    size="small"
                    pagination={false}
                    dataSource={monthlyRows}
                    columns={[
                      { title: 'Month', dataIndex: 'month' },
                      {
                        title: 'Return', dataIndex: 'returnPct', align: 'right',
                        render: (value: number) => (
                          <Typography.Text type={value >= 0 ? 'success' : 'danger'}>{percent(value)}</Typography.Text>
                        ),
                      },
                    ]}
                  />
                </Col>
              </Row>
            ),
          },
          {
            key: 'signals',
            label: `Signals (${result.signalCount})`,
            children: (
              <Table
                rowKey={(_, index) => `signal-${index}`}
                size="small"
                dataSource={result.signals ?? []}
                columns={signalColumns}
                pagination={{ pageSize: 20, showSizeChanger: false }}
                scroll={{ x: 1000 }}
              />
            ),
          },
          {
            key: 'trades',
            label: `Trades (${result.totalTrades})`,
            children: (
              <Table
                rowKey={(_, index) => `trade-${index}`}
                size="small"
                dataSource={result.trades ?? []}
                columns={tradeColumns}
                pagination={{ pageSize: 20, showSizeChanger: false }}
                scroll={{ x: 1100 }}
              />
            ),
          },
          {
            key: 'research-quality',
            label: 'Research quality',
            children: metadataQuery.data ? (
              <div>
                <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 4 }}>
                  <Descriptions.Item label="Evidence grade">
                    <Tag color={metadataQuery.data.robustness?.evidenceGrade === 'STRONG' ? 'success' : 'warning'}>
                      {metadataQuery.data.robustness?.evidenceGrade ?? 'UNKNOWN'}
                    </Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="Closed trades">
                    {metadataQuery.data.robustness?.tradeCount ?? 0}
                  </Descriptions.Item>
                  <Descriptions.Item label="Bootstrap samples">
                    {metadataQuery.data.robustness?.bootstrapSamples ?? 0}
                  </Descriptions.Item>
                  <Descriptions.Item label="P(mean > 0)">
                    {((metadataQuery.data.robustness?.probabilityMeanPositive ?? 0) * 100).toFixed(2)}%
                  </Descriptions.Item>
                  <Descriptions.Item label="Mean return 95% CI">
                    [{percent(metadataQuery.data.robustness?.bootstrapMeanLower95 ?? 0)}, {' '}
                    {percent(metadataQuery.data.robustness?.bootstrapMeanUpper95 ?? 0)}]
                  </Descriptions.Item>
                  <Descriptions.Item label="Probabilistic Sharpe">
                    {(metadataQuery.data.robustness?.probabilisticSharpeRatio ?? 0).toFixed(4)}
                  </Descriptions.Item>
                  <Descriptions.Item label="Minimum track record">
                    {(metadataQuery.data.robustness?.minimumTrackRecordLength ?? 0).toFixed(1)}
                  </Descriptions.Item>
                  <Descriptions.Item label="Turnover">
                    {Number(metadataQuery.data.executionQuality?.turnover ?? 0).toFixed(4)}
                  </Descriptions.Item>
                </Descriptions>
                {(metadataQuery.data.robustness?.warnings ?? []).map((warning) => (
                  <Alert key={warning} type="warning" showIcon message={warning} style={{ marginTop: 10 }} />
                ))}
                <Collapse
                  style={{ marginTop: 14 }}
                  items={[
                    {
                      key: 'reproducibility',
                      label: 'Reproducibility manifest',
                      children: <pre>{JSON.stringify(metadataQuery.data.reproducibility, null, 2)}</pre>,
                    },
                    {
                      key: 'execution-quality',
                      label: 'Execution model quality',
                      children: <pre>{JSON.stringify(metadataQuery.data.executionQuality, null, 2)}</pre>,
                    },
                  ]}
                />
              </div>
            ) : (
              <Typography.Text type="secondary">
                {metadataQuery.isLoading ? 'Loading research metadata' : 'Research metadata unavailable'}
              </Typography.Text>
            ),
          },
        ]}
      />

      <Collapse
        size="small"
        items={[
          {
            key: 'strategy',
            label: 'Strategy configuration',
            children: <Typography.Paragraph copyable><pre>{result.strategyConfigJson}</pre></Typography.Paragraph>,
          },
          {
            key: 'assumptions',
            label: 'Backtest assumptions',
            children: <Typography.Paragraph copyable><pre>{result.assumptionsJson}</pre></Typography.Paragraph>,
          },
        ]}
      />
    </section>
  );
}
