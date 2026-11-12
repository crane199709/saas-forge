import vue from '@vitejs/plugin-vue'
import ElementPlus from 'unplugin-element-plus/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import Components from 'unplugin-vue-components/vite'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [
    vue(),
    // 按需引入 Element Plus：模板中的 <el-*> 由 Components 解析，脚本中具名导入的组件（含 @crane199709/ui）由 ElementPlus 补上样式。
    // 组件类型来自 tsconfig 的 element-plus/global，不生成 components.d.ts。
    // 单元测试中 element-plus 由 Node 直接加载，无法导入 CSS，只解析组件不引样式。
    Components({
      resolvers: [ElementPlusResolver({ importStyle: process.env.VITEST ? false : 'css' })],
      dts: false,
    }),
    ...(!process.env.VITEST ? [ElementPlus({})] : []),
  ],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '^/api(?:/|\\?|$)': {
        target: process.env.FORGE_DEV_GATEWAY ?? 'http://localhost:8080',
        rewrite: (path) => path.replace(/^\/api(?:\/|(?=\?|$))/, '/'),
      },
      '^/iam(?:/|\\?|$)': process.env.FORGE_DEV_GATEWAY ?? 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
  },
})
