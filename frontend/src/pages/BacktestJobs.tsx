import {
  Button,
  Col,
  Flex,
  Form,
  InputNumber,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from 'antd';
import { BarChartOutlined, CloseCircleOutlined, ExperimentOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  cancelBacktestJob,
  compareBacktestRuns,
  fetchBacktestJobs,
  fetchStrategies,
  submitBacktestJob,
} from '../api/admin';
import { notify } from '../feedback/notify';
import type { BacktestComparisonRow, BacktestJob, BacktestJobCommand } from '../types';

const activeStatuses = new Set(['QUEUED', 'RUNNING', 'CANCEL_REQUESTED']);

export default function BacktestJobs() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [selectedJobIds, setSelectedJobIds] = useState<string[]>([]);
  const strategiesQuery = useQuery({ queryKey: ['strategies'], queryFn: fetchStrategies });
  const jobsQuery = useQuery({
    queryKey: ['backtest-jobs'], queryFn: () => fetchBacktestJobs(undefined, 300), refetchInterval: 2000,
  });
  const submitMutation = useMutation({
    mutationFn: submitBacktestJob,
    onSuccess: () => {
      notify.success('Backtest job queued');
      void queryClient.invalidateQueries({ queryKey: ['backtest-jobs'] });
    },
  });
  const cancelMutation = useMutation({
    mutationFn: cancelBacktestJob,
    onSuccess: () => {
      notify.success('Cancellation requested');
      void queryClient.invalidateQueries({ queryKey: ['backtest-jobs'] });
    },
  });
  const comparisonMutation = useMutation({ mutationFn: compareBacktestRuns });
  const selectedRunIds = (jobsQuery.data ?? [])
    .filter((job) => selectedJobIds.includes(job.jobId) && job.resultId)
    .map((job) => job.resultId as string);

  const columns: TableColumnsType<BacktestJob> = [
    { title: 'Created', dataIndex: 'createdAtMs', width: 170, render: (value) => new Date(value).toLocaleString() },
    { title: 'Type', dataIndex: 'jobType', width: 90 },
    { title: 'Owner', dataIndex: 'createdBy', width: 100 },
    {
      title: 'Status', dataIndex: 'status', width: 140,
      render: (value) => <Tag color={value === 'COMPLETED' ? 'success' : value === 'FAILED' ? 'error' : value === 'CANCELLED' ? 'default' : 'processing'}>{value}</Tag>,
    },
    { title: 'Stage', dataIndex: 'stage', width: 150 },
    { title: 'Progress', dataIndex: 'progressPct', width: 190, render: (value) => <Progress percent={value} size="small" /> },
    { title: 'Seed', dataIndex: 'randomSeed', width: 100 },
    { title: 'Error', dataIndex: 'errorMessage', ellipsis: true },
    {
      title: '', fixed: 'right', width: 95,
      render: (_, row) => (
        <Space size={0}>
          {row.resultId && row.status === 'COMPLETED' && (
            <Button type="text" title="Open result" icon={<BarChartOutlined />} onClick={() => navigate('/backtest-results')} />
          )}
          {activeStatuses.has(row.status) && (
            <Button
              type="text"
              danger
              title="Cancel job"
              icon={<CloseCircleOutlined />}
              loading={cancelMutation.isPending}
              onClick={() => cancelMutation.mutate(row.jobId)}
            />
          )}
        </Space>
      ),
    },
  ];
  const comparisonColumns: TableColumnsType<BacktestComparisonRow> = [
    { title: '#', dataIndex: 'rank', width: 55 },
    { title: 'Strategy', dataIndex: 'strategyName' },
    { title: 'Instrument', render: (_, row) => `${row.exchange} · ${row.symbol} · ${row.timeframe}` },
    { title: 'Return', dataIndex: 'totalReturn', render: (value) => `${(Number(value) * 100).toFixed(2)}%` },
    { title: 'Max drawdown', dataIndex: 'maxDrawdown', render: (value) => `${(Number(value) * 100).toFixed(2)}%` },
    { title: 'Sharpe', dataIndex: 'sharpe', render: (value) => Number(value).toFixed(3) },
    { title: 'Sortino', dataIndex: 'sortino', render: (value) => Number(value).toFixed(3) },
    { title: 'Profit factor', dataIndex: 'profitFactor', render: (value) => Number(value).toFixed(3) },
    { title: 'Trades', dataIndex: 'totalTrades' },
    { title: 'Fees', dataIndex: 'totalFees' },
  ];

  return (
    <div>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>Backtest Jobs</Typography.Title>
        <Button icon={<ReloadOutlined />} loading={jobsQuery.isFetching} onClick={() => jobsQuery.refetch()} />
      </Flex>
      <Row gutter={[24, 24]}>
        <Col xs={24} xl={7}>
          <Typography.Title level={5}>Submit job</Typography.Title>
          <Form<BacktestJobCommand>
            layout="vertical"
            requiredMark={false}
            initialValues={{
              type: 'STRATEGY', strategyName: 'MacdCross', exchange: 'BINANCE',
              marketType: 'PERPETUAL', symbol: 'BTCUSDT', timeframe: '1h',
              days: 30, warmupBars: 200, initialBalance: 10_000,
              autoBackfill: true, randomSeed: 42,
            }}
            onFinish={(values) => submitMutation.mutate(values)}
          >
            <Form.Item name="strategyName" label="Strategy" rules={[{ required: true }]}>
              <Select options={(strategiesQuery.data ?? []).map((item) => ({ value: item.name, label: item.name }))} />
            </Form.Item>
            <Space.Compact block>
              <Form.Item name="exchange" label="Venue" style={{ width: '34%' }}>
                <Select options={['BINANCE', 'COINGLASS', 'OKX'].map((value) => ({ value, label: value }))} />
              </Form.Item>
              <Form.Item name="marketType" label="Market" style={{ width: '33%' }}>
                <Select options={['PERPETUAL', 'SPOT'].map((value) => ({ value, label: value }))} />
              </Form.Item>
              <Form.Item name="symbol" label="Symbol" style={{ width: '33%' }}>
                <Select options={['BTCUSDT', 'ETHUSDT'].map((value) => ({ value, label: value }))} />
              </Form.Item>
            </Space.Compact>
            <Space.Compact block>
              <Form.Item name="timeframe" label="Timeframe" style={{ width: '34%' }}>
                <Select options={['1m', '5m', '15m', '1h', '4h', '1d'].map((value) => ({ value, label: value }))} />
              </Form.Item>
              <Form.Item name="days" label="Days" style={{ width: '33%' }}>
                <InputNumber min={1} max={3650} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="warmupBars" label="Warmup" style={{ width: '33%' }}>
                <InputNumber min={0} max={10_000} style={{ width: '100%' }} />
              </Form.Item>
            </Space.Compact>
            <Space.Compact block>
              <Form.Item name="initialBalance" label="Capital" style={{ width: '50%' }}>
                <InputNumber min={100} step={1000} prefix="$" style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="randomSeed" label="Random seed" style={{ width: '50%' }}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Space.Compact>
            <Button type="primary" block htmlType="submit" icon={<ExperimentOutlined />} loading={submitMutation.isPending}>
              Queue backtest
            </Button>
          </Form>
        </Col>
        <Col xs={24} xl={17}>
          <Flex justify="space-between" align="center" style={{ marginBottom: 12 }}>
            <Typography.Title level={5} style={{ margin: 0 }}>Queue & history</Typography.Title>
            <Button
              disabled={selectedRunIds.length < 2}
              loading={comparisonMutation.isPending}
              onClick={() => comparisonMutation.mutate(selectedRunIds)}
            >
              Compare selected
            </Button>
          </Flex>
          <Table<BacktestJob>
            rowKey="jobId"
            columns={columns}
            dataSource={jobsQuery.data ?? []}
            loading={jobsQuery.isLoading}
            size="small"
            scroll={{ x: 1150 }}
            pagination={{ pageSize: 25 }}
            rowSelection={{
              selectedRowKeys: selectedJobIds,
              getCheckboxProps: (row) => ({ disabled: row.status !== 'COMPLETED' || !row.resultId }),
              onChange: (keys) => setSelectedJobIds(keys.map(String)),
            }}
          />
        </Col>
      </Row>
      {comparisonMutation.data && (
        <section style={{ marginTop: 28 }}>
          <Typography.Title level={5}>Comparison</Typography.Title>
          <Table rowKey="runId" columns={comparisonColumns} dataSource={comparisonMutation.data} size="small" scroll={{ x: 1050 }} pagination={false} />
        </section>
      )}
    </div>
  );
}
