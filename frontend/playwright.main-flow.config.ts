import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e/main-flow',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    channel: 'chromium',
    baseURL: 'http://127.0.0.1:15174',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: 'pnpm --filter @forge/tenant-app preview --host 127.0.0.1 --port 15174',
      url: 'http://127.0.0.1:15174',
      reuseExistingServer: false,
      env: { FORGE_DEV_GATEWAY: 'http://127.0.0.1:18000' },
    },
    {
      command: 'pnpm --filter @forge/demo-sub-app preview --host 127.0.0.1 --port 15175',
      url: 'http://127.0.0.1:15175/remoteEntry.js',
      env: { FORGE_E2E: '1' },
      reuseExistingServer: false,
    },
  ],
})
