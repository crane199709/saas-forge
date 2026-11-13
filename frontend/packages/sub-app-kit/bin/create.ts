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
// Shell 不依赖的构建与走查工具，版本取自 sub-app-kit 自身；build.mjs 写入发布快照时用同一份清单。
const kitTools = [
  '@module-federation/vite',
  '@playwright/test',
  'postcss-html',
  'prettier',
  'stylelint',
]
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
        ...Object.fromEntries(kitTools.map((name) => [name, kit.devDependencies[name]])),
      }
    })()
  : JSON.parse(await readFile(new URL('../versions.json', import.meta.url), 'utf8'))
const version = (name: string) => {
  const value = versions[name]
  if (!value || value.startsWith('workspace:')) throw new Error(`缺少发布依赖版本：${name}`)
  return value
}
const routeName = (page: string) => `${code}:${page}`
// 规范只在仓库 docs/standards 维护一份；发布包由 build.mjs 带上副本。头部写明版本，升级时按 CHANGELOG 重新复制。
const standards = await readFile(
  workspace
    ? join(frontend, '../docs/standards/sub-app.md')
    : new URL('../standards/sub-app.md', import.meta.url),
  'utf8',
)
const platformVersion = version('@crane199709/sub-app-kit')
// 格式规则与平台前端同一份（frontend/.prettierrc.json），发布包由 build.mjs 带上副本。
const prettierConfig = await readFile(
  workspace ? join(frontend, '.prettierrc.json') : new URL('../prettierrc.json', import.meta.url),
  'utf8',
)

