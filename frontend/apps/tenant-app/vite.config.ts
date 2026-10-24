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
    proxy: {
      '/iam': 'http://localhost:8080',
      '/demo': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
  },
})
