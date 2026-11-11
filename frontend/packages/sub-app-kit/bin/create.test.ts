import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, writeFile, copyFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'

// 规范原文的替身：生成工程须原样带上标题，并在头部写入平台版本。
const standards = '# Sub-App 页面规范\n\n## 改动页面的流程\n'

async function assertStandards(app: string, version: string) {
  const doc = await readFile(join(app, 'docs/standards/sub-app.md'), 'utf8')
  assert.ok(doc.split('\n', 1)[0].includes(version), doc)
  assert.ok(doc.includes(standards), doc)
  const agents = await readFile(join(app, 'AGENTS.md'), 'utf8')
  assert.ok(agents.includes('docs/standards/sub-app.md'), agents)
  for (const command of [
    'pnpm dev',
    'pnpm lint',
    'pnpm lint:style',
    'pnpm typecheck',
    'pnpm build',
    'pnpm test:visual',
  ])
    assert.ok(agents.includes(command), command)
  const pkg = JSON.parse(await readFile(join(app, 'package.json'), 'utf8'))
  assert.equal(pkg.scripts['lint:style'], 'stylelint "src/**/*.{vue,css}"')
  assert.ok(pkg.scripts.build.includes('pnpm lint:style'), pkg.scripts.build)
  assert.ok(pkg.scripts['test:visual'].includes('playwright.visual.config.ts'))
  assert.ok(pkg.scripts['test:visual:update'].includes('--update-snapshots'))
  assert.match(
    await readFile(join(app, 'stylelint.config.mjs'), 'utf8'),
    /@crane199709\/sub-app-kit\/stylelint/,
  )
  assert.match(await readFile(join(app, 'playwright.visual.config.ts'), 'utf8'), /e2e\/visual/)
  const spec = await readFile(join(app, 'e2e/visual/home.spec.ts'), 'utf8')
  assert.match(spec, /page\.route/)
  assert.match(spec, /localStorage/)
  const home = await readFile(join(app, 'src/HomePage.vue'), 'utf8')
  for (const part of ['PageHeader', 'PageToolbar', 'ListPage', 'formatDateTime'])
    assert.ok(home.includes(part), part)
  await assertMatchesArchetypeOrders(home, spec)
}

