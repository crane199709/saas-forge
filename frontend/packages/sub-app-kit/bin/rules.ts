/** 一条违反隔离约定的位置。 */
export interface Violation {
  file: string
  line: number
  message: string
}

// Shell 以 singleton 提供的共享依赖：只能从根入口导入，子路径可能绕开共享声明得到第二份实例（工单 02 坑点 5）。
const SHARED = ['vue', 'vue-router', 'pinia', 'element-plus', 'vue-i18n', '@forge/ui', '@forge/sdk']

// Shell 统一创建应用级实例，Sub-App 只导出路由与组件（工单 02 坑点 1）。
const FACTORIES: Record<string, string[]> = {
  vue: ['createApp', 'createSSRApp'],
  'vue-router': ['createRouter', 'createWebHistory', 'createWebHashHistory', 'createMemoryHistory'],
  pinia: ['createPinia'],
  'vue-i18n': ['createI18n'],
}

interface Pattern {
  test: RegExp
  message: string
}

// 路由与 history 归 Shell 所有；主题 token 由 Shell 在文档根节点维护。
const CODE_PATTERNS: Pattern[] = [
  {
    test: /\.(addRoute|removeRoute)\s*\(/,
    message: '不得增删全局路由，路由只经 sub-app 导出的 routes 声明',
  },
  {
    test: /\bhistory\.(pushState|replaceState)\s*\(/,
    message: '不得直接改写 history，使用 RouterLink/useRouter 在本应用路由内导航',
  },
  {
    test: /\b(window\.)?location\.(assign|replace)\s*\(|\blocation\.href\s*=/,
    message: '不得整页跳转，租户切换与登录只由 Shell 负责',
  },
  {
    test: /document\.(documentElement|body)\.(style|classList|className)/,
    message: '不得修改文档根节点样式，主题 token 由 Shell 维护',
  },
  {
    test: /\b(local|session)Storage\.(setItem|removeItem|clear)\s*\(\s*['"]forge\./,
    message: '不得读写 Shell 的会话存储，Token 经 @forge/sdk 获取',
  },
]

const STYLE_PATTERNS: Pattern[] = [
  { test: /:global\s*\(/, message: '不得用 :global 写全局样式' },
  { test: /^\s*(html|body|:root)\b/, message: '不得为 html、body 或 :root 设置样式' },
]

function lineOf(source: string, index: number): number {
  return source.slice(0, index).split('\n').length
}

function checkImports(file: string, source: string, violations: Violation[]) {
  // 导入子句不含引号，避免把副作用导入与下一条导入连成一条
  const imports = /\bimport\s+(?:([^'";]*?)\s+from\s+)?['"]([^'"]+)['"]/g
  for (const match of source.matchAll(imports)) {
    const [, clause = '', specifier] = match
    const line = lineOf(source, match.index)
    const root = SHARED.find((name) => specifier.startsWith(`${name}/`))
    if (root) {
      violations.push({
        file,
        line,
        message: `只能从共享依赖 ${root} 的根入口导入，不得导入 ${specifier}`,
      })
    }
    for (const name of FACTORIES[specifier] ?? []) {
      if (new RegExp(`\\b${name}\\b`).test(clause)) {
        violations.push({
          file,
          line,
          message: `不得调用 ${name}：应用、路由、Pinia 与 i18n 实例由 Shell 创建`,
        })
      }
    }
  }
}

function checkPatterns(
  file: string,
  source: string,
  offset: number,
  patterns: Pattern[],
  violations: Violation[],
) {
  source.split('\n').forEach((text, index) => {
    for (const pattern of patterns) {
      if (pattern.test.test(text)) {
        violations.push({ file, line: offset + index, message: pattern.message })
      }
    }
  })
}

/**
 * 按工单 02 的结论检查一个 Sub-App 源文件是否违反隔离约定。Module Federation 不提供沙箱，隔离依靠这些约定（ADR-0009）。
 *
 * @param file 用于报告的文件路径；`.vue` 文件额外检查 `<style>` 块
 * @param source 文件内容
 */
export function check(file: string, source: string): Violation[] {
  const violations: Violation[] = []
  checkImports(file, source, violations)
  if (!file.endsWith('.vue')) {
    checkPatterns(file, source, 1, CODE_PATTERNS, violations)
    return violations
  }
  const blocks = /<(script|style)\b([^>]*)>([\s\S]*?)<\/\1>/g
  for (const match of source.matchAll(blocks)) {
    const [, tag, attributes, body] = match
    const offset = lineOf(source, match.index)
    if (tag === 'script') {
      checkPatterns(file, body, offset, CODE_PATTERNS, violations)
    } else {
      if (!/\b(scoped|module)\b/.test(attributes)) {
        violations.push({
          file,
          line: offset,
          message: '<style> 必须是 scoped 或 CSS Modules，避免样式泄漏到 Shell 与其他 Sub-App',
        })
      }
      checkPatterns(file, body, offset, STYLE_PATTERNS, violations)
    }
  }
  return violations
}
