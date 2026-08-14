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
  OmsOrder,
  OmsOrderDetail,
  PaperTradingStatus,
  BacktestCapabilities,
  FactorBacktestRequest,
  FactorBacktestResponse,
  PaperAccount,
  PaperAttribution,
  PaperEquityPoint,
  PaperExecutionQuality,
  PaperLedgerEntry,
  PaperMark,
  PaperMarkCommand,
  PaperOrderCommand,
  PaperTrade,
  OmsFill,
  AuditLog,
  AuditVerification,
  SloStatus,
  SloSnapshot,
  ReconciliationIncident,
  ReconciliationReport,
  OutboxEvent,
  BacktestJob,
  BacktestJobCommand,
  BacktestComparisonRow,
  BacktestResearchMetadata,
} from '../types';

const client = axios.create({ baseURL: '/api/admin' });

// ── 认证拦截器 ─────────────────────────────────────────────────
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('admin_token');
      // 避免在登录页循环跳转
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  },
);

// ── Auth ───────────────────────────────────────────────────────

export interface LoginResponse {
  success: boolean;
  token?: string;
  error?: string;
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const { data } = await client.post<LoginResponse>('/login', { username, password });
  return data;
}

// ── System ─────────────────────────────────────────────────────

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

// ── Strategies ─────────────────────────────────────────────────

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

// ── Factors ────────────────────────────────────────────────────

export async function fetchFactors(): Promise<FactorInfoDTO[]> {
  const { data } = await client.get<FactorInfoDTO[]>('/factors');
  return data;
}

// ── Signals ────────────────────────────────────────────────────

export async function fetchSignals(limit = 50): Promise<SignalEvent[]> {
  const { data } = await client.get<SignalEvent[]>('/signals', { params: { limit } });
  return data;
}

// ── Risk ───────────────────────────────────────────────────────

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

// ── Coverage / Backfill ────────────────────────────────────────

export async function fetchCoverage(
  symbol: string,
  timeframe = '1m',
  days = 30,
  exchange = 'BINANCE',
  marketType = 'PERPETUAL',
): Promise<CoverageReport> {
  const { data } = await client.get<CoverageReport>('/coverage', {
    params: { exchange, marketType, symbol, timeframe, days },
  });
  return data;
}

export async function triggerBackfill(
  symbol: string,
  timeframe = '1m',
  days = 30,
  exchange = 'BINANCE',
  marketType = 'PERPETUAL',
): Promise<{ barsFilled: number }> {
  const { data } = await client.post('/backfill', null, {
    params: { exchange, marketType, symbol, timeframe, days },
  });
  return data;
}

export async function runBacktest(params: {
  strategyName: string;
  exchange: string;
  marketType: string;
  symbol: string;
  timeframe: string;
  days: number;
  warmupBars?: number;
  initialBalance?: number;
}): Promise<{
  strategyName: string;
  finalBalance: number;
  totalReturnPct: number;
  maxDrawdownPct: number;
  totalTrades: number;
  persisted: boolean;
}> {
  const { data } = await client.post('/backtests/run', null, { params });
  return data;
}

export async function fetchBacktestCapabilities(): Promise<BacktestCapabilities> {
  const { data } = await client.get<BacktestCapabilities>('/backtests/capabilities');
  return data;
}

export async function runFactorBacktest(
  request: FactorBacktestRequest,
): Promise<FactorBacktestResponse> {
  const { data } = await client.post<FactorBacktestResponse>('/backtests/factor-run', request);
  return data;
}

// ── OMS / Paper Trading ───────────────────────────────────────

export async function fetchOrders(params: {
  exchange?: string;
  marketType?: string;
  symbol?: string;
  status?: string;
  limit?: number;
}): Promise<OmsOrder[]> {
  const { data } = await client.get<OmsOrder[]>('/orders', { params });
  return data;
}

export async function fetchOrder(orderId: string): Promise<OmsOrderDetail> {
  const { data } = await client.get<OmsOrderDetail>(`/orders/${encodeURIComponent(orderId)}`);
  return data;
}

export async function fetchPaperTradingStatus(accountId?: string): Promise<PaperTradingStatus> {
  const { data } = await client.get<PaperTradingStatus>('/paper-trading/status', {
    params: { accountId },
  });
  return data;
}

export async function fetchPaperAccounts(limit = 50): Promise<PaperAccount[]> {
  const { data } = await client.get<PaperAccount[]>('/paper-trading/accounts', { params: { limit } });
  return data;
}

export async function fetchPaperOrders(accountId?: string, limit = 500): Promise<OmsOrder[]> {
  const { data } = await client.get<OmsOrder[]>('/paper-trading/orders', {
    params: { accountId, limit },
  });
  return data;
}

