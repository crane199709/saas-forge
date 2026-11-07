import { defineConfig } from '@playwright/test'
import mainFlow from './playwright.main-flow.config'

// 多参数 defineConfig 会拼接 webServer；独立模式必须替换原预览入口。
export default defineConfig({
  ...mainFlow,
  testMatch: '**/notification-flow.spec.ts',
  timeout: 120_000,
  reporter: [['list']],
  outputDir: '/private/tmp/notification-center-qa/results',
  use: { ...mainFlow.use, viewport: { width: 1440, height: 1000 } },
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
      reuseExistingServer: false,
      env: { FORGE_E2E: '1' },
    },
    {
      command:
        'pnpm --filter @forge/platform-console preview --host 127.0.0.1 --port 15173 --strictPort',
      url: 'http://127.0.0.1:15173',
      reuseExistingServer: false,
      env: { FORGE_DEV_GATEWAY: 'http://127.0.0.1:18000' },
    },
  ],
})
