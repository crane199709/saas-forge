#!/usr/bin/env node
// 发布命令默认生成到当前目录；仓库脚本通过 --workspace 保留 apps 目录约定。
// 生成的工程已带隔离约定：只导出相对路由与组件、scoped 样式、从共享依赖根入口导入，并带 lint 与独立开发宿主。
import { mkdir, readFile, lstat, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
const workspace = args[0] === '--workspace'
const [code, portText, directory, ...extra] = workspace ? args.slice(1) : args
if (
  !code ||
  !/^[a-z][a-z0-9-]{0,63}$/.test(code) ||
  !/^\d{4,5}$/.test(portText ?? '') ||
  Number(portText) > 65535 ||
  extra.length > 0
) {
  console.error(
    '用法：forge-create-sub-app <应用编码> <端口> [目标目录]，编码为小写字母开头的小写字母、数字与连字符',
  )
  process.exit(1)
}
const port = Number(portText)
const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const target = directory
  ? resolve(directory)
  : workspace
    ? join(frontend, 'apps', `${code}-sub-app`)
    : resolve(`${code}-sub-app`)
if (await lstat(target).catch(() => null)) {
  console.error(`${target} 已存在`)
  process.exit(1)
}
// 发布包只读取随包快照；源码模式使用同一发布版本与 Shell 依赖。
const versions: Record<string, string> = workspace
  ? await (async () => {
      const shell = JSON.parse(
        await readFile(join(frontend, 'apps/tenant-app/package.json'), 'utf8'),
      )
      const kit = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
      return {
        ...shell.dependencies,
        ...shell.devDependencies,
        ...Object.fromEntries(
          ['@crane199709/sdk', '@crane199709/ui', '@crane199709/sub-app-kit'].map((name) => [
            name,
            kit.version,
          ]),
        ),
        '@module-federation/vite': kit.devDependencies['@module-federation/vite'],
      }
    })()
  : JSON.parse(await readFile(new URL('../versions.json', import.meta.url), 'utf8'))
const version = (name: string) => {
  const value = versions[name]
  if (!value || value.startsWith('workspace:')) throw new Error(`缺少发布依赖版本：${name}`)
  return value
}
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
        [
          '@crane199709/sdk',
          '@crane199709/ui',
          'element-plus',
          'pinia',
          'vue',
          'vue-i18n',
          'vue-router',
        ].map((name) => [name, version(name)]),
      ),
      devDependencies: {
        '@crane199709/sub-app-kit': version('@crane199709/sub-app-kit'),
        ...Object.fromEntries(
          ['@module-federation/vite', '@vitejs/plugin-vue', 'typescript', 'vite', 'vue-tsc'].map(
            (name) => [name, version(name)],
          ),
        ),
      },
    },
    null,
    2,
  )}\n`,
  'tsconfig.json': `{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["vite/client", "element-plus/global"]
  },
  "include": ["src/**/*.ts", "src/**/*.vue"]
}
`,
  'vite.config.ts': `import { defineSubAppConfig } from '@crane199709/sub-app-kit/vite'

// 开发模式经 index.html 运行独立宿主；构建产物暴露 ./sub-app 的 remoteEntry.js，入口地址登记到 App Registry。
export default defineSubAppConfig({ appCode: '${code}', port: ${port}, apiPrefixes: ['/${code}'] })
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
  'src/dev.ts': `import { startDevHost } from '@crane199709/sub-app-kit/dev-host'
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
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElTableColumn } from 'element-plus'
import { ApiError, useTenantClient, type Page } from '@crane199709/sdk'
import { ErrorAlert, ListPage, type ErrorTip } from '@crane199709/ui'

interface Order {
  id: string
  title: string
  amount: number
}

const client = useTenantClient()
const orders = ref<Page<Order> | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '订单',
      refresh: '刷新订单',
      name: '标题',
      amount: '金额',
      empty: '暂无订单',
      failed: '订单加载失败，请稍后重试',
    },
  },
})

async function load(page = orders.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    // 数据范围由服务端裁剪；通过 Shell 的共享客户端，让 401 走统一刷新与登出流程。
    orders.value = await client.page<Order>(\`/${code}/orders?page=\${page}&size=20&sort=createdAt,asc\`)
  } catch (cause) {
    orders.value = null
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('failed'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
  } finally {
    busy.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <section :aria-label="t('title')">
    <h3>{{ t('title') }}</h3>
    <ErrorAlert v-if="error" :tip="error" />
    <ListPage :page="orders" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <template #search>
        <el-button :disabled="busy" @click="load()">{{ t('refresh') }}</el-button>
      </template>
      <el-table-column :label="t('name')">
        <template #default="{ row }">
          <span data-testid="order-title">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="amount" :label="t('amount')" />
    </ListPage>
  </section>
</template>
`,
}

for (const [file, content] of Object.entries(files)) {
  const path = join(target, file)
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, content)
}
console.log(`已生成 ${target}
下一步：
  cd ${JSON.stringify(target)}
  pnpm install
  pnpm --filter @forge/${code}-sub-app dev       # 独立开发宿主 http://localhost:${port}
  pnpm --filter @forge/${code}-sub-app build     # 产物 dist/remoteEntry.js，入口地址登记到 App Registry
并在业务服务的 manifest.json 菜单中以 "app": "${code}" 指向它，在 Plan 的 Feature Pack 中加入 ${code}。`)
