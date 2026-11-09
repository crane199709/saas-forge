import { defineConfig } from '@playwright/test'

// 基线只在开发机（macOS）生成和比对：字体渲染随系统变化，不进 CI 的通用 E2E。
export default defineConfig({
  testDir: './e2e/visual',
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  fullyParallel: false,
  workers: 1,
  expect: { toHaveScreenshot: { animations: 'disabled', maxDiffPixelRatio: 0.005 } },
  use: {
    channel: 'chromium',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
    baseURL: 'http://127.0.0.1:5184',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  webServer: [
    {
      command: 'pnpm --filter @forge/tenant-app preview --host 127.0.0.1 --port 5184 --strictPort',
      url: 'http://127.0.0.1:5184',
      reuseExistingServer: false,
    },
  ],
})