// 首页与 forge-service-archetype 的订单接口配套：只用接口返回的字段与接受的参数，生成后前后端即可直接联调。
async function assertMatchesArchetypeOrders(home: string, spec: string) {
  const controller = await readFile(
    new URL(
      '../../../../backend/forge-service-archetype/src/main/resources/archetype-resources/src/main/java/order/controller/OrderController.java',
      import.meta.url,
    ),
    'utf8',
  )
  const responseFields = [
    ...controller.match(/record OrderResponse\(([^)]*)\)/)![1].matchAll(/\w+ (\w+)(?:,|$)/g),
  ].map((field) => field[1])
  const sortable = [...controller.match(/@SortableFields\(\{([^}]*)\}\)/)![1].matchAll(/"(\w+)"/g)].map(
    (field) => field[1],
  )
  const used = [...home.match(/interface Order \{([^}]*)\}/)![1].matchAll(/(\w+):/g)].map(
    (field) => field[1],
  )
  for (const field of used) assert.ok(responseFields.includes(field), `OrderResponse 没有 ${field}`)
  for (const param of home.matchAll(/query\.set\('(\w+)'/g))
    assert.ok(['page', 'size', 'sort'].includes(param[1]), `订单列表不接受 ${param[1]}`)
  for (const sort of home.matchAll(/'(\w+),(?:asc|desc)'/g))
    assert.ok(sortable.includes(sort[1]), `订单列表不能按 ${sort[1]} 排序`)
  const mocked = spec.match(/const orders = \[([\s\S]*?)\n\]/)![1]
  for (const field of mocked.matchAll(/(?:^|[{,]) *(\w+):/gm))
    assert.ok(responseFields.includes(field[1]), `视觉用例 mock 了接口没有的 ${field[1]}`)
}

// 只经生成命令观察工程产物；临时发布布局中没有 Shell 或 monorepo。
test('仓库外生成使用发布版本快照，支持目标目录且拒绝覆盖', async () => {
  const root = await mkdtemp(join(tmpdir(), 'forge-create-'))
  try {
    await mkdir(join(root, 'kit/bin'), { recursive: true })
    await copyFile(new URL('./create.ts', import.meta.url), join(root, 'kit/bin/create.ts'))
    await writeFile(
      join(root, 'kit/versions.json'),
      JSON.stringify({
        '@crane199709/sdk': '0.8.2',
        '@crane199709/ui': '0.8.2',
        '@crane199709/sub-app-kit': '0.8.2',
        vue: '3.5.44',
        'vue-router': '5.4.0',
        pinia: '4.1.0',
        'vue-i18n': '11.4.16',
        'element-plus': '2.14.7',
        '@vitejs/plugin-vue': '6.0.10',
        '@module-federation/vite': '1.23.5',
        '@vue/tsconfig': '0.9.1',
        typescript: '5.9.3',
        vite: '8.3.5',
        'vue-tsc': '3.3.13',
        stylelint: '18.0.0',
        'postcss-html': '2.0.0',
        '@playwright/test': '1.64.1',
      }),
    )
    await mkdir(join(root, 'kit/standards'), { recursive: true })
    await writeFile(join(root, 'kit/standards/sub-app.md'), standards)
    const run = (...args: string[]) =>
      spawnSync(process.execPath, ['kit/bin/create.ts', 'billing', '5180', ...args], {
        cwd: root,
        encoding: 'utf8',
      })
    const generated = run()
    assert.equal(generated.status, 0, generated.stderr)
    // 仓库外工程不在 pnpm workspace 中，提示的命令必须能在工程目录直接运行。
    assert.match(generated.stdout, /^\s+pnpm dev\b/m)
    assert.match(generated.stdout, /^\s+pnpm build\b/m)
    assert.doesNotMatch(generated.stdout, /--filter/)
    const pkg = JSON.parse(await readFile(join(root, 'billing-sub-app/package.json'), 'utf8'))
    assert.equal(pkg.dependencies['@crane199709/sdk'], '0.8.2')
    assert.equal(pkg.dependencies['@crane199709/ui'], '0.8.2')
    assert.equal(pkg.devDependencies['@crane199709/sub-app-kit'], '0.8.2')
    assert.equal(pkg.dependencies.vue, '3.5.44')
    assert.ok(!JSON.stringify(pkg).includes('workspace:'))
    await assertStandards(join(root, 'billing-sub-app'), '0.8.2')
    assert.equal(pkg.devDependencies.stylelint, '18.0.0')
    assert.equal(pkg.devDependencies['postcss-html'], '2.0.0')
    assert.equal(pkg.devDependencies['@playwright/test'], '1.64.1')
    assert.equal(run().status, 1)
    assert.equal(run('custom/app').status, 0)
    assert.equal(
      JSON.parse(await readFile(join(root, 'custom/app/package.json'), 'utf8')).name,
      '@forge/billing-sub-app',
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('仓库命令继续默认生成到 apps 并支持独立编译配置', async () => {
  const repo = await mkdtemp(join(tmpdir(), 'forge-workspace-'))
  const root = join(repo, 'frontend')
  try {
    await mkdir(join(repo, 'docs/standards'), { recursive: true })
    await writeFile(join(repo, 'docs/standards/sub-app.md'), standards)
    await mkdir(join(root, 'packages/sub-app-kit/bin'), { recursive: true })
    await mkdir(join(root, 'apps/tenant-app'), { recursive: true })
    await copyFile(
      new URL('./create.ts', import.meta.url),
      join(root, 'packages/sub-app-kit/bin/create.ts'),
    )
    const kit = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
    // 固定测试输入，避免统一升版改变用例的版本约定。
    await writeFile(
      join(root, 'packages/sub-app-kit/package.json'),
      JSON.stringify({ ...kit, version: '0.8.2' }),
    )
    await copyFile(
      new URL('../../../apps/tenant-app/package.json', import.meta.url),
      join(root, 'apps/tenant-app/package.json'),
    )
    const run = spawnSync(
      process.execPath,
      ['packages/sub-app-kit/bin/create.ts', '--workspace', 'billing', '5180'],
      { cwd: root, encoding: 'utf8' },
    )
    assert.equal(run.status, 0, run.stderr)
    assert.match(run.stdout, /pnpm --filter @forge\/billing-sub-app dev/)
    const pkg = JSON.parse(await readFile(join(root, 'apps/billing-sub-app/package.json'), 'utf8'))
    assert.equal(pkg.dependencies['@crane199709/sdk'], '0.8.2')
    assert.equal(pkg.dependencies['@crane199709/ui'], '0.8.2')
    assert.equal(pkg.devDependencies['@crane199709/sub-app-kit'], '0.8.2')
    assert.ok(!JSON.stringify(pkg).includes('workspace:'))
    const config = JSON.parse(
      await readFile(join(root, 'apps/billing-sub-app/tsconfig.json'), 'utf8'),
    )
    assert.equal(config.extends, undefined)
    await assertStandards(join(root, 'apps/billing-sub-app'), '0.8.2')
    assert.equal(pkg.devDependencies.stylelint, kit.devDependencies.stylelint)
    assert.equal(pkg.devDependencies['@playwright/test'], kit.devDependencies['@playwright/test'])
  } finally {
    await rm(repo, { recursive: true, force: true })
  }
})
