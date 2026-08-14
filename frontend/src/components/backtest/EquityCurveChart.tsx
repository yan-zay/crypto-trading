import ReactEChartsCore from 'echarts-for-react/esm/core';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { BacktestEquityPointDTO } from '../../types';

echarts.use([LineChart, DataZoomComponent, GridComponent, TooltipComponent, CanvasRenderer]);

interface EquityCurveChartProps {
  points: BacktestEquityPointDTO[];
}

export default function EquityCurveChart({ points }: EquityCurveChartProps) {
  const data = points.map((point) => [point.timestamp, point.equity]);
  const option = {
    animation: false,
    grid: { left: 62, right: 20, top: 24, bottom: 48 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value: number) => value.toLocaleString(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }),
    },
    xAxis: {
      type: 'time',
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { formatter: (value: number) => value.toLocaleString() },
    },
    dataZoom: [
      { type: 'inside' },
      { type: 'slider', height: 16, bottom: 8 },
    ],
    series: [{
      name: 'Equity',
      type: 'line',
      data,
      showSymbol: false,
      sampling: 'lttb',
      lineStyle: { width: 2, color: '#1677ff' },
      areaStyle: { color: 'rgba(22,119,255,0.10)' },
    }],
  };

  return (
    <ReactEChartsCore
      echarts={echarts}
      option={option}
      style={{ height: 320, width: '100%' }}
    />
  );
}
