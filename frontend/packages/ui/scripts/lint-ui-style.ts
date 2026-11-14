import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'

/**
 * `@forge/ui` 的内联样式守卫：扫描 `h()` 传入的 style 对象，禁止字面量色值与长度。
 *
 * 为什么单独做：本包全部用 `h()` 写内联样式，stylelint 只覆盖 `.vue` 与 `.css`，这是当前最大的规范盲区。
 * 为什么用 TypeScript 编译器 API 而不是正则：AST 能区分「style 对象里的值」与「注释、普通属性、常量引用里的同名文本」。
 *
 * 扫描范围与例外：
 * - 只扫 `packages/ui/src` 下的 `.ts`，跳过 `*.spec.ts` 与 `test-mount.ts`（测试夹具不是产品样式）。
 * - **必须排除 `src/theme.ts`**：它是主题 token 的唯一来源，里面的色值与长度是「定义」而不是「违规」。
 *   不要以「统一扫描」为由把它加回来，否则 token 定义会永久报错，只能靠豁免掩盖。
 * - 常量引用不展开：`color: SOME_CONST` 与 `style: sharedStyle` 不检查。AST 不做类型分析，
 *   展开常量要跨文件求值，代价与误报都不值得；这是刻意的边界，不是遗漏。
 * - 带插值的模板字符串不展开，只把它当作运行时拼值，不判定。
 *
 * 例外还可用注释声明：在属性上一行写 `// style-guard-ignore: 理由`，只豁免该属性。
 * 这是最后手段——优先用现有 token；确实没有对应档位时再写，并在理由里说明何时提为 token。
 */

/** 违规类别：色值、长度字面量，以及数值长度写法（Vue 3 不会补 px，单独降级为 warning）。 */
export type StyleRule = 'color' | 'length' | 'numeric-length'

/** 一条检查结果；`file` 是相对 `@forge/ui` 包根目录的路径。 */
export interface Finding {
  file: string
  line: number
  column: number
  property: string
  value: string
  severity: 'error' | 'warning'
  rule: StyleRule
  message: string
}

/** 一次扫描的结果。 */
export interface LintResult {
  scannedFiles: number
  findings: Finding[]
}

/** token 定义文件：这里的字面量是定义，永远不参与检查。 */
export const TOKEN_FILE = 'src/theme.ts'

const PACKAGE_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

