import { defineConfig } from '@playwright/test'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import mainFlow from './playwright.main-flow.config'

// Browser plugin not available；真实登录与 HTTP 链路通过仓库 Playwright 验收。
export default defineConfig({
  ...mainFlow,
  testMatch: '**/import-rollback.spec.ts',
  timeout: 120_000,
  reporter: [['list']],
  outputDir: join(tmpdir(), 'forge-import-rollback-qa', 'results'),
  use: { ...mainFlow.use, viewport: { width: 1440, height: 900 } },
})
