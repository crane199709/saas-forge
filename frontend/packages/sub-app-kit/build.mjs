import { chmod, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import ts from 'typescript'

await rm('dist', { recursive: true, force: true })
// 将仓库内共享版本快照打入 Vite 入口；发布包不能再读取 Shell 的 package.json。
for (const entry of ['vite', 'dev-host']) {
  await build({
    configFile: false,
    plugins: [vue()],
    build: {
      emptyOutDir: false,
      lib: { entry: `src/${entry}.ts`, formats: ['es'], fileName: () => `${entry}.js` },
      rolldownOptions: {
        external: (id) => !id.startsWith('.') && !id.startsWith('/') && !id.startsWith('\0'),
      },
    },
  })
}
// Vue 样式抽取后显式载入，消费者无需额外记住一个 CSS 入口。
await writeFile(
  'dist/dev-host.js',
  `import './sub-app-kit.css'\n${await readFile('dist/dev-host.js', 'utf8')}`,
)
await mkdir('dist/bin', { recursive: true })
for (const name of ['create', 'lint', 'rules']) {
  const source = await readFile(`bin/${name}.ts`, 'utf8')
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
      rewriteRelativeImportExtensions: true,
    },
  })
  await writeFile(`dist/bin/${name}.js`, outputText)
  await chmod(`dist/bin/${name}.js`, 0o755)
}

// 生成器与发布包一起携带 Shell 的精确版本，仓库外不访问源码目录。
const shell = JSON.parse(await readFile('../../apps/tenant-app/package.json', 'utf8'))
const kit = JSON.parse(await readFile('package.json', 'utf8'))
const versions = {
  ...shell.dependencies,
  ...shell.devDependencies,
  '@module-federation/vite': kit.devDependencies['@module-federation/vite'],
  ...Object.fromEntries(
    ['@crane199709/sdk', '@crane199709/ui', '@crane199709/sub-app-kit'].map((name) => [
      name,
      kit.version,
    ]),
  ),
}
await writeFile('dist/versions.json', `${JSON.stringify(versions, null, 2)}\n`)