const HEX_COLOR = /#[0-9a-f]{3,8}\b/i
const COLOR_FUNCTION = /\b(rgba?|hsla?)\(/i

/** 与 `sub-app-kit` 的 stylelint 规则保持一致：只认 px、rem、em 三种长度单位。 */
const LENGTH_UNIT = /\d(px|rem|em)\b/i

const COLOR_MESSAGE =
  '色值必须用 token：var(--forge-*)、var(--el-color-*)、var(--el-text-color-*) 或 color-mix()'
const LENGTH_MESSAGE =
  '长度必须用 token：var(--forge-space-*)、var(--forge-radius-*)、var(--forge-font-size-*) 或 calc()/min()/max()'
const NUMERIC_MESSAGE =
  'Vue 3 不对数值补 px，数字长度等于无效样式；改用 token 字符串（如 var(--forge-space-2)）'

/** 数值本身带单位语义的属性：Vue 按无单位数值写入才是对的。 */
const UNITLESS_PROPERTIES = new Set([
  'aspectratio',
  'columncount',
  'flex',
  'flexgrow',
  'flexshrink',
  'fontweight',
  'gridcolumn',
  'gridrow',
  'lineheight',
  'opacity',
  'order',
  'orphans',
  'tabsize',
  'widows',
  'zindex',
  'zoom',
])

/** 属性名可能写成 camelCase 或 kebab-case（含带引号的字符串键），统一去掉引号与连字符再比对。 */
const normalizeProperty = (name: string) => name.replaceAll(/['"-]/g, '').toLowerCase()

/** 豁免标记：属性上一行的注释写 `style-guard-ignore`，只豁免该属性。 */
const IGNORE_MARKER = 'style-guard-ignore'

/** 只有产品源码参与检查；测试与 token 定义文件不在此列（理由见文件头注释）。 */
export function isLintTarget(file: string): boolean {
  const normalized = file.replaceAll('\\', '/')
  if (!normalized.startsWith('src/') || !normalized.endsWith('.ts')) return false
  if (normalized.endsWith('.spec.ts')) return false
  if (normalized === TOKEN_FILE || normalized === 'src/test-mount.ts') return false
  return true
}

function styleObjectOf(node: ts.Node): ts.ObjectLiteralExpression | undefined {
  if (!ts.isPropertyAssignment(node)) return undefined
  if (!(ts.isIdentifier(node.name) || ts.isStringLiteral(node.name))) return undefined
  if (node.name.text !== 'style') return undefined
  return ts.isObjectLiteralExpression(node.initializer) ? node.initializer : undefined
}

/** 沿 AST 收集字面量检查结果，不读注释、不拼接源码文本，因此注释里的值不会被误报。 */
export function findStyleViolations(file: string, text: string): Finding[] {
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true)
  const findings: Finding[] = []

  const report = (
    property: ts.PropertyAssignment,
    value: string,
    rule: StyleRule,
    message: string,
    severity: Finding['severity'] = 'error',
  ) => {
    const { line, character } = source.getLineAndCharacterOfPosition(property.getStart(source))
    findings.push({
      file,
      line: line + 1,
      column: character + 1,
      property: property.name.getText(source),
      value,
      severity,
      rule,
      message,
    })
  }

  const propertyName = (property: ts.PropertyAssignment) =>
    ts.isIdentifier(property.name) || ts.isStringLiteral(property.name)
      ? property.name.text
      : property.name.getText(source)

  const isExempt = (property: ts.PropertyAssignment) =>
    (ts.getLeadingCommentRanges(source.text, property.getFullStart()) ?? []).some((comment) =>
      source.text.slice(comment.pos, comment.end).includes(IGNORE_MARKER),
    )

  const checkProperty = (property: ts.PropertyAssignment) => {
    const value = property.initializer
    if (ts.isStringLiteral(value) || ts.isNoSubstitutionTemplateLiteral(value)) {
      if (HEX_COLOR.test(value.text) || COLOR_FUNCTION.test(value.text))
        report(property, value.text, 'color', COLOR_MESSAGE)
      else if (LENGTH_UNIT.test(value.text)) report(property, value.text, 'length', LENGTH_MESSAGE)
      return
    }
    if (!ts.isNumericLiteral(value) || Number(value.text) === 0) return
    if (UNITLESS_PROPERTIES.has(normalizeProperty(propertyName(property)))) return
    report(property, value.text, 'numeric-length', NUMERIC_MESSAGE, 'warning')
  }

  const visit = (node: ts.Node) => {
    const style = styleObjectOf(node)
    if (style) {
      for (const property of style.properties)
        if (ts.isPropertyAssignment(property) && !isExempt(property)) checkProperty(property)
    }
    ts.forEachChild(node, visit)
  }
  visit(source)

  return findings
}

function collectFiles(directory: string, root: string, files: string[] = []): string[] {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = join(directory, entry.name)
    if (entry.isDirectory()) collectFiles(full, root, files)
    else if (entry.isFile() && isLintTarget(relative(root, full))) files.push(full)
  }
  return files
}

/** 扫描整个包并返回结果；默认扫 `@forge/ui` 自身。 */
export function lintPackage(packageRoot: string = PACKAGE_ROOT): LintResult {
  const sourceRoot = join(packageRoot, 'src')
  const files = collectFiles(sourceRoot, packageRoot)

  return {
    scannedFiles: files.length,
    findings: files.flatMap((file) =>
      findStyleViolations(relative(packageRoot, file), readFileSync(file, 'utf8')),
    ),
  }
}

/** 把一条结果格式化为单行；路径按前端工作区习惯补上包前缀。 */
export function formatFinding(finding: Finding): string {
  return `packages/ui/${finding.file}:${finding.line}:${finding.column} [${finding.severity}] ${finding.property}: ${finding.value} —— ${finding.message}`
}

/** 执行检查并打印结果，返回进程退出码：有 error 为 1，只有 warning 为 0。 */
export function runCli(packageRoot: string = PACKAGE_ROOT, write = console.log): number {
  const { scannedFiles, findings } = lintPackage(packageRoot)
  for (const finding of findings) write(formatFinding(finding))

  const errors = findings.filter(({ severity }) => severity === 'error').length
  const warnings = findings.length - errors
  if (errors > 0) {
    write(`lint:ui 失败：${errors} 处 error，${warnings} 处 warning（扫描 ${scannedFiles} 个文件）`)
    return 1
  }
  write(`lint:ui 通过：扫描 ${scannedFiles} 个文件，${warnings} 处 warning`)
  return 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  process.exitCode = runCli()
