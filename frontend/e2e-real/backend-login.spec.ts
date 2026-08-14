import { expect, test } from '@playwright/test';

test('real backend login opens the authenticated overview', async ({ page }) => {
  const responses: Array<{ url: string; status: number }> = [];
  page.on('response', (response) => {
    if (response.url().includes('/api/admin/')) {
      responses.push({ url: response.url(), status: response.status() });
    }
  });

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByTestId('login-submit').click();

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'System Overview' })).toBeVisible();
  const token = await page.evaluate(() => localStorage.getItem('admin_token'));
  expect(token).toBeTruthy();
  expect(responses.some(({ url, status }) => url.endsWith('/api/admin/login') && status === 200)).toBeTruthy();
  expect(responses.some(({ url, status }) => url.endsWith('/api/admin/overview') && status === 200)).toBeTruthy();
});
