import { useEffect, useMemo, useState } from 'react';
import { Button, Card, Descriptions, Empty, Form, Select, Space, Tabs, Typography } from 'antd';
import { PlayCircleOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  fetchBacktestCapabilities,
  fetchCoverage,
  fetchStrategies,
  runBacktest,
  runFactorBacktest,
  triggerBackfill,
} from '../api/admin';
import BacktestScopePanel from '../components/backtest/BacktestScopePanel';
import FactorStrategyEditor from '../components/backtest/FactorStrategyEditor';
import { notify } from '../feedback/notify';
import type { BacktestCapabilities, CoverageReport, FactorBacktestRequest } from '../types';

const fallbackCapabilities: BacktestCapabilities = {
  exchanges: ['BINANCE', 'COINGLASS', 'OKX'],
  marketTypes: ['SPOT', 'PERPETUAL'],
  symbols: ['BTCUSDT', 'ETHUSDT'],
  timeframes: ['1m', '5m', '15m', '1h', '4h', '1d'],
  factors: ['ADX', 'ATR', 'BB_PCT_B', 'EMA', 'MACD_HIST', 'RSI', 'SMA', 'SUPERTREND', 'VOLUME_CHANGE_PCT', 'VWAP'],
  operators: ['LT', 'LTE', 'GT', 'GTE', 'CROSS_ABOVE', 'CROSS_BELOW'],
  comparisonTargets: ['CONSTANT', 'PRICE', 'FACTOR'],
  matchModes: ['ALL', 'ANY', 'WEIGHTED'],
  positionModes: ['LONG_ONLY', 'LONG_SHORT'],
};

const initialRequest: FactorBacktestRequest = {
  exchange: 'BINANCE',
  marketType: 'PERPETUAL',
  symbol: 'BTCUSDT',
  timeframe: '1h',
  days: 30,
  warmupBars: 200,
  initialBalance: 10_000,
  autoBackfill: true,
  strategy: {
    name: 'RSI research',
    positionMode: 'LONG_ONLY',
    longEntry: {
      mode: 'ALL', minimumMatchRatio: 1,
      rules: [{ factorName: 'RSI', operator: 'LTE', target: 'CONSTANT', threshold: 30, weight: 1 }],
    },
    longExit: {
      mode: 'ALL', minimumMatchRatio: 1,
      rules: [{ factorName: 'RSI', operator: 'GTE', target: 'CONSTANT', threshold: 70, weight: 1 }],
    },
  },
};

