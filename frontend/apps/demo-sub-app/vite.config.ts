import { defineSubAppConfig } from '@crane199709/sub-app-kit/vite'

// 开发模式经 index.html 运行独立宿主；构建产物暴露 ./sub-app 的 remoteEntry.js。v2 模式只用于验证修改注册表地址即切换版本。
export default defineSubAppConfig(
  {
    appCode: 'demo',
    port: 5175,
    shellOrigins: process.env.FORGE_E2E
      ? ['http://127.0.0.1:15174']
      : process.env.FORGE_VISUAL
        ? ['http://127.0.0.1:5184']
        : undefined,
  },
  ({ mode }) => ({ define: { __DEMO_VERSION__: JSON.stringify(mode === 'v2' ? 'v2' : 'v1') } }),
)
