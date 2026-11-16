import { defineConfig } from '@playwright/test'

// 基线只在开发机（macOS）生成和比对：字体渲染随系统变化，不进 CI 的通用 E2E。
//
// 0.001 在 1024×768 / 1440×900 上分别允许约 786 / 1296 个差异像素，
// 用来拦住工单 08 中 0.13%–0.31% 量级的局部漂移；更小的变化仍可能漏过。
// 工单 09 已定位到固定列点击引发的背景横向滚动，相关用例禁止不必要的自动滚动并断言滚动位置。
// 后续出现两态时先检查实际页面状态和字体，不用提高容差掩盖；重写基线必须用 --update-snapshots=all。
export default defineConfig({
  testDir: './e2e/visual',
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  fullyParallel: false,
  workers: 1,
  expect: { toHaveScreenshot: { animations: 'disabled', maxDiffPixelRatio: 0.001 } },
  use: {
    channel: 'chromium',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
    baseURL: 'http://127.0.0.1:5184',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  webServer: [
    {
      command:
        'pnpm --filter @forge/demo-sub-app preview --host 127.0.0.1 --port 5185 --strictPort',
      url: 'http://127.0.0.1:5185/remoteEntry.js',
      reuseExistingServer: false,
      env: { FORGE_VISUAL: '1' },
    },
    {
      command:
        'pnpm --filter @forge/platform-console preview --host 127.0.0.1 --port 5183 --strictPort',
      url: 'http://127.0.0.1:5183',
      reuseExistingServer: false,
    },
    {
      command: 'pnpm --filter @forge/tenant-app preview --host 127.0.0.1 --port 5184 --strictPort',
      url: 'http://127.0.0.1:5184',
      reuseExistingServer: false,
    },
  ],
})
