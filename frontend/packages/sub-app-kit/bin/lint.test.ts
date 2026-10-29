import assert from 'node:assert/strict'
import { test } from 'node:test'
import { check } from './rules.ts'

const messages = (file: string, source: string) =>
  check(file, source).map((violation) => `${violation.line}:${violation.message}`)

test('不得创建应用级实例或导入共享依赖的子路径', () => {
  const found = messages(
    'src/sub-app.ts',
    [
      "import { createApp, ref } from 'vue'",
      "import { createRouter, type RouteRecordRaw } from 'vue-router'",
      "import 'element-plus/dist/index.css'",
      "import { ElButton } from 'element-plus'",
      "import { useTenantClient } from '@forge/sdk'",
    ].join('\n'),
  )
  assert.equal(found.length, 3)
  assert.match(found[0], /^1:不得调用 createApp/)
  assert.match(found[1], /^2:不得调用 createRouter/)
  assert.match(found[2], /^3:只能从共享依赖 element-plus 的根入口导入/)
})

test('不得改写全局路由、history 与文档根节点样式', () => {
  const found = messages(
    'src/a.ts',
    [
      'router.addRoute({ path: "/x" })',
      'history.pushState({}, "", "/x")',
      "location.assign('/')",
      "document.documentElement.style.setProperty('--x', 'red')",
      "router.push({ name: 'demo:overview' })",
    ].join('\n'),
  )
  assert.deepEqual(
    found.map((item) => item.split(':')[0]),
    ['1', '2', '3', '4'],
  )
})

test('.vue 的样式必须 scoped 或 CSS Modules，且不写全局选择器', () => {
  const found = messages(
    'src/A.vue',
    [
      '<script setup lang="ts">',
      "import { ref } from 'vue'",
      '</script>',
      '<template><p /></template>',
      '<style scoped>',
      'p { color: red }',
      ':global(.el-button) { color: red }',
      '</style>',
      '<style>',
      'body { margin: 0 }',
      '</style>',
    ].join('\n'),
  )
  assert.deepEqual(found, [
    '7:不得用 :global 写全局样式',
    '9:<style> 必须是 scoped 或 CSS Modules，避免样式泄漏到 Shell 与其他 Sub-App',
    '10:不得为 html、body 或 :root 设置样式',
  ])
})

test('遵守约定的 Sub-App 没有违规', () => {
  assert.deepEqual(
    check(
      'src/Page.vue',
      [
        '<script setup lang="ts">',
        "import { useRouter } from 'vue-router'",
        "import { useTenantClient } from '@forge/sdk'",
        'const router = useRouter()',
        '</script>',
        '<style module>.a { color: var(--forge-color-primary) }</style>',
      ].join('\n'),
    ),
    [],
  )
})
