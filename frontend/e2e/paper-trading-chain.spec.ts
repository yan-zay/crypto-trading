import { expect, test, type Page, type Route } from '@playwright/test';

const account = {
  accountId: 'paper-e2e', accountName: 'E2E', status: 'RUNNING', baseCurrency: 'USDT',
  initialBalance: 10_000, startedAtMs: Date.now() - 60_000, stoppedAtMs: null,
};

async function json(route: Route, body: unknown) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
}

async function mockHealth(page: Page) {
  await page.addInitScript(() => localStorage.setItem('admin_token', 'test-token'));
  await page.route('**/api/admin/health', (route) => json(route, {
    status: 'UP', uptimeMs: 1000, connectors: [], strategyCount: 6, factorCount: 19, totalSignalCount: 0,
  }));
}

test('operates and inspects the complete paper trading chain', async ({ page }) => {
  await mockHealth(page);
  let mark = {
    exchange: 'BINANCE', marketType: 'PERPETUAL', symbol: 'BTCUSDT', price: 60_000,
    highPrice: 60_000, lowPrice: 60_000, baseVolume: 1, eventTimeMs: Date.now(), source: 'ADMIN_MANUAL',
  };
  let orders: Record<string, unknown>[] = [];
  const status = () => ({
    running: true, accountId: account.accountId, account, balance: 9_995, initialBalance: 10_000,
    balances: [{ accountId: account.accountId, asset: 'USDT', totalBalance: 9_995, availableBalance: 9_395, lockedBalance: 600 }],
    positions: [{
      positionId: 'position-1', accountId: account.accountId, exchange: 'BINANCE', marketType: 'PERPETUAL',
      symbol: 'BTCUSDT', side: 'LONG', quantity: 0.01, entryPrice: 59_900, markPrice: 60_000,
      leverage: 2, marginMode: 'ISOLATED', initialMargin: 300, maintenanceMargin: 15,
      openFee: 0.2, funding: 0, realizedPnl: 0, unrealizedPnl: 1, strategyId: 'MANUAL',
      openedAtMs: Date.now() - 30_000, updatedAtMs: Date.now(),
    }],
    tradeCount: 1, activeOrderCount: orders.filter((order) => order.status === 'ACKNOWLEDGED').length,
    feesPaid: 1.1, realizedPnl: 8, unrealizedPnl: 1, netPnl: 7.9, equity: 10_007.9,
  });
  const priorFill = {
    fillId: 'fill-prior', accountId: account.accountId, strategyId: 'MANUAL', orderId: 'prior-order',
    eventId: 'event-prior', exchangeTradeId: null, fillPrice: 59_900, fillQuantity: 0.01,
    referencePrice: 59_890, arrivalPrice: 59_890, spreadBps: 1, impactBps: 2,
    slippageBps: 3, fee: 0.2, feeCurrency: 'USDT', liquidityRole: 'TAKER', fillTime: Date.now() - 30_000,
  };

  await page.route('**/api/admin/paper-trading/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path.endsWith('/accounts')) return json(route, [account]);
    if (path.endsWith('/status')) return json(route, status());
    if (path.endsWith('/marks') && request.method() === 'GET') return json(route, [mark]);
    if (path.endsWith('/market-price')) {
      mark = { ...mark, ...request.postDataJSON(), eventTimeMs: Date.now() };
      return json(route, mark);
    }
    if (path.endsWith('/orders') && request.method() === 'GET') return json(route, orders);
    if (path.endsWith('/orders') && request.method() === 'POST') {
      const command = request.postDataJSON();
      const order = {
        orderId: 'order-1', clientOrderId: 'client-1', accountId: account.accountId,
        orderSource: 'PAPER', venueOrderId: null, correlationId: 'corr-1', leverage: command.leverage,
        marginMode: 'ISOLATED', strategyId: command.strategyId, exchange: command.exchange,
        marketType: command.marketType, symbol: command.symbol, tradeSide: command.side,
        requestedSide: command.side === 'BUY' ? 'LONG' : 'SHORT', positionSide: 'LONG',
        reduceOnly: command.reduceOnly, orderType: command.orderType, quantity: command.quantity,
        price: mark.price, filledQuantity: 0, avgFillPrice: null, status: 'ACKNOWLEDGED',
        rejectReason: null, createdAtMs: Date.now(), submittedAtMs: Date.now(), filledAtMs: null, cancelledAtMs: null,
      };
      orders = [order];
      return json(route, order);
    }
    if (/\/orders\/[^/]+\/cancel$/.test(path)) {
      orders = orders.map((order) => ({ ...order, status: 'CANCELLED', cancelledAtMs: Date.now() }));
      return json(route, orders[0]);
    }
    if (path.endsWith('/fills')) return json(route, [priorFill]);
    if (path.endsWith('/trades')) return json(route, [{
      tradeId: 'trade-1', accountId: account.accountId, strategyId: 'MANUAL', exchange: 'BINANCE',
      marketType: 'PERPETUAL', symbol: 'BTCUSDT', side: 'LONG', quantity: 0.01,
      entryPrice: 59_000, exitPrice: 60_000, grossPnl: 10, openFee: 0.5, closeFee: 0.5,
      funding: 0, netPnl: 9, openedAtMs: Date.now() - 120_000, closedAtMs: Date.now() - 60_000, durationMs: 60_000,
    }]);
    if (path.endsWith('/equity')) return json(route, [
      { snapshotId: 'eq-1', eventTimeMs: Date.now() - 60_000, balance: 10_000, availableBalance: 10_000, lockedMargin: 0, unrealizedPnl: 0, equity: 10_000 },
      { snapshotId: 'eq-2', eventTimeMs: Date.now(), balance: 10_007, availableBalance: 9_395, lockedMargin: 600, unrealizedPnl: 1, equity: 10_008 },
    ]);
    if (path.endsWith('/ledger')) return json(route, [{
      entryId: 'ledger-1', transactionId: 'tx-1', accountId: account.accountId,
      ledgerAccount: 'CASH', asset: 'USDT', debit: 10, credit: 0, createTime: new Date().toISOString(),
    }]);
    if (path.endsWith('/attribution')) return json(route, {
      strategy: [{ dimension: 'strategy', key: 'MANUAL', trades: 1, wins: 1, losses: 0, grossPnl: 10, fees: 1, funding: 0, netPnl: 9, winRatePct: 100, avgTradePnl: 9, profitFactor: 999999 }],
      symbol: [], side: [], day: [],
    });
    if (path.endsWith('/execution-quality')) return json(route, {
      fills: 1, filledQuantity: 0.01, notional: 599, fees: 0.2,
      avgSpreadBps: 1, avgImpactBps: 2, avgSlippageBps: 3, makerRatioPct: 0,
    });
    return json(route, {});
  });
  await page.route('**/api/admin/reconciliation/run**', (route) => json(route, {
    accountId: account.accountId, checkedAtMs: Date.now(), ordersChecked: orders.length,
    balancesChecked: 1, positionsChecked: 1, newOrUpdatedIncidents: 0, openIncidents: 0,
    checks: ['OMS_VS_FILLS', 'DOUBLE_ENTRY'],
  }));

  await page.goto('/orders');
  await expect(page.getByRole('heading', { name: 'Trading Operations' })).toBeVisible();
  await expect(page.getByText('10,007.90')).toBeVisible();
  await expect(page.getByText('BINANCE · BTCUSDT').first()).toBeVisible();

  await page.getByText('Market data', { exact: true }).click();
  await page.getByLabel('Price').fill('60100');
  await page.getByLabel('Base volume').fill('1');
  await page.getByTestId('paper-mark-submit').click();
  await expect.poll(() => mark.price).toBe(60_100);

  await page.getByText('Order', { exact: true }).click();
  await page.getByTestId('paper-order-submit').click();
  await expect.poll(() => orders.length).toBe(1);
  await page.getByText(/Orders \(1\)/).click();
  await expect(page.getByTestId('paper-orders-table').getByText('ACKNOWLEDGED')).toBeVisible();
  await page.locator('[data-testid="paper-orders-table"] .ant-btn-dangerous').click();
  await expect(page.getByText('Cancel this order?')).toBeVisible();
  await page.getByRole('button', { name: 'OK' }).click();
  await expect(page.getByTestId('paper-orders-table').getByText('CANCELLED')).toBeVisible();

  await page.getByText(/Executions \(1\)/).click();
  await expect(page.getByText('Closed trades')).toBeVisible();
  await expect(page.locator('.ant-tabs-tabpane-active').getByText('59900').first()).toBeVisible();
  await page.getByText('Analytics & Ledger').click();
  await expect(page.getByText('Execution quality')).toBeVisible();
  await expect(page.locator('.ant-tabs-tabpane-active').getByText('MANUAL')).toBeVisible();
  await expect(page.locator('.ant-tabs-tabpane-active').getByText('CASH')).toBeVisible();

  await page.getByRole('button', { name: 'Reconcile' }).click();
  await expect(page.getByText('Reconciliation: 0 open')).toBeVisible();
});