export async function fetchPaperFills(accountId?: string, limit = 500): Promise<OmsFill[]> {
  const { data } = await client.get<OmsFill[]>('/paper-trading/fills', {
    params: { accountId, limit },
  });
  return data;
}

export async function fetchPaperTrades(accountId?: string, limit = 500): Promise<PaperTrade[]> {
  const { data } = await client.get<PaperTrade[]>('/paper-trading/trades', {
    params: { accountId, limit },
  });
  return data;
}

export async function fetchPaperEquity(accountId?: string, limit = 5000): Promise<PaperEquityPoint[]> {
  const { data } = await client.get<PaperEquityPoint[]>('/paper-trading/equity', {
    params: { accountId, limit },
  });
  return data;
}

export async function fetchPaperLedger(accountId?: string, limit = 500): Promise<PaperLedgerEntry[]> {
  const { data } = await client.get<PaperLedgerEntry[]>('/paper-trading/ledger', {
    params: { accountId, limit },
  });
  return data;
}

export async function fetchPaperAttribution(
  accountId?: string,
): Promise<Record<string, PaperAttribution[]>> {
  const { data } = await client.get<Record<string, PaperAttribution[]>>('/paper-trading/attribution', {
    params: { accountId },
  });
  return data;
}

export async function fetchPaperExecutionQuality(accountId?: string): Promise<PaperExecutionQuality> {
  const { data } = await client.get<PaperExecutionQuality>('/paper-trading/execution-quality', {
    params: { accountId },
  });
  return data;
}

export async function fetchPaperMarks(): Promise<PaperMark[]> {
  const { data } = await client.get<PaperMark[]>('/paper-trading/marks');
  return data;
}

export async function updatePaperMark(command: PaperMarkCommand): Promise<PaperMark> {
  const { data } = await client.post<PaperMark>('/paper-trading/market-price', command);
  return data;
}

export async function placePaperOrder(command: PaperOrderCommand): Promise<OmsOrder> {
  const { data } = await client.post<OmsOrder>('/paper-trading/orders', command);
  return data;
}

export async function cancelPaperOrder(orderId: string, accountId?: string): Promise<OmsOrder> {
  const { data } = await client.post<OmsOrder>(
    `/paper-trading/orders/${encodeURIComponent(orderId)}/cancel`, null, { params: { accountId } },
  );
  return data;
}

export async function startPaperTrading(initialBalance: number, accountName?: string): Promise<PaperTradingStatus> {
  const { data } = await client.post<PaperTradingStatus>('/paper-trading/start', null, {
    params: { initialBalance, accountName },
  });
  return data;
}

export async function stopPaperTrading(accountId?: string): Promise<PaperTradingStatus> {
  const { data } = await client.post<PaperTradingStatus>('/paper-trading/stop', null, {
    params: { accountId },
  });
  return data;
}

export async function resumePaperTrading(accountId: string): Promise<PaperTradingStatus> {
  const { data } = await client.post<PaperTradingStatus>(
    `/paper-trading/accounts/${encodeURIComponent(accountId)}/resume`,
  );
  return data;
}

// ── Reliability / Audit / SLO ─────────────────────────────────

export async function runReconciliation(accountId: string): Promise<ReconciliationReport> {
  const { data } = await client.post<ReconciliationReport>('/reconciliation/run', null, {
    params: { accountId },
  });
  return data;
}

export async function fetchReconciliationIncidents(
  accountId?: string, status?: string, limit = 200,
): Promise<ReconciliationIncident[]> {
  const { data } = await client.get<ReconciliationIncident[]>('/reconciliation/incidents', {
    params: { accountId, status, limit },
  });
  return data;
}

export async function resolveReconciliationIncident(
  incidentId: string, resolution: string,
): Promise<ReconciliationIncident> {
  const { data } = await client.post<ReconciliationIncident>(
    `/reconciliation/incidents/${encodeURIComponent(incidentId)}/resolve`, null,
    { params: { resolution } },
  );
  return data;
}

export async function fetchAuditLogs(limit = 200): Promise<AuditLog[]> {
  const { data } = await client.get<AuditLog[]>('/audit', { params: { limit } });
  return data;
}

export async function verifyAuditChain(): Promise<AuditVerification> {
  const { data } = await client.get<AuditVerification>('/audit/verify');
  return data;
}

export async function fetchCurrentSlos(): Promise<SloStatus[]> {
  const { data } = await client.get<SloStatus[]>('/slo/current');
  return data;
}

