import { flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { mountWithShell } from './test-mount'
import { ImportRollbackDialog } from './ImportRollbackDialog'

const preview = {
  taskId: 'task-1',
  available: true,
  blocked: true,
  totalCount: 2,
  rollbackableCount: 1,
  conflictCount: 1,
  snapshotExpiresAt: '2027-02-11T00:00:00Z',
  conflicts: [{ recordId: 'order-a', reason: 'RECORD_MODIFIED' }],
}
const button = (text: string) =>
  [...document.body.querySelectorAll('button')].find(
    (b) => b.textContent?.trim() === text,
  ) as HTMLButtonElement
it('先预览冲突，管理员明确勾选跳过后才允许恢复，并展示累计结果', async () => {
  const execute = vi.fn().mockResolvedValue({
    taskId: 'task-1',
    status: 'PARTIAL',
    restoredCount: 1,
    skippedCount: 1,
    failedCount: 0,
    conflicts: preview.conflicts,
  })
  const wrapper = await mountWithShell(ImportRollbackDialog, {
    props: { modelValue: true, taskId: 'task-1', api: { preview: async () => preview, execute } },
    attachTo: document.body,
  })
  await flushPromises()
  expect(document.body.textContent).toContain('导入后已被修改')
  expect(button('确认回滚').disabled).toBe(true)
  expect(execute).not.toHaveBeenCalled()
  ;(document.body.querySelector('input[type=checkbox]') as HTMLInputElement).click()
  await flushPromises()
  button('确认回滚').click()
  await flushPromises()
  expect(execute).toHaveBeenCalledWith('task-1', true)
  expect(document.body.textContent).toContain('已恢复 1 条，跳过 1 条，失败 0 条')
  expect(wrapper.emitted('completed')).toHaveLength(1)
  wrapper.unmount()
})

it('快照已过期时展示实际截止时间和原因，不能执行', async () => {
  const execute = vi.fn()
  const wrapper = await mountWithShell(ImportRollbackDialog, {
    props: {
      modelValue: true,
      taskId: 'expired',
      api: {
        preview: async () => ({
          ...preview,
          available: false,
          unavailableReason: 'SNAPSHOT_EXPIRED',
          blocked: false,
        }),
        execute,
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  expect(document.body.textContent).toContain('快照已过期')
  expect(document.body.textContent).toContain('2027/2/11')
  expect(button('确认回滚')).toBeUndefined()
  expect(execute).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('执行发现新冲突时重取报告并清除跳过确认，重新确认后可完成', async () => {
  const { ApiError } = await import('@crane199709/sdk')
  let reads = 0
  const execute = vi
    .fn()
    .mockRejectedValueOnce(new ApiError({ code: 'io.rollbackConflicts', httpStatus: 409 }))
    .mockResolvedValue({
      taskId: 'task-1',
      status: 'SUCCEEDED',
      restoredCount: 2,
      skippedCount: 0,
      failedCount: 0,
      conflicts: [],
    })
  const wrapper = await mountWithShell(ImportRollbackDialog, {
    props: {
      modelValue: true,
      taskId: 'task-1',
      api: {
        preview: async () => {
          reads++
          return reads === 1
            ? { ...preview, blocked: false, conflictCount: 0, conflicts: [] }
            : preview
        },
        execute,
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  button('确认回滚').click()
  await flushPromises()
  expect(document.body.textContent).toContain('数据状态已变化')
  expect(button('确认回滚').disabled).toBe(true)
  ;(document.body.querySelector('input[type=checkbox]') as HTMLInputElement).click()
  await flushPromises()
  button('确认回滚').click()
  await flushPromises()
  expect(document.body.textContent).toContain('已回滚')
  expect(document.body.querySelector('[data-testid=rollback-summary]')).toBeNull()
  wrapper.unmount()
})

it('部分回滚后过期仍显示历史计数和剩余记录不可回滚原因，全恢复保留已回滚', async () => {
  const { ImportHistoryDialog } = await import('./ImportHistoryDialog')
  const base = {
    type: 'IMPORT',
    status: 'SUCCEEDED',
    membershipId: 'm',
    rowCount: 3,
    snapshotCount: 2,
    snapshotAvailable: false,
    snapshotExpiresAt: '2020-01-01T00:00:00Z',
    rollbackAttempts: 1,
    restoredCount: 1,
    skippedCount: 1,
    failedCount: 0,
  }
  const wrapper = await mountWithShell(ImportHistoryDialog, {
    props: {
      modelValue: true,
      membershipId: 'm',
      modelLabel: () => '覆盖导入',
      tasks: {
        list: async () => ({
          items: [
            { ...base, id: 'partial', rollbackStatus: 'PARTIAL' },
            {
              ...base,
              id: 'complete',
              rollbackStatus: 'SUCCEEDED',
              restoredCount: 2,
              skippedCount: 0,
            },
          ],
          page: { number: 1, size: 10, totalElements: 2, totalPages: 1 },
        }),
        get: vi.fn(),
        download: vi.fn(),
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  expect(document.body.textContent).toContain('部分回滚')
  expect(document.body.textContent).toContain('不可回滚：快照已过期')
  expect(document.body.textContent).toContain('已恢复 1 条，跳过 1 条，失败 0 条')
  expect(document.body.textContent).toContain('已回滚')
  expect(document.body.textContent?.match(/不可回滚：快照已过期/g)).toHaveLength(1)
  expect(button('回滚导入')).toBeUndefined()
  wrapper.unmount()
})

it('逐条恢复失败后保留结果并允许重试，成功后不再提供重复恢复入口', async () => {
  const execute = vi
    .fn()
    .mockResolvedValueOnce({
      taskId: 'task-1',
      status: 'PARTIAL',
      restoredCount: 1,
      skippedCount: 0,
      failedCount: 1,
      conflicts: [],
    })
    .mockResolvedValueOnce({
      taskId: 'task-1',
      status: 'SUCCEEDED',
      restoredCount: 2,
      skippedCount: 0,
      failedCount: 0,
      conflicts: [],
    })
  const wrapper = await mountWithShell(ImportRollbackDialog, {
    props: {
      modelValue: true,
      taskId: 'task-1',
      api: {
        preview: async () => ({ ...preview, blocked: false, conflictCount: 0, conflicts: [] }),
        execute,
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  button('确认回滚').click()
  await flushPromises()
  expect(document.body.textContent).toContain('已恢复 1 条，跳过 0 条，失败 1 条')
  expect(button('重试未完成记录').disabled).toBe(false)
  button('重试未完成记录').click()
  await flushPromises()
  expect(document.body.textContent).toContain('已恢复 2 条，跳过 0 条，失败 0 条')
  expect(button('重试未完成记录')).toBeUndefined()
  expect(button('确认回滚')).toBeUndefined()
  wrapper.unmount()
})
