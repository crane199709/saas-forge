import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, writeFile, copyFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'

// 只经生成命令观察工程产物；临时发布布局中没有 Shell 或 monorepo。
test('仓库外生成使用发布版本快照，支持目标目录且拒绝覆盖', async () => {
  const root = await mkdtemp(join(tmpdir(), 'forge-create-'))
  try {
    await mkdir(join(root, 'kit/bin'), { recursive: true })
    await copyFile(new URL('./create.ts', import.meta.url), join(root, 'kit/bin/create.ts'))
    await writeFile(
      join(root, 'kit/versions.json'),
      JSON.stringify({
        '@forge/sdk': '0.8.2',
        '@forge/ui': '0.8.2',
        '@forge/sub-app-kit': '0.8.2',
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
      }),
    )
    const run = (...args: string[]) =>
      spawnSync(process.execPath, ['kit/bin/create.ts', 'billing', '5180', ...args], {
        cwd: root,
        encoding: 'utf8',
      })
    const generated = run()
    assert.equal(generated.status, 0, generated.stderr)
    const pkg = JSON.parse(await readFile(join(root, 'billing-sub-app/package.json'), 'utf8'))
    assert.equal(pkg.dependencies['@forge/sdk'], '0.8.2')
    assert.equal(pkg.dependencies['@forge/ui'], '0.8.2')
    assert.equal(pkg.devDependencies['@forge/sub-app-kit'], '0.8.2')
    assert.equal(pkg.dependencies.vue, '3.5.44')
    assert.ok(!JSON.stringify(pkg).includes('workspace:'))
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
  const root = await mkdtemp(join(tmpdir(), 'forge-workspace-'))
  try {
    await mkdir(join(root, 'packages/sub-app-kit/bin'), { recursive: true })
    await mkdir(join(root, 'apps/tenant-app'), { recursive: true })
    await copyFile(
      new URL('./create.ts', import.meta.url),
      join(root, 'packages/sub-app-kit/bin/create.ts'),
    )
    await copyFile(
      new URL('../package.json', import.meta.url),
      join(root, 'packages/sub-app-kit/package.json'),
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
    const pkg = JSON.parse(await readFile(join(root, 'apps/billing-sub-app/package.json'), 'utf8'))
    assert.equal(pkg.dependencies['@forge/sdk'], '0.1.0')
    assert.ok(!JSON.stringify(pkg).includes('workspace:'))
    const config = JSON.parse(
      await readFile(join(root, 'apps/billing-sub-app/tsconfig.json'), 'utf8'),
    )
    assert.equal(config.extends, undefined)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