export async function fetchSloHistory(name?: string, limit = 200): Promise<SloSnapshot[]> {
  const { data } = await client.get<SloSnapshot[]>('/slo/history', { params: { name, limit } });
  return data;
}

export async function fetchOutbox(status?: string, limit = 200): Promise<OutboxEvent[]> {
  const { data } = await client.get<OutboxEvent[]>('/outbox', { params: { status, limit } });
  return data;
}

export async function fetchOutboxBacklog(): Promise<Record<string, number | null>> {
  const { data } = await client.get<Record<string, number | null>>('/outbox/backlog');
  return data;
}

export async function retryOutboxEvent(eventId: string): Promise<{ eventId: string; status: string }> {
  const { data } = await client.post(`/outbox/${encodeURIComponent(eventId)}/retry`);
  return data;
}

// ── Durable Backtest Jobs ─────────────────────────────────────

export async function fetchBacktestJobs(status?: string, limit = 200): Promise<BacktestJob[]> {
  const { data } = await client.get<BacktestJob[]>('/backtest-jobs', { params: { status, limit } });
  return data;
}

export async function submitBacktestJob(command: BacktestJobCommand): Promise<BacktestJob> {
  const { data } = await client.post<BacktestJob>('/backtest-jobs', command);
  return data;
}

export async function cancelBacktestJob(jobId: string): Promise<BacktestJob> {
  const { data } = await client.post<BacktestJob>(
    `/backtest-jobs/${encodeURIComponent(jobId)}/cancel`,
  );
  return data;
}

export async function compareBacktestRuns(runIds: string[]): Promise<BacktestComparisonRow[]> {
  const { data } = await client.get<BacktestComparisonRow[]>('/backtest-jobs/compare', {
    params: { runIds },
    paramsSerializer: { indexes: null },
  });
  return data;
}

// ── Alerts ─────────────────────────────────────────────────────

export async function fetchAlerts(limit = 20): Promise<AlertEvent[]> {
  const { data } = await client.get<AlertEvent[]>('/alerts', { params: { limit } });
  return data;
}

// ── Strategy Detail ────────────────────────────────────────────

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

// ── Config Management ──────────────────────────────────────────

export interface ConfigVersion {
  versionId: string;
  type: string;
  configKey: string;
  contentJson: string;
  status: string;
  remark: string;
  publishedBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export async function getConfigs(type: string, configKey?: string): Promise<ConfigVersion[]> {
  const params: Record<string, string> = { type };
  if (configKey) params.configKey = configKey;
  const { data } = await client.get('/configs', { params });
  return Array.isArray(data) ? data : [data];
}

export async function createConfigDraft(
  type: string, configKey: string, contentJson: string, remark: string,
): Promise<ConfigVersion> {
  const { data } = await client.post('/configs/draft', null, {
    params: { type, configKey, contentJson, remark },
  });
  return data;
}

export async function validateConfig(versionId: string): Promise<ConfigVersion> {
  const { data } = await client.post(`/configs/${encodeURIComponent(versionId)}/validate`);
  return data;
}

export async function publishConfig(versionId: string): Promise<ConfigVersion> {
  const { data } = await client.post(`/configs/${encodeURIComponent(versionId)}/publish`);
  return data;
}

export async function rollbackConfig(versionId: string, targetVersionId: string): Promise<ConfigVersion> {
  const { data } = await client.post(`/configs/${encodeURIComponent(versionId)}/rollback`, null, {
    params: { targetVersionId },
  });
  return data;
}

export async function getConfigHistory(type: string, configKey: string): Promise<ConfigVersion[]> {
  const { data } = await client.get('/configs/history', {
    params: { type, configKey },
  });
  return Array.isArray(data) ? data : [];
}

// ── Backtest Results ───────────────────────────────────────────

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

export async function fetchBacktestResearchMetadata(id: string): Promise<BacktestResearchMetadata> {
  const { data } = await client.get<BacktestResearchMetadata>(
    `/backtest-results/${encodeURIComponent(id)}/research-metadata`,
  );
  return data;
}

export async function downloadBacktestReport(
  id: string,
  format: 'json' | 'csv' | 'markdown',
): Promise<void> {
  const response = await client.get<Blob>(
    `/backtest-results/${encodeURIComponent(id)}/report`,
    { params: { format }, responseType: 'blob' },
  );
  const disposition = response.headers['content-disposition'] as string | undefined;
  const encodedName = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plainName = disposition?.match(/filename="?([^";]+)"?/i)?.[1];
  const filename = encodedName
    ? decodeURIComponent(encodedName)
    : plainName ?? `backtest-${id}.${format === 'markdown' ? 'md' : format}`;
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
