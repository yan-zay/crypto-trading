/** System status DTO */
export interface SystemStatusDTO {
  startupTimestamp: number;
  uptimeMs: number;
  strategyCount: number;
  factorCount: number;
  totalSignalCount: number;
  connectorCount: number;
  connectedConnectorCount: number;
}

/** Overview DTO */
export interface OverviewDTO {
  startupTimestamp: number;
  uptimeMs: number;
  strategyCount: number;
  enabledStrategyCount: number;
  factorCount: number;
  totalSignalCount: number;
  connectorCount: number;
  connectedConnectorCount: number;
  connectors: ConnectorStatusDTO[];
  riskConfig: RiskConfigDTO;
}

/** Connector status DTO */
export interface ConnectorStatusDTO {
  name: string;
  connected: boolean;
  messagesReceived: number;
  reconnectCount: number;
  lastMessageTimestamp: number;
  lastError: string;
}

/** Strategy info DTO */
export interface StrategyInfoDTO {
  name: string;
  listenedEvents: string[];
}

/** Factor info DTO */
export interface FactorInfoDTO {
  name: string;
}

/** Risk config DTO */
export interface RiskConfigDTO {
  maxLossPerTradePct: number;
  maxDailyLossPct: number;
  maxSizePct: number;
  slippageBps: number;
}

/** Signal event (from strategy engine) */
export interface SignalEvent {
  strategyName: string;
  instrument: {
    exchange: string;
    marketType: string;
    symbol: string;
    baseAsset: string;
    quoteAsset: string;
  };
  type: 'BUY' | 'SELL' | 'HOLD';
  confidence: number;
  reason: string;
  factorSnapshot: Record<string, number>;
  timestamp: number;
}

/** KillSwitch status */
export interface KillSwitchStatus {
  active: boolean;
  mode: 'NORMAL' | 'CLOSE_ONLY' | 'HALT';
}

/** Health check response */
export interface HealthResponse {
  status: 'UP' | 'DOWN';
  uptimeMs: number;
  connectors: Record<string, unknown>[];
  strategyCount: number;
  factorCount: number;
  totalSignalCount: number;
}

/** Coverage report */
export interface CoverageReport {
  symbol: string;
  timeframe: string;
  expectedBars: number;
  actualBars: number;
  coveragePct: number;
  gaps: Array<{ from: number; to: number }>;
}

/** Alert event */
export interface AlertEvent {
  level: 'INFO' | 'WARN' | 'ERROR' | 'CRITICAL';
  source: string;
  message: string;
  timestamp: number;
}

/** Strategy detail with recent signals */
export interface StrategyDetailDTO {
  name: string;
  enabled: boolean;
  listenedEvents: string[];
  recentSignals: SignalEvent[];
}

/** Backtest result summary */
export interface BacktestResultDTO {
  id: string;
  strategyName: string;
  symbol: string;
  timeframe: string;
  startDate: string;
  endDate: string;
  initialCapital: number;
  finalCapital: number;
  totalReturnPct: number;
  maxDrawdownPct: number;
  winRatePct: number;
  sharpeRatio: number;
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  avgWinPct: number;
  avgLossPct: number;
  profitFactor: number;
  trades: BacktestTradeDTO[];
}

/** Individual backtest trade */
export interface BacktestTradeDTO {
  entryTime: number;
  exitTime: number;
  side: 'LONG' | 'SHORT';
  entryPrice: number;
  exitPrice: number;
  quantity: number;
  pnl: number;
  pnlPct: number;
  fees: number;
}
