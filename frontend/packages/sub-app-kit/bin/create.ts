#!/usr/bin/env node
// 生成新的 Sub-App 工程：pnpm create:sub-app <应用编码> <端口>，生成到 frontend/apps/<应用编码>-sub-app。
// 生成的工程已带隔离约定：只导出相对路由与组件、scoped 样式、从共享依赖根入口导入，并带 lint 与独立开发宿主。
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const [code, portText] = process.argv.slice(2)
if (!code || !/^[a-z][a-z0-9-]{0,63}$/.test(code) || !/^\d{4,5}$/.test(portText ?? '')) {
  console.error(
    '用法：pnpm create:sub-app <应用编码> <端口>，编码为小写字母开头的小写字母、数字与连字符',
  )
  process.exit(1)
}
const port = Number(portText)
const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const target = join(frontend, 'apps', `${code}-sub-app`)
if (await stat(target).catch(() => null)) {
  console.error(`${target} 已存在`)
  process.exit(1)
}
// 依赖版本与 Shell 保持一致：共享依赖由 Shell 提供，两端版本相同才经过验证（工单 02）
const shell = JSON.parse(await readFile(join(frontend, 'apps/tenant-app/package.json'), 'utf8'))
const version = (name: string) => shell.dependencies[name] ?? shell.devDependencies[name]
const routeName = (page: string) => `${code}:${page}`

const files: Record<string, string> = {
  'package.json': `${JSON.stringify(
    {
      name: `@forge/${code}-sub-app`,
      version: '0.1.0',
      private: true,
      type: 'module',
      scripts: {
        dev: 'vite',
        build: 'pnpm lint && vue-tsc --noEmit && vite build',
        preview: 'vite preview',
        typecheck: 'vue-tsc --noEmit',
        lint: 'forge-sub-app-lint',
      },
      dependencies: Object.fromEntries(
        ['@forge/sdk', '@forge/ui', 'element-plus', 'pinia', 'vue', 'vue-i18n', 'vue-router'].map(
          (name) => [name, version(name)],
        ),
      ),
      devDependencies: {
        '@forge/sub-app-kit': 'workspace:*',
        ...Object.fromEntries(
          ['@vitejs/plugin-vue', 'typescript', 'vite', 'vue-tsc'].map((name) => [
            name,
            version(name),
          ]),
        ),
      },
    },
    null,
    2,
  )}\n`,
  'tsconfig.json': `{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "types": ["vite/client", "element-plus/global"]
  },
  "include": ["src/**/*.ts", "src/**/*.vue"]
}
`,
  'vite.config.ts': `import { defineSubAppConfig } from '@forge/sub-app-kit/vite'

// 开发模式经 index.html 运行独立宿主；构建产物暴露 ./sub-app 的 remoteEntry.js，入口地址登记到 App Registry。
export default defineSubAppConfig({ appCode: '${code}', port: ${port} })
`,
  'index.html': `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>${code} Sub-App 开发宿主</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/dev.ts"></script>
  </body>
</html>
`,
  'src/dev.ts': `import { startDevHost } from '@forge/sub-app-kit/dev-host'
import { routes } from './sub-app'

// 仅用于 pnpm dev：无需启动 Shell App 工程即可运行与调试，修改代码后经 HMR 更新。
await startDevHost({ appCode: '${code}', routes, home: 'home' })
`,
  'src/sub-app.ts': `import type { RouteRecordRaw } from 'vue-router'
import HomePage from './HomePage.vue'

// Shell 把这些相对路由挂在 /apps/${code} 下；路由名以应用编码为前缀，保证全局唯一。
export const routes: RouteRecordRaw[] = [
  { path: 'home', name: '${routeName('home')}', component: HomePage },
]
`,
  'src/HomePage.vue': `<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useTenantClient } from '@forge/sdk'

// Token 与租户上下文经共享的 @forge/sdk 获取，Sub-App 自身不处理登录；文案放在 local scope，不覆盖 Shell 文案。
const client = useTenantClient()
const { t } = useI18n({
  useScope: 'local',
  messages: { 'zh-CN': { title: '${code}', tenant: '当前租户：{name}' } },
})
</script>

<template>
  <section class="page">
    <h2>{{ t('title') }}</h2>
    <p>{{ t('tenant', { name: client.session()?.tenantName ?? '-' }) }}</p>
  </section>
</template>

<style scoped>
.page {
  border-left: 4px solid var(--forge-color-primary, var(--el-color-primary));
  padding-left: 12px;
}
</style>
`,
}

for (const [file, content] of Object.entries(files)) {
  const path = join(target, file)
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, content)
}
console.log(`已生成 ${target}
下一步：
  pnpm install
  pnpm --filter @forge/${code}-sub-app dev       # 独立开发宿主 http://localhost:${port}
  pnpm --filter @forge/${code}-sub-app build     # 产物 dist/remoteEntry.js，入口地址登记到 App Registry
并在业务服务的 manifest.json 菜单中以 "app": "${code}" 指向它，在 Plan 的 Feature Pack 中加入 ${code}。`)