const files: Record<string, string> = {
  'package.json': `${JSON.stringify(
    {
      name: `@forge/${code}-sub-app`,
      version: '0.1.0',
      private: true,
      type: 'module',
      scripts: {
        dev: 'vite',
        [`start:${code}`]: `pnpm build && pnpm preview --host 127.0.0.1 --port ${port}`,
        build: 'pnpm lint && pnpm lint:style && vue-tsc --noEmit && vite build',
        preview: 'vite preview',
        typecheck: 'vue-tsc --noEmit',
        lint: 'forge-sub-app-lint',
        'lint:style': 'stylelint "src/**/*.{vue,css}"',
        format: 'prettier --write .',
        'format:check': 'prettier --check .',
        'test:visual': 'playwright test -c playwright.visual.config.ts',
        'test:visual:update': 'playwright test -c playwright.visual.config.ts --update-snapshots',
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
          [
            '@module-federation/vite',
            '@playwright/test',
            '@vitejs/plugin-vue',
            'postcss-html',
            'prettier',
            'stylelint',
            'typescript',
            'vite',
            'vue-tsc',
          ].map((name) => [name, version(name)]),
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

// 宿主只代理用户请求；后端服务凭证由各服务独立注入，约定见 docs/standards/sub-app.md。
// 构建产物暴露 ./sub-app 的 remoteEntry.js，入口地址登记到 App Registry。
export default defineSubAppConfig({
  appCode: '${code}',
  port: ${port},
})
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
await startDevHost({
  appCode: '${code}',
  routes,
  home: 'home',
})
`,
  'src/sub-app.ts': `import type { RouteRecordRaw } from 'vue-router'
import HomePage from './HomePage.vue'

// Shell 把这些相对路由挂在 /apps/${code} 下；路由名以应用编码为前缀，保证全局唯一。
export const routes: RouteRecordRaw[] = [
  {
    path: 'home',
    name: '${routeName('home')}',
    component: HomePage,
  },
]
`,
  'src/HomePage.vue': `<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElOption, ElSelect, ElTableColumn } from 'element-plus'
import { ApiError, useTenantClient, type Page } from '@crane199709/sdk'
import {
  ErrorAlert,
  ListPage,
  PageHeader,
  PageToolbar,
  formatDateTime,
  type ErrorTip,
} from '@crane199709/ui'

interface Order {
  id: string
  title: string
  amount: number
  createdAt: string
}

const client = useTenantClient()
const orders = ref<Page<Order> | null>(null)
// 选项取自 archetype 订单接口 @SortableFields 声明的可排序字段；新增筛选前先让后端接口支持对应参数。
const sorts = [
  { value: 'createdAt,desc', label: 'newest' },
  { value: 'amount,desc', label: 'amountDesc' },
] as const
const sort = ref<string>(sorts[0].value)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '订单',
      sort: '排序方式',
      newest: '最新创建',
      amountDesc: '金额从高到低',
      name: '标题',
      amount: '金额',
      createdAt: '创建时间',
      empty: '暂无订单',
      failed: '订单加载失败，请稍后重试',
    },
  },
})
const money = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function load(page = orders.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    const query = new URLSearchParams({ page: String(page), size: '20', sort: sort.value })
    // 数据范围由服务端裁剪；通过 Shell 的共享客户端，让 401 走统一刷新与登出流程。
    orders.value = await client.page<Order>(\`/${code}/orders?\${query}\`)
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
    <PageHeader :title="t('title')" />
    <PageToolbar>
      <el-select v-model="sort" class="sort" :aria-label="t('sort')" @change="load(1)">
        <el-option
          v-for="item in sorts"
          :key="item.value"
          :value="item.value"
          :label="t(item.label)"
        />
      </el-select>
      <!-- 主操作（如新增）放在 #actions 插槽，靠右显示；每个区域只有一个实心主按钮。 -->
    </PageToolbar>
    <ErrorAlert v-if="error" :tip="error" />
    <ListPage :page="orders" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <el-table-column :label="t('name')" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span data-testid="order-title">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('amount')" width="140" align="right">
        <template #default="{ row }">{{ money.format(row.amount) }}</template>
      </el-table-column>
      <el-table-column :label="t('createdAt')" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </ListPage>
  </section>
</template>

<style scoped>
.sort {
  width: 160px;
}
</style>
`,
  '.prettierrc.json': prettierConfig,
  // 规范副本随平台版本复制、不在本工程修改，格式化不得改写它。
  '.prettierignore': `dist
test-results
playwright-report
pnpm-lock.yaml
docs/standards
`,
  'stylelint.config.mjs': `// 规则随 @crane199709/sub-app-kit 发布：间距、字号、圆角与颜色只用 --forge-* token 或 Element Plus 变量。
export { default } from '@crane199709/sub-app-kit/stylelint'
`,
  'playwright.visual.config.ts': `import { defineConfig } from '@playwright/test'

// 截图随字体渲染变化：团队固定在同一平台生成与比对基线，首次运行 pnpm test:visual:update 后逐张查看再提交。
export default defineConfig({
  testDir: './e2e/visual',
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  fullyParallel: false,
  workers: 1,
  expect: { toHaveScreenshot: { animations: 'disabled', maxDiffPixelRatio: 0.005 } },
  use: {
    channel: 'chromium',
    baseURL: 'http://127.0.0.1:${port}',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  // 开发宿主即可渲染页面；接口全部由用例 mock，不需要网关与后端。
  webServer: {
    command: 'pnpm dev --host 127.0.0.1',
    url: 'http://127.0.0.1:${port}',
    reuseExistingServer: false,
  },
})
`,
  'e2e/visual/home.spec.ts': `import { expect, test, type Page } from '@playwright/test'

const viewports = [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const

// 接近真实长度的数据：含最长标题与大额金额，检查长文本不撑破布局；字段与 archetype 的 OrderResponse 一致。
const orders = [
  {
    id: 'o1',
    title: '华东大区 2026 年第四季度办公设备集中采购订单（含笔记本电脑、显示器与会议室终端）',
    amount: 1286400.5,
    createdAt: '2026-11-08T09:30:00Z',
  },
  { id: 'o2', title: '办公用品补货', amount: 860, createdAt: '2026-11-07T02:15:00Z' },
  {
    id: 'o3',
    title: '生产环境服务器扩容',
    amount: 45200,
    createdAt: '2026-11-06T13:45:00Z',
  },
  {
    id: 'o4',
    title: '年度软件许可续费',
    amount: 98000,
    createdAt: '2026-11-05T08:00:00Z',
  },
  {
    id: 'o5',
    title: '华南区门店装修物料',
    amount: 23650.75,
    createdAt: '2026-11-03T06:20:00Z',
  },
]

/** 以已登录成员身份打开首页；\`items\` 为订单接口返回的条目。 */
async function open(page: Page, items: unknown[]) {
  // 预置假会话：开发宿主读到会话即视为已登录，不访问真实网关。
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'visual-access',
        refreshToken: 'visual-refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '示例科技有限公司',
        identityName: '测试成员',
      }),
    ),
  )
  const ok = (data: unknown, meta?: unknown) => ({
    json: { code: 'ok', httpStatus: 200, data, meta, traceId: 'visual' },
  })
  await page.route(
    (url) => url.pathname.startsWith('/iam/') || url.pathname.startsWith('/api/iam/'),
    (route) => route.fulfill(ok({ permissions: [], menus: [], subApps: [], layout: null })),
  )
  // 只拦截接口：页面地址 /apps/${code}/... 不以 /${code}/ 开头。
  await page.route(
    (url) => url.pathname.startsWith('/api/${code}/'),
    (route) =>
      route.fulfill(
        ok(items, {
          page: { number: 1, size: 20, totalElements: items.length, totalPages: 1 },
        }),
      ),
  )
  await page.goto('/apps/${code}/home')
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('订单列表', async ({ page }) => {
      await open(page, orders)
      await expect(page.getByTestId('order-title')).toHaveCount(orders.length)
      await expect(page).toHaveScreenshot(\`home-\${label}.png\`)
    })

    test('空列表', async ({ page }) => {
      await open(page, [])
      await expect(page.getByText('暂无订单')).toBeVisible()
      await expect(page).toHaveScreenshot(\`home-empty-\${label}.png\`)
    })
  })
}
`,
  'AGENTS.md': `# AGENTS.md

本工程是 SaaS Forge 平台的 Sub-App \`${code}\`（平台版本 ${platformVersion}）：构建产物 \`dist/remoteEntry.js\` 由租户 Shell 经 Module Federation 加载，路由挂在 \`/apps/${code}\` 下。

## 规则摘要

- 新增或调整页面前读 [Sub-App 页面规范](docs/standards/sub-app.md)，按页面简报、功能实现、设计走查三步完成；走查必须逐张查看截图。
- 列表页用 \`PageHeader\` → \`PageToolbar\` → \`ListPage\` 原型；空值显示 \`—\`，时间用 \`formatDateTime\`。
- 样式只用 \`--forge-*\` token 与 Element Plus 的 \`--el-*\` 变量，不写间距、字号、圆角和颜色字面量。
- 隔离约定：只在 \`src/sub-app.ts\` 导出相对路由；不创建 Vue 应用、Router、Pinia、i18n 实例；\`<style>\` 用 \`scoped\`；共享依赖只从根入口导入；接口经 \`useTenantClient()\` 调用，不读写 Shell 的 \`forge.*\` 会话存储。
- 配套 Business Service 继承 \`forge-dependencies\` 的中间件依赖门禁与 Checkstyle \`NeedBraces\`；Java 的 \`if\`、\`else\`、\`for\`、\`while\`、\`do-while\` 语句体必须加大括号，后端 \`./mvnw validate\` 检查生产与测试代码；中间件通过后端 starter 接入，Sub-App 经 HTTP 接口使用这些能力。
- 配套后端按需使用 storage 时，StoragePurpose 保留期由服务声明；删除先失效并保留七天，期间仍占容量，前端按服务端结果展示。默认模板不启用 storage，后端已有表升级须新增迁移复制 upgrade-retention.sql。引用检查器须显式声明覆盖用途、包含逻辑删除的业务行并保留租户隔离；未覆盖或检查失败时跳过治理；storage 的 audit/scheduling 依赖、MQ/Redis 与 IAM 必配项按配套后端业务规范接入。
- 敏感字段展示按 [规范「敏感字段展示」](docs/standards/sub-app.md#敏感字段展示) 消费服务端结果，页面不重算掩码；原文查看使用专用授权操作；预览失败不回显原样例，字段脱敏失败的导出任务没有结果文件；后端脱敏运行时的 IAM 地址和 Service Credential 不注入前端。
- 视觉用例放 \`e2e/visual/\`：在 \`localStorage\` 预置假会话，用 \`page.route\` mock 接口，覆盖接近真实长度的数据、空值与空列表。

## 命令

- \`pnpm dev\`：独立开发宿主 \`http://localhost:${port}\`，经代理访问本地网关
- \`pnpm start:${code}\`：构建后在 \`http://127.0.0.1:${port}\` 预览，供租户 Shell 加载 \`remoteEntry.js\`；源码修改后重新执行
- \`pnpm lint\`：隔离约定检查
- \`pnpm lint:style\`：样式 token 检查
- \`pnpm typecheck\`：类型检查
- \`pnpm format\`：按平台统一的 Prettier 规则格式化；\`pnpm format:check\` 只检查，提交前须通过
- \`pnpm build\`：依次执行 lint、lint:style、类型检查，产出 \`dist/remoteEntry.js\`
- \`pnpm test:visual\`：视觉回归，与 \`e2e/visual/__screenshots__/\` 的基线比对
- \`pnpm test:visual:update\`：生成或更新基线截图，逐张查看后与代码一同提交

## 规范

- [docs/standards/sub-app.md](docs/standards/sub-app.md)：页面流程、原型、组件、交互、样式与走查清单。文件随平台版本复制，不在本工程修改；平台升级时按 CHANGELOG 重新复制。
`,
  'docs/standards/sub-app.md': `<!-- 平台版本 ${platformVersion}：由 forge-create-sub-app 复制，不在本工程修改；平台升级时按 CHANGELOG 重新复制。 -->

${standards}`,
}

