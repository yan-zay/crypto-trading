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
  historicalBacktestSupported: boolean;
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
  exchange: Exchange;
  marketType: 'SPOT' | 'FUTURES' | 'PERPETUAL';
  symbol: string;
  timeframe: string;
  expectedBars: number;
  actualBars: number;
  coveragePct: number;
  gaps: Array<{ from: number; to: number }>;
}

export interface OmsOrder {
  orderId: string;
  clientOrderId: string;
  accountId: string | null;
  orderSource: 'PAPER' | 'LIVE' | string;
  venueOrderId: string | null;
  correlationId: string | null;
  leverage: number;
  marginMode: string;
  strategyId: string;
  exchange: string;
  marketType: string;
  symbol: string;
  tradeSide: 'BUY' | 'SELL';
  requestedSide: 'LONG' | 'SHORT';
  positionSide: 'LONG' | 'SHORT';
  reduceOnly: boolean;
  orderType: string;
  quantity: number;
  price: number | null;
  filledQuantity: number;
  avgFillPrice: number | null;
  status: string;
  rejectReason: string | null;
  createdAtMs: number;
  submittedAtMs: number | null;
  filledAtMs: number | null;
  cancelledAtMs: number | null;
}

export interface OmsOrderEvent {
  eventId: string;
  orderId: string;
  eventType: string;
  orderStatus: string;
  eventTime: number;
  fillPrice: number | null;
  fillQuantity: number | null;
  rejectReason: string | null;
}

export interface OmsFill {
  fillId: string;
  orderId: string;
  accountId: string | null;
  strategyId: string | null;
  eventId: string;
  exchangeTradeId: string | null;
  fillPrice: number;
  fillQuantity: number;
  referencePrice: number | null;
  arrivalPrice: number | null;
  spreadBps: number | null;
  impactBps: number | null;
  slippageBps: number | null;
  fee: number;
  feeCurrency: string | null;
  liquidityRole: string | null;
  fillTime: number;
}

export interface OmsOrderDetail {
  order: OmsOrder;
  events: OmsOrderEvent[];
  fills: OmsFill[];
}

export interface PaperTradingStatus {
  running: boolean;
  accountId: string | null;
  account: PaperAccount | null;
  balance: number | null;
  initialBalance: number | null;
  balances: PaperBalance[];
  positions: PaperPosition[];
  tradeCount: number;
  activeOrderCount: number;
  feesPaid: number;
  realizedPnl: number;
  unrealizedPnl: number;
  netPnl: number;
  equity: number;
}

export interface PaperAccount {
  accountId: string;
  accountName: string;
  status: 'RUNNING' | 'STOPPED';
  baseCurrency: string;
  initialBalance: number;
  startedAtMs: number;
  stoppedAtMs: number | null;
}

export interface PaperBalance {
  accountId: string;
  asset: string;
  totalBalance: number;
  availableBalance: number;
  lockedBalance: number;
}

export interface PaperPosition {
  positionId: string;
  accountId: string;
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  entryPrice: number;
  markPrice: number;
  leverage: number;
  marginMode: string;
  initialMargin: number;
  maintenanceMargin: number;
  openFee: number;
  funding: number;
  realizedPnl: number;
  unrealizedPnl: number;
  strategyId: string;
  openedAtMs: number;
  updatedAtMs: number;
}

export interface PaperTrade {
  tradeId: string;
  accountId: string;
  strategyId: string;
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  entryPrice: number;
  exitPrice: number;
  grossPnl: number;
  openFee: number;
  closeFee: number;
  funding: number;
  netPnl: number;
  openedAtMs: number;
  closedAtMs: number;
  durationMs: number;
}

export interface PaperEquityPoint {
  snapshotId: string;
  eventTimeMs: number;
  balance: number;
  availableBalance: number;
  lockedMargin: number;
  unrealizedPnl: number;
  equity: number;
}

export interface PaperLedgerEntry {
  entryId: string;
  transactionId: string;
  accountId: string;
  ledgerAccount: string;
  asset: string;
  debit: number;
  credit: number;
  createTime: string;
}

export interface PaperAttribution {
  dimension: string;
  key: string;
  trades: number;
  wins: number;
  losses: number;
  grossPnl: number;
  fees: number;
  funding: number;
  netPnl: number;
  winRatePct: number;
  avgTradePnl: number;
  profitFactor: number;
}

