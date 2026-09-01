import { expect, test } from '@playwright/test';

test('real backend login opens the authenticated overview', async ({ page }) => {
  const username = process.env.E2E_ADMIN_USERNAME;
  const password = process.env.E2E_ADMIN_PASSWORD;
  if (!username || !password) {
    throw new Error('E2E_ADMIN_USERNAME and E2E_ADMIN_PASSWORD are required for the real backend path');
  }

  const responses: Array<{ url: string; status: number }> = [];
  page.on('response', (response) => {
    if (response.url().includes('/api/admin/')) {
      responses.push({ url: response.url(), status: response.status() });
    }
  });

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByTestId('login-submit').click();

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'System Overview' })).toBeVisible();
  const token = await page.evaluate(() => localStorage.getItem('admin_token'));
  expect(token).toBeTruthy();
  expect(responses.some(({ url, status }) => url.endsWith('/api/admin/login') && status === 200)).toBeTruthy();
  expect(responses.some(({ url, status }) => url.endsWith('/api/admin/overview') && status === 200)).toBeTruthy();
});