export default function Backtests() {
  const queryClient = useQueryClient();
  const [request, setRequest] = useState<FactorBacktestRequest>(initialRequest);
  const [strategyName, setStrategyName] = useState('MacdCross');
  const [report, setReport] = useState<CoverageReport | null>(null);
  const capabilitiesQuery = useQuery({ queryKey: ['backtest-capabilities'], queryFn: fetchBacktestCapabilities });
  const strategiesQuery = useQuery({ queryKey: ['strategies'], queryFn: fetchStrategies });
  const capabilities = capabilitiesQuery.data ?? fallbackCapabilities;
  const factorOptions = useMemo(() => capabilities.factors, [capabilities.factors]);

  useEffect(() => {
    if (request.marketType === 'SPOT' && request.strategy.positionMode === 'LONG_SHORT') {
      setRequest((current) => ({
        ...current,
        strategy: { ...current.strategy, positionMode: 'LONG_ONLY', shortEntry: undefined, shortExit: undefined },
      }));
    }
  }, [request.marketType, request.strategy.positionMode]);

  const coverageMutation = useMutation({
    mutationFn: () => fetchCoverage(request.symbol, request.timeframe, request.days, request.exchange, request.marketType),
    onSuccess: setReport,
    onError: () => notify.error('Failed to fetch coverage'),
  });
  const backfillMutation = useMutation({
    mutationFn: () => triggerBackfill(request.symbol, request.timeframe, request.days, request.exchange, request.marketType),
    onSuccess: (data) => {
      notify.success(`Backfill complete: ${data.barsFilled} bars filled`);
      coverageMutation.mutate();
    },
    onError: () => notify.error('Backfill failed'),
  });
  const factorRunMutation = useMutation({
    mutationFn: () => runFactorBacktest(request),
    onSuccess: (data) => {
      notify.success(`Backtest complete: ${data.totalTrades} trades, ${(data.totalReturnPct * 100).toFixed(2)}% return`);
      queryClient.invalidateQueries({ queryKey: ['backtest-results'] });
    },
    onError: () => notify.error('Factor backtest failed'),
  });
  const presetRunMutation = useMutation({
    mutationFn: () => runBacktest({
      strategyName,
      exchange: request.exchange,
      marketType: request.marketType,
      symbol: request.symbol,
      timeframe: request.timeframe,
      days: request.days,
      warmupBars: request.warmupBars,
      initialBalance: request.initialBalance,
    }),
    onSuccess: (data) => {
      notify.success(`Backtest complete: ${data.totalTrades} trades, ${(data.totalReturnPct * 100).toFixed(2)}% return`);
      queryClient.invalidateQueries({ queryKey: ['backtest-results'] });
    },
    onError: () => notify.error('Preset backtest failed'),
  });

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 20 }}>Backtest Research</Typography.Title>
      <BacktestScopePanel
        value={request}
        capabilities={capabilities}
        checkingCoverage={coverageMutation.isPending}
        backfilling={backfillMutation.isPending}
        onChange={setRequest}
        onCheckCoverage={() => coverageMutation.mutate()}
        onBackfill={() => backfillMutation.mutate()}
      />
      <Card size="small" style={{ marginBottom: 16 }}>
        <Tabs items={[
          {
            key: 'factor',
            label: 'Factor strategy',
            children: (
              <FactorStrategyEditor
                value={request.strategy}
                marketType={request.marketType}
                factors={factorOptions}
                running={factorRunMutation.isPending}
                onChange={(strategy) => setRequest({ ...request, strategy })}
                onRun={() => factorRunMutation.mutate()}
              />
            ),
          },
          {
            key: 'preset',
            label: 'Preset strategy',
            children: (
              <Space wrap align="end">
                <Form.Item label="Strategy" style={{ marginBottom: 0 }}>
                  <Select
                    value={strategyName}
                    onChange={setStrategyName}
                    loading={strategiesQuery.isLoading}
                    style={{ width: 220 }}
                    options={(strategiesQuery.data ?? []).map((strategy) => ({ value: strategy.name, label: strategy.name }))}
                  />
                </Form.Item>
                <Button type="primary" icon={<PlayCircleOutlined />} loading={presetRunMutation.isPending} onClick={() => presetRunMutation.mutate()}>
                  Run preset backtest
                </Button>
              </Space>
            ),
          },
        ]} />
      </Card>
      {report ? (
        <Card size="small" title="Coverage report">
          <Descriptions column={{ xs: 1, sm: 2, md: 4 }} size="small">
            <Descriptions.Item label="Exchange">{report.exchange}</Descriptions.Item>
            <Descriptions.Item label="Market">{report.marketType}</Descriptions.Item>
            <Descriptions.Item label="Symbol">{report.symbol}</Descriptions.Item>
            <Descriptions.Item label="Timeframe">{report.timeframe}</Descriptions.Item>
            <Descriptions.Item label="Expected bars">{report.expectedBars.toLocaleString()}</Descriptions.Item>
            <Descriptions.Item label="Actual bars">{report.actualBars.toLocaleString()}</Descriptions.Item>
            <Descriptions.Item label="Coverage">
              <Typography.Text type={report.coveragePct >= 95 ? 'success' : 'danger'}>{report.coveragePct.toFixed(2)}%</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Gaps">{report.gaps.length}</Descriptions.Item>
          </Descriptions>
        </Card>
      ) : <Card size="small"><Empty description="No coverage check selected" /></Card>}
    </div>
  );
}
