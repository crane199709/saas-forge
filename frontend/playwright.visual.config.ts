import { defineConfig } from '@playwright/test'

// 基线只在开发机（macOS）生成和比对：字体渲染随系统变化，不进 CI 的通用 E2E。
//
// maxDiffPixelRatio 取 0.005 是一个被两头夹住的值，不是安全余量，改它之前先读
// .scratch/frontend-visual-quality/issues/08-baseline-drift-under-threshold.md：
// - 收紧会误报：同一份代码的文字渲染会在两个状态间来回翻（同一套基线，两次重生成的改动
//   集合并不相同），翻转幅度实测 0.13%–0.31%，收紧到 0.001 会让这批噪声变成假失败。
// - 放宽会漏报：0.005 在 1024×768 上是约 3932 像素，一个 200×36 的局部区域整块变样也能通过。
// 所以这是一个已知盲区。要真正提高门禁强度，先消除渲染不确定性，再收紧此值。
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
