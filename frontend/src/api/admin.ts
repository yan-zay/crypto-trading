import axios from 'axios';
import type {
  OverviewDTO,
  SystemStatusDTO,
  StrategyInfoDTO,
  FactorInfoDTO,
  ConnectorStatusDTO,
  RiskConfigDTO,
  SignalEvent,
  KillSwitchStatus,
  HealthResponse,
  CoverageReport,
  AlertEvent,
  StrategyDetailDTO,
  BacktestResultDTO,
} from '../types';

const client = axios.create({ baseURL: '/api/admin' });

// ---- System ----

export async function fetchOverview(): Promise<OverviewDTO> {
  const { data } = await client.get<OverviewDTO>('/overview');
  return data;
}

export async function fetchStatus(): Promise<SystemStatusDTO> {
  const { data } = await client.get<SystemStatusDTO>('/status');
  return data;
}

export async function fetchHealth(): Promise<HealthResponse> {
  const { data } = await client.get<HealthResponse>('/health');
  return data;
}

export async function fetchConnectors(): Promise<ConnectorStatusDTO[]> {
  const { data } = await client.get<ConnectorStatusDTO[]>('/connectors');
  return data;
}

// ---- Strategies ----

export async function fetchStrategies(): Promise<StrategyInfoDTO[]> {
  const { data } = await client.get<StrategyInfoDTO[]>('/strategies');
  return data;
}

export async function fetchStrategyStatus(
  name: string,
): Promise<{ name: string; enabled: boolean; listenedEvents: string[] }> {
  const { data } = await client.get(`/strategies/${encodeURIComponent(name)}/status`);
  return data;
}

export async function enableStrategy(name: string): Promise<void> {
  await client.post(`/strategies/${encodeURIComponent(name)}/enable`);
}

export async function disableStrategy(name: string): Promise<void> {
  await client.post(`/strategies/${encodeURIComponent(name)}/disable`);
}

// ---- Factors ----

export async function fetchFactors(): Promise<FactorInfoDTO[]> {
  const { data } = await client.get<FactorInfoDTO[]>('/factors');
  return data;
}

// ---- Signals ----

export async function fetchSignals(limit = 50): Promise<SignalEvent[]> {
  const { data } = await client.get<SignalEvent[]>('/signals', { params: { limit } });
  return data;
}

// ---- Risk ----

export async function fetchRiskConfigs(): Promise<RiskConfigDTO> {
  const { data } = await client.get<RiskConfigDTO>('/risk/configs');
  return data;
}

export async function fetchKillSwitch(): Promise<KillSwitchStatus> {
  const { data } = await client.get<KillSwitchStatus>('/risk/kill-switch');
  return data;
}

export async function activateKillSwitch(
  mode: 'NORMAL' | 'CLOSE_ONLY' | 'HALT' = 'HALT',
): Promise<void> {
  await client.post('/risk/kill-switch', null, { params: { mode } });
}

export async function deactivateKillSwitch(): Promise<void> {
  await client.post('/risk/kill-switch/deactivate');
}

// ---- Coverage / Backfill ----

export async function fetchCoverage(
  symbol: string,
  timeframe = '1m',
  days = 30,
): Promise<CoverageReport> {
  const { data } = await client.get<CoverageReport>('/coverage', {
    params: { symbol, timeframe, days },
  });
  return data;
}

export async function triggerBackfill(
  symbol: string,
  timeframe = '1m',
  days = 30,
): Promise<{ barsFilled: number }> {
  const { data } = await client.post('/backfill', null, {
    params: { symbol, timeframe, days },
  });
  return data;
}

// ---- Alerts ----

export async function fetchAlerts(limit = 20): Promise<AlertEvent[]> {
  const { data } = await client.get<AlertEvent[]>('/alerts', { params: { limit } });
  return data;
}

// ---- Strategy Detail ----

export async function fetchStrategyDetail(name: string): Promise<StrategyDetailDTO> {
  const { data } = await client.get<StrategyDetailDTO>(
    `/strategies/${encodeURIComponent(name)}/detail`,
  );
  return data;
}

export async function fetchStrategySignals(
  name: string,
  limit = 10,
): Promise<SignalEvent[]> {
  const { data } = await client.get<SignalEvent[]>(
    `/strategies/${encodeURIComponent(name)}/signals`,
    { params: { limit } },
  );
  return data;
}

// ---- Backtest Results ----

export async function fetchBacktestResults(): Promise<BacktestResultDTO[]> {
  const { data } = await client.get<BacktestResultDTO[]>('/backtest-results');
  return data;
}

export async function fetchBacktestResult(id: string): Promise<BacktestResultDTO> {
  const { data } = await client.get<BacktestResultDTO>(
    `/backtest-results/${encodeURIComponent(id)}`,
  );
  return data;
}