export interface PaperExecutionQuality {
  fills: number;
  filledQuantity: number;
  notional: number;
  fees: number;
  avgSpreadBps: number;
  avgImpactBps: number;
  avgSlippageBps: number;
  makerRatioPct: number;
}

export interface PaperMark {
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: string;
  price: number;
  highPrice: number;
  lowPrice: number;
  baseVolume: number;
  eventTimeMs: number;
  source: string;
}

export interface PaperOrderCommand {
  accountId?: string;
  clientOrderId?: string;
  strategyId: string;
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: 'BTCUSDT' | 'ETHUSDT';
  side: 'BUY' | 'SELL';
  orderType: 'MARKET' | 'LIMIT';
  quantity: number;
  limitPrice?: number;
  leverage: number;
  reduceOnly: boolean;
}

export interface PaperMarkCommand {
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: 'BTCUSDT' | 'ETHUSDT';
  price: number;
  highPrice?: number;
  lowPrice?: number;
  baseVolume: number;
  eventTimeMs?: number;
}

export interface ReconciliationReport {
  accountId: string;
  checkedAtMs: number;
  ordersChecked: number;
  balancesChecked: number;
  positionsChecked: number;
  newOrUpdatedIncidents: number;
  openIncidents: number;
  checks: string[];
}

export interface ReconciliationIncident {
  incidentId: string;
  accountId: string;
  incidentType: string;
  severity: string;
  aggregateType: string;
  aggregateId: string;
  expectedJson: string;
  actualJson: string;
  status: string;
  detectedAtMs: number;
  resolution: string | null;
}

export interface AuditLog {
  id: number;
  requestId: string | null;
  correlationId: string | null;
  operationType: string;
  resourceType: string | null;
  resourceId: string | null;
  operator: string;
  outcome: 'SUCCESS' | 'FAILURE';
  sourceIp: string | null;
  latencyMs: number | null;
  operationTime: string | number;
  detail: string | null;
  previousHash: string | null;
  entryHash: string | null;
}

export interface AuditVerification {
  valid: boolean;
  verifiedEntries: number;
  lastAuditId: number | null;
  lastHash: string;
  chainHeadMatches: boolean;
  failedAuditId: number | null;
  message: string;
}

export interface SloStatus {
  name: string;
  windowStartMs: number;
  windowEndMs: number;
  targetValue: number;
  actualValue: number | null;
  compliant: boolean;
  errorBudgetRemainingPct: number | null;
  sampleCount: number;
  successCount: number;
  failureCount: number;
  averageLatencyMs: number;
  maxLatencyMs: number;
  state: 'NO_DATA' | 'COMPLIANT' | 'BREACHED';
}

export interface SloSnapshot {
  snapshotId: string;
  sloName: string;
  windowStartMs: number;
  windowEndMs: number;
  targetValue: number;
  actualValue: number | null;
  compliant: boolean;
  errorBudgetRemainingPct: number | null;
  sampleCount: number;
  detailJson: string;
}

export interface OutboxEvent {
  eventId: string;
  eventSequence: number;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  correlationId: string | null;
  status: string;
  attempts: number;
  availableAtMs: number;
  publishedAtMs: number | null;
  lastError: string | null;
}

export interface BacktestJob {
  jobId: string;
  jobType: 'STRATEGY' | 'FACTOR';
  status: string;
  requestJson: string;
  progressPct: number;
  stage: string;
  resultId: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  randomSeed: number;
  createdBy: string;
  createdAtMs: number;
  startedAtMs: number | null;
  completedAtMs: number | null;
}

export interface BacktestJobCommand {
  type: 'STRATEGY';
  strategyName: string;
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: 'BTCUSDT' | 'ETHUSDT';
  timeframe: string;
  days: number;
  warmupBars: number;
  initialBalance: number;
  autoBackfill: boolean;
  randomSeed: number;
}

export interface BacktestComparisonRow {
  rank: number;
  runId: string;
  strategyName: string;
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: string;
  timeframe: string;
  totalReturn: number;
  annualizedReturn: number;
  maxDrawdown: number;
  sharpe: number;
  sortino: number;
  calmar: number;
  winRate: number;
  profitFactor: number;
  totalFees: number;
  totalTrades: number;
}

