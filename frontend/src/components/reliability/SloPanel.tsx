import { Flex, Progress, Select, Statistic, Table, Tag, Typography, type TableColumnsType } from 'antd';
import { useQuery } from '@tanstack/react-query';
import ReactECharts from 'echarts-for-react';
import { useMemo, useState } from 'react';
import { fetchCurrentSlos, fetchSloHistory } from '../../api/admin';
import type { SloStatus } from '../../types';

export default function SloPanel() {
  const [selected, setSelected] = useState<string>();
  const currentQuery = useQuery({
    queryKey: ['slo-current'], queryFn: fetchCurrentSlos, refetchInterval: 10_000,
  });
  const selectedName = selected ?? currentQuery.data?.[0]?.name;
  const historyQuery = useQuery({
    queryKey: ['slo-history', selectedName],
    queryFn: () => fetchSloHistory(selectedName, 240),
    enabled: Boolean(selectedName),
    refetchInterval: 60_000,
  });
  const breached = currentQuery.data?.filter((item) => item.state === 'BREACHED').length ?? 0;
  const noData = currentQuery.data?.filter((item) => item.state === 'NO_DATA').length ?? 0;
  const chartOption = useMemo(() => ({
    animation: false,
    grid: { left: 55, right: 18, top: 20, bottom: 40 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', min: 0, max: 100, name: '%' },
    series: [{
      name: 'Actual', type: 'line', showSymbol: false,
      data: [...(historyQuery.data ?? [])].reverse()
        .filter((row) => row.actualValue != null)
        .map((row) => [row.windowEndMs, Number(row.actualValue) * 100]),
    }],
  }), [historyQuery.data]);

  const columns: TableColumnsType<SloStatus> = [
    { title: 'SLO', dataIndex: 'name', width: 240 },
    {
      title: 'State', dataIndex: 'state', width: 120,
      render: (value) => <Tag color={value === 'COMPLIANT' ? 'success' : value === 'BREACHED' ? 'error' : 'default'}>{value}</Tag>,
    },
    { title: 'Target', dataIndex: 'targetValue', width: 100, render: (value) => `${(Number(value) * 100).toFixed(2)}%` },
    { title: 'Actual', dataIndex: 'actualValue', width: 100, render: (value) => value == null ? '-' : `${(Number(value) * 100).toFixed(3)}%` },
    { title: 'Samples', dataIndex: 'sampleCount', width: 90 },
    { title: 'Failures', dataIndex: 'failureCount', width: 90 },
    { title: 'Avg latency', dataIndex: 'averageLatencyMs', width: 120, render: (value) => `${Number(value).toFixed(1)} ms` },
    { title: 'Max latency', dataIndex: 'maxLatencyMs', width: 110, render: (value) => `${value} ms` },
    {
      title: 'Error budget', dataIndex: 'errorBudgetRemainingPct', width: 180,
      render: (value) => value == null ? '-' : (
        <Progress
          percent={Math.max(0, Math.min(100, Number(value)))}
          status={Number(value) < 0 ? 'exception' : 'normal'}
          size="small"
          format={() => `${Number(value).toFixed(1)}%`}
        />
      ),
    },
  ];

  return (
    <div>
      <Flex gap={32} wrap style={{ marginBottom: 18 }}>
        <Statistic title="Objectives" value={currentQuery.data?.length ?? 0} />
        <Statistic title="Breached" value={breached} styles={{ content: { color: breached ? '#a8071a' : '#237804' } }} />
        <Statistic title="No data" value={noData} />
      </Flex>
      <Table
        rowKey="name"
        columns={columns}
        dataSource={currentQuery.data ?? []}
        loading={currentQuery.isLoading}
        size="small"
        pagination={false}
        scroll={{ x: 1250 }}
        onRow={(row) => ({ onClick: () => setSelected(row.name) })}
      />
      <Flex justify="space-between" align="center" style={{ marginTop: 24 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>SLO history</Typography.Title>
        <Select
          value={selectedName}
          style={{ width: 280 }}
          options={(currentQuery.data ?? []).map((item) => ({ value: item.name, label: item.name }))}
          onChange={setSelected}
        />
      </Flex>
      <ReactECharts option={chartOption} style={{ height: 280, width: '100%' }} notMerge />
    </div>
  );
}
