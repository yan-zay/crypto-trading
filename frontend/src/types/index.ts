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