export interface BacktestResearchMetadata {
  runId: string;
  robustness: {
    tradeCount: number;
    evidenceGrade: string;
    meanTradeReturn: number;
    standardDeviation: number;
    skewness: number;
    excessKurtosis: number;
    bootstrapSamples: number;
    bootstrapMeanLower95: number;
    bootstrapMeanUpper95: number;
    probabilityMeanPositive: number;
    probabilisticSharpeRatio: number;
    minimumTrackRecordLength: number;
    warnings: string[];
  };
  reproducibility: Record<string, string | number>;
  executionQuality: {
    fillMode: string;
    turnover: number;
    totalFees: number;
    tradeCount: number;
    feeBpsOnTurnover: number;
    limitations: string[];
    [key: string]: unknown;
  };
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
  exchange: string;
  marketType: string;
  symbol: string;
  timeframe: string;
  startDate: string;
  endDate: string;
  initialCapital: number;
  finalCapital: number;
  totalReturnPct: number;
  annualizedReturnPct: number;
  maxDrawdownPct: number;
  winRatePct: number;
  sharpeRatio: number;
  sortinoRatio: number;
  calmarRatio: number;
  avgTradeDurationMs: number;
  totalTrades: number;
  signalCount: number;
  winningTrades: number;
  losingTrades: number;
  maxWinStreak: number;
  maxLoseStreak: number;
  avgWinPct: number;
  avgLossPct: number;
  profitFactor: number;
  totalFees: number;
  strategyConfigJson: string;
  assumptionsJson: string;
  monthlyReturnsPct: Record<string, number>;
  equityCurve: BacktestEquityPointDTO[];
  signals: BacktestSignalDTO[];
  trades: BacktestTradeDTO[];
}

export interface BacktestEquityPointDTO {
  timestamp: number;
  equity: number;
}

export interface BacktestSignalDTO {
  timestamp: number;
  type: 'BUY' | 'SELL' | 'HOLD';
  confidence: number;
  reason: string;
  factorSnapshot: Record<string, number>;
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

export type Exchange = 'BINANCE' | 'COINGLASS' | 'OKX';
export type BacktestMarketType = 'SPOT' | 'PERPETUAL';
export type FactorOperator = 'LT' | 'LTE' | 'GT' | 'GTE' | 'CROSS_ABOVE' | 'CROSS_BELOW';
export type FactorComparisonTarget = 'CONSTANT' | 'PRICE' | 'FACTOR';
export type FactorMatchMode = 'ALL' | 'ANY' | 'WEIGHTED';
export type FactorPositionMode = 'LONG_ONLY' | 'LONG_SHORT';

export interface FactorRule {
  factorName: string;
  operator: FactorOperator;
  target: FactorComparisonTarget;
  threshold?: number;
  targetFactorName?: string;
  weight: number;
}

export interface FactorRuleGroup {
  mode: FactorMatchMode;
  minimumMatchRatio: number;
  rules: FactorRule[];
}

export interface FactorStrategySpec {
  name: string;
  positionMode: FactorPositionMode;
  longEntry: FactorRuleGroup;
  longExit: FactorRuleGroup;
  shortEntry?: FactorRuleGroup;
  shortExit?: FactorRuleGroup;
}

export interface FactorBacktestRequest {
  exchange: Exchange;
  marketType: BacktestMarketType;
  symbol: 'BTCUSDT' | 'ETHUSDT';
  timeframe: string;
  days: number;
  warmupBars: number;
  initialBalance: number;
  autoBackfill: boolean;
  strategy: FactorStrategySpec;
}

export interface FactorBacktestResponse {
  strategyName: string;
  finalBalance: number;
  totalReturnPct: number;
  maxDrawdownPct: number;
  totalTrades: number;
  signalCount: number;
  persisted: boolean;
}

export interface BacktestCapabilities {
  exchanges: Exchange[];
  marketTypes: BacktestMarketType[];
  symbols: Array<'BTCUSDT' | 'ETHUSDT'>;
  timeframes: string[];
  factors: string[];
  operators: FactorOperator[];
  comparisonTargets: FactorComparisonTarget[];
  matchModes: FactorMatchMode[];
  positionModes: FactorPositionMode[];
}
