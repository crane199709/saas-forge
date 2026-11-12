import { defineConfig } from '@playwright/test'
import mainFlow from './playwright.main-flow.config'

export default defineConfig({
  ...mainFlow,
  testMatch: '**/service-onboarding.spec.ts',
  use: { ...mainFlow.use, baseURL: 'http://127.0.0.1:15173' },
  webServer: [
    ...(Array.isArray(mainFlow.webServer) ? mainFlow.webServer : []),
    {
      command:
        'pnpm --filter @forge/platform-console preview --host 127.0.0.1 --port 15173 --strictPort',
      url: 'http://127.0.0.1:15173',
      reuseExistingServer: false,
      env: { FORGE_DEV_GATEWAY: 'http://127.0.0.1:18000' },
    },
  ],
})
