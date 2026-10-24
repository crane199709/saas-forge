import vue from '@vitejs/plugin-vue'
import { federation } from '@module-federation/vite'
import { defineConfig } from 'vitest/config'
import { sharedDependencies } from '../../federation.shared.ts'

export default defineConfig({
  plugins: [
    vue(),
    ...(!process.env.VITEST
      ? [
          federation({
            name: 'tenant_shell',
            remotes: {},
            shared: sharedDependencies(true),
            shareStrategy: 'loaded-first',
            dts: false,
          }),
        ]
      : []),
  ],
  server: {
    port: 5174,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
  },
})
