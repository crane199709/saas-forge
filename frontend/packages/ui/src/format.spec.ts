import { expect, it } from 'vitest'
import { formatDateTime } from './format'

it('按 zh-CN 输出日期与 24 小时制时间', () => {
  expect(formatDateTime('2026-11-07T04:00:00Z')).toMatch(/^2026\/11\/\d{1,2} \d{1,2}:\d{2}:\d{2}$/)
})

it('时间缺失时显示占位符', () => {
  expect(formatDateTime(null)).toBe('—')
  expect(formatDateTime(undefined)).toBe('—')
})
