import vue from '@vitejs/plugin-vue'
import { federation } from '@module-federation/vite'
import { defineConfig } from 'vite'
import { sharedDependencies } from '../../federation.shared.ts'

export default defineConfig(({ mode }) => ({
  base: './',
  plugins: [
    vue(),
    federation({
      name: 'demo_sub_app',
      filename: 'remoteEntry.js',
      exposes: { './sub-app': './src/sub-app.ts' },
      shared: sharedDependencies(false),
      shareStrategy: 'loaded-first',
      dts: false,
    }),
  ],
  define: { __DEMO_VERSION__: JSON.stringify(mode === 'v2' ? 'v2' : 'v1') },
  preview: { port: 5175, strictPort: true, cors: { origin: 'http://127.0.0.1:5174' } },
}))
