import { describe, expect, it } from 'vitest'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { findStyleViolations, isLintTarget, lintPackage, runCli } from './lint-ui-style'

const check = (code: string) => findStyleViolations('sample.ts', code)

describe('颜色字面量', () => {
  it('报出 style 对象里的 hex 色值', () => {
    const findings = check(`h('div', { style: { color: '#fff', background: '#1e293b' } })`)

    expect(findings.map(({ rule, property, value }) => ({ rule, property, value }))).toEqual([
      { rule: 'color', property: 'color', value: '#fff' },
      { rule: 'color', property: 'background', value: '#1e293b' },
    ])
  })

  it('报出 rgb()/hsl() 调用串，包括带透明度的写法', () => {
    const findings = check(
      `h('div', {
        style: {
          boxShadow: '0 1px 2px rgba(0, 0, 0, 6%)',
          background: 'hsl(210 40% 50%)',
          color: 'var(--el-text-color-primary)',
        },
      })`,
    )

    expect(findings.map(({ rule, property }) => ({ rule, property }))).toEqual([
      { rule: 'color', property: 'boxShadow' },
      { rule: 'color', property: 'background' },
    ])
  })
})

describe('长度字面量', () => {
  it('报出 px/rem/em 字面量，包括复合值与函数内嵌值', () => {
    const findings = check(
      `h('div', {
        style: {
          gap: '12px',
          fontSize: '1.5rem',
          margin: '4px 0 0',
          width: 'min(640px, 94vw)',
        },
      })`,
    )

    expect(findings.map(({ rule, property, value }) => ({ rule, property, value }))).toEqual([
      { rule: 'length', property: 'gap', value: '12px' },
      { rule: 'length', property: 'fontSize', value: '1.5rem' },
      { rule: 'length', property: 'margin', value: '4px 0 0' },
      { rule: 'length', property: 'width', value: 'min(640px, 94vw)' },
    ])
  })

  it('token 引用、颜色函数、百分比与零值不报', () => {
    const findings = check(
      `h('div', {
        style: {
          padding: 'var(--forge-space-4) 0',
          margin: '0',
          width: '100%',
          display: 'flex',
          backgroundColor: 'color-mix(in srgb, var(--el-color-primary) 10%, transparent)',
          insetInline: 'calc(100% - var(--forge-space-2))',
          height: 'min(100%, var(--forge-space-7))',
        },
      })`,
    )

    expect(findings).toEqual([])
  })
})

describe('AST 精度', () => {
  it('注释里的字面量不报', () => {
    const findings = check(
      `h('div', {
        style: {
          // 历史写法：color: '#fff'
          color: 'var(--el-text-color-primary)',
        },
      })`,
    )

    expect(findings).toEqual([])
  })

  it('非 style 属性里的字面量不报', () => {
    const findings = check(
      `h('div', { title: '#fff', 'aria-label': '12px', style: { color: 'var(--el-text-color-primary)' } })`,
    )

    expect(findings).toEqual([])
  })

  it('常量引用与常量对象不展开、不报', () => {
    const findings = check(
      `const primary = '#409eff'
      const shared = { color: '#fff' }
      h('div', { style: { color: primary, background: shared.color } })
      h('div', { style: shared })`,
    )

    expect(findings).toEqual([])
  })

  it('无插值的模板字符串按字面量检查', () => {
    const findings = check("h('div', { style: { marginLeft: `12px` } })")

    expect(findings.map(({ rule, value }) => ({ rule, value }))).toEqual([
      { rule: 'length', value: '12px' },
    ])
  })

  it('带插值的模板字符串不展开、不报', () => {
    const findings = check("h('div', { style: { marginLeft: `${depth * 20}px` } })")

    expect(findings).toEqual([])
  })
})

describe('裸数字', () => {
  it('无单位语义的属性接受数字', () => {
    const findings = check(
      `h('div', {
        style: {
          fontWeight: 600,
          'font-weight': 700,
          lineHeight: 1.4,
          flex: 1,
          flexShrink: 0,
          opacity: 0.5,
          zIndex: 10,
          minWidth: 0,
        },
      })`,
    )

    expect(findings).toEqual([])
  })

  it('长度属性上的裸数字降级为 warning（Vue 3 不会补 px）', () => {
    const findings = check(`h('div', { style: { marginTop: 8, width: 12 } })`)

    expect(
      findings.map(({ rule, severity, property, value }) => ({ rule, severity, property, value })),
    ).toEqual([
      { rule: 'numeric-length', severity: 'warning', property: 'marginTop', value: '8' },
      { rule: 'numeric-length', severity: 'warning', property: 'width', value: '12' },
    ])
  })
})

describe('显式豁免', () => {
  it('属性前的 style-guard-ignore 注释带理由时豁免', () => {
    const findings = check(
      `h('section', {
        style: {
          // style-guard-ignore: 结果页留白固定 80px，现有间距档位不足
          padding: '80px 16px',
        },
      })`,
    )

    expect(findings).toEqual([])
  })

  it('没有豁免标记的普通注释不生效', () => {
    const findings = check(
      `h('section', {
        style: {
          // 这里就是留白
          padding: '80px 16px',
        },
      })`,
    )

    expect(findings.map(({ rule }) => rule)).toEqual(['length'])
  })
})

describe('扫描范围', () => {
  it('排除测试文件、挂载辅助与 token 定义文件', () => {
    expect(isLintTarget('src/AppLayout.ts')).toBe(true)
    expect(isLintTarget('src/AppLayout.spec.ts')).toBe(false)
    expect(isLintTarget('src/test-mount.ts')).toBe(false)
    expect(isLintTarget('src/theme.ts')).toBe(false)
    expect(isLintTarget('src/theme.spec.ts')).toBe(false)
  })

  it('@forge/ui 当前没有 error 级违规', () => {
    const { scannedFiles, findings } = lintPackage()

    expect(scannedFiles).toBeGreaterThan(20)
    expect(findings.filter(({ severity }) => severity === 'error')).toEqual([])
  })
})

describe('命令行退出码', () => {
  const workspace = () => mkdtempSync(join(tmpdir(), 'forge-ui-lint-'))

  it('有 error 时退出码为 1 并打印位置', () => {
    const root = workspace()
    mkdirSync(join(root, 'src'))
    writeFileSync(join(root, 'src', 'bad.ts'), "h('div', { style: { color: '#fff' } })")
    const lines: string[] = []

    expect(runCli(root, (line) => lines.push(line))).toBe(1)
    expect(lines.join('\n')).toContain('packages/ui/src/bad.ts:1:')
    expect(lines.join('\n')).toContain('[error] color')
    rmSync(root, { recursive: true, force: true })
  })

  it('只有 warning 或无违规时退出码为 0', () => {
    const root = workspace()
    mkdirSync(join(root, 'src'))
    writeFileSync(join(root, 'src', 'warn.ts'), "h('div', { style: { marginTop: 8 } })")
    const lines: string[] = []

    expect(runCli(root, (line) => lines.push(line))).toBe(0)
    expect(lines.join('\n')).toContain('[warning] marginTop')
    expect(lines.join('\n')).toContain('1 处 warning')
    rmSync(root, { recursive: true, force: true })
  })
})
