import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('admin_token', 'test-token'));
  await page.route('**/api/admin/health', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ status: 'UP', uptimeMs: 1000, connectors: [], strategyCount: 6, factorCount: 19, totalSignalCount: 0 }),
  }));
});

test('shows SLO error budgets and verifies the audit chain', async ({ page }) => {
  const now = Date.now();
  await page.route('**/api/admin/slo/current', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([{
      name: 'PAPER_ORDER_AVAILABILITY', windowStartMs: now - 3_600_000, windowEndMs: now,
      targetValue: 0.999, actualValue: 1, compliant: true, errorBudgetRemainingPct: 100,
      sampleCount: 12, successCount: 12, failureCount: 0, averageLatencyMs: 18, maxLatencyMs: 42,
      state: 'COMPLIANT',
    }]),
  }));
  await page.route('**/api/admin/slo/history**', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([{
      snapshotId: 'slo-1', sloName: 'PAPER_ORDER_AVAILABILITY', windowStartMs: now - 3_600_000,
      windowEndMs: now, targetValue: 0.999, actualValue: 1, compliant: true,
      errorBudgetRemainingPct: 100, sampleCount: 12, detailJson: '{}',
    }]),
  }));
  await page.route('**/api/admin/audit/verify', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({
      valid: true, verifiedEntries: 24, lastAuditId: 24, lastHash: 'a'.repeat(64),
      chainHeadMatches: true, failedAuditId: null, message: 'OK',
    }),
  }));
  await page.route('**/api/admin/audit?**', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([{
      id: 24, requestId: 'request-24', correlationId: 'corr-24', operationType: 'HTTP_POST',
      resourceType: 'PAPER_TRADING', resourceId: 'orders', operator: 'admin', outcome: 'SUCCESS',
      sourceIp: '127.0.0.1', latencyMs: 18, operationTime: now, detail: '{"status":200}',
      previousHash: '0'.repeat(64), entryHash: 'a'.repeat(64),
    }]),
  }));

  await page.goto('/reliability');
  await expect(page.getByRole('heading', { name: 'Reliability & Audit' })).toBeVisible();
  await expect(page.getByText('PAPER_ORDER_AVAILABILITY').first()).toBeVisible();
  await expect(page.getByText('COMPLIANT')).toBeVisible();
  await page.getByText('Audit Chain').click();
  await expect(page.getByText('Audit chain verified')).toBeVisible();
  await expect(page.getByText('24 entries · OK')).toBeVisible();
  await expect(page.getByText('PAPER_TRADING · orders')).toBeVisible();
});