for (const [file, content] of Object.entries(files)) {
  const path = join(target, file)
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, content)
}
// 仓库内工程属于 pnpm workspace，须在仓库根目录用 --filter 运行；仓库外工程独立安装，直接在工程目录运行。
const next = workspace
  ? `下一步（在仓库根目录 frontend 下执行）：
  pnpm install
  pnpm --filter @forge/${code}-sub-app format    # 模板按默认编码排版，较长的编码会改变折行
  pnpm --filter @forge/${code}-sub-app dev       # 独立开发宿主 http://localhost:${port}
  pnpm --filter @forge/${code}-sub-app start:${code} # 构建并预览，供租户 Shell 加载 http://127.0.0.1:${port}/remoteEntry.js
  pnpm --filter @forge/${code}-sub-app build     # 产物 dist/remoteEntry.js，入口地址登记到 App Registry`
  : `下一步：
  cd ${JSON.stringify(target)}
  pnpm install
  pnpm format    # 模板按默认编码排版，较长的编码会改变折行
  pnpm dev       # 独立开发宿主 http://localhost:${port}
  pnpm start:${code} # 构建并预览，供租户 Shell 加载 http://127.0.0.1:${port}/remoteEntry.js
  pnpm build     # 产物 dist/remoteEntry.js，入口地址登记到 App Registry`
console.log(`已生成 ${target}
${next}
并在业务服务的 manifest.json 菜单中以 "app": "${code}" 指向它，在 Plan 的 Feature Pack 中加入 ${code}。`)
