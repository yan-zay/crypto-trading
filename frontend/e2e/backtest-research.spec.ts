import { expect, test } from '@playwright/test';

const capabilities = {
  exchanges: ['BINANCE', 'COINGLASS', 'OKX'],
  marketTypes: ['SPOT', 'PERPETUAL'],
  symbols: ['BTCUSDT', 'ETHUSDT'],
  timeframes: ['1m', '1h', '4h'],
  factors: ['MACD_HIST', 'RSI', 'SMA'],
  operators: ['LT', 'LTE', 'GT', 'GTE', 'CROSS_ABOVE', 'CROSS_BELOW'],
  comparisonTargets: ['CONSTANT', 'PRICE', 'FACTOR'],
  matchModes: ['ALL', 'ANY', 'WEIGHTED'],
  positionModes: ['LONG_ONLY', 'LONG_SHORT'],
};

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('admin_token', 'test-token'));
  await page.route('**/api/admin/health', async (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      status: 'UP', uptimeMs: 1000, connectors: [], strategyCount: 6, factorCount: 14, totalSignalCount: 0,
    }),
  }));
});

test('submits a configurable multi-factor research backtest', async ({ page }) => {
  let submitted: Record<string, unknown> | undefined;
  await page.route('**/api/admin/backtests/capabilities', async (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(capabilities),
  }));
  await page.route('**/api/admin/strategies', async (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]',
  }));
  await page.route('**/api/admin/backtests/factor-run', async (route) => {
    submitted = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        strategyName: 'RSI research', finalBalance: 10_250, totalReturnPct: 0.025,
        maxDrawdownPct: 0.01, totalTrades: 4, signalCount: 8, persisted: true,
      }),
    });
  });

  await page.goto('/backtests');
  await expect(page.getByRole('heading', { name: 'Backtest Research' })).toBeVisible();
  await page.getByRole('button', { name: 'Add rule' }).first().click();
  await page.locator('[aria-label="Long entry factor 2"]').click();
  await page.getByText('MACD_HIST', { exact: true }).last().click();
  await page.getByRole('button', { name: 'Run factor backtest' }).click();
  await expect.poll(() => submitted).toBeTruthy();

  const request = submitted as {
    exchange: string;
    marketType: string;
    symbol: string;
    strategy: { longEntry: { rules: Array<{ factorName: string }> } };
  };
  expect(request.exchange).toBe('BINANCE');
  expect(request.marketType).toBe('PERPETUAL');
  expect(request.symbol).toBe('BTCUSDT');
  expect(request.strategy.longEntry.rules.map((rule) => rule.factorName)).toEqual(['RSI', 'MACD_HIST']);
});

test('loads full report details separately from the result summary', async ({ page }) => {
  let detailRequests = 0;
  const summary = {
    id: 'run-1', strategyName: 'RSI research', exchange: 'OKX', marketType: 'PERPETUAL',
    symbol: 'ETHUSDT', timeframe: '1h', startDate: '2026-06-01', endDate: '2026-07-01',
    initialCapital: 10_000, finalCapital: 10_500, totalReturnPct: 0.05,
    annualizedReturnPct: 0.81, maxDrawdownPct: 0.02, winRatePct: 0.6,
    sharpeRatio: 1.4, sortinoRatio: 1.8, calmarRatio: 3.2, avgTradeDurationMs: 3_600_000,
    totalTrades: 5, signalCount: 10, winningTrades: 3, losingTrades: 2,
    maxWinStreak: 2, maxLoseStreak: 1, avgWinPct: 0.03, avgLossPct: -0.015,
    profitFactor: 1.9, totalFees: 8.5, strategyConfigJson: '{}', assumptionsJson: '{}',
    monthlyReturnsPct: {}, equityCurve: [], signals: [], trades: [],
  };
  const detail = {
    ...summary,
    monthlyReturnsPct: { '2026-06': 0.05 },
    equityCurve: [{ timestamp: 1_717_200_000_000, equity: 10_000 }, { timestamp: 1_719_792_000_000, equity: 10_500 }],
    signals: [{ timestamp: 1_717_200_000_000, type: 'BUY', confidence: 0.8, reason: 'RSI <= 30', factorSnapshot: { RSI: 28 } }],
    trades: [{
      entryTime: 1_717_200_000_000, exitTime: 1_717_203_600_000, side: 'LONG',
      entryPrice: 3500, exitPrice: 3600, quantity: 1, pnl: 100, pnlPct: 0.0285, fees: 2,
    }],
  };
  await page.route('**/api/admin/backtest-results**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/run-1')) {
      detailRequests += 1;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([summary]) });
  });

  await page.goto('/backtest-results');
  await expect(page.getByText('RSI research · OKX PERPETUAL · ETHUSDT 1h')).toBeVisible();
  await expect(page.getByText('Annualized')).toBeVisible();
  expect(detailRequests).toBe(1);
});
