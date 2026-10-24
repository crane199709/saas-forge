import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  use: {
    channel: 'chromium',
    baseURL: 'http://127.0.0.1:5174',
    viewport: { width: 1280, height: 900 },
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'pnpm --filter @forge/tenant-app preview --host 127.0.0.1 --port 5174',
      url: 'http://127.0.0.1:5174',
      reuseExistingServer: false,
    },
    {
      command: 'pnpm --filter @forge/demo-sub-app preview --host 127.0.0.1 --port 5175',
      url: 'http://127.0.0.1:5175/remoteEntry.js',
      reuseExistingServer: false,
    },
    {
      command:
        'pnpm --filter @forge/demo-sub-app preview --outDir dist-v2 --host 127.0.0.1 --port 5176',
      url: 'http://127.0.0.1:5176/remoteEntry.js',
      reuseExistingServer: false,
    },
  ],
})
