#!/usr/bin/env node
// 检查当前 Sub-App 的 src 是否遵守隔离约定（工单 02、ADR-0009），违反时列出位置并以非零状态退出。
// src/dev.ts 只用于独立开发，经 @forge/sub-app-kit/dev-host 创建宿主，不进入构建产物，不检查。
import { readFile, readdir } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { check } from './rules.ts'

const root = process.cwd()
const files = (await readdir(join(root, 'src'), { recursive: true }))
  .filter((file) => /\.(ts|vue)$/.test(file) && file !== 'dev.ts' && !file.endsWith('.d.ts'))
  .sort()
const violations = []
for (const file of files) {
  const path = join(root, 'src', file)
  violations.push(...check(relative(root, path), await readFile(path, 'utf8')))
}
for (const violation of violations) {
  console.error(`${violation.file}:${violation.line}  ${violation.message}`)
}
if (violations.length > 0) {
  console.error(`\n${violations.length} 处违反 Sub-App 隔离约定`)
  process.exit(1)
}
console.log(`Sub-App 隔离约定检查通过（${files.length} 个文件）`)
