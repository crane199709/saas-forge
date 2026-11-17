import { defineConfig } from '@playwright/test'

// 顺序复用模板 E2E 的回调源；上一个 Playwright 进程退出后 Shell preview 已关闭。
export default defineConfig({
  testDir: './e2e/template-dev',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  forbidOnly: !!process.env.CI,
  use: {
    channel: 'chromium',
    baseURL: 'http://127.0.0.1:15174',
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'pnpm --dir "$FORGE_TEMPLATE_SUB_APP" dev --host 127.0.0.1 --port 15174',
    url: 'http://127.0.0.1:15174',
    reuseExistingServer: false,
  },
})
