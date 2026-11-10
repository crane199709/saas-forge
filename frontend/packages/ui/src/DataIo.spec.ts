import { flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { ExportButton } from './ExportButton'
import { mountWithShell } from './test-mount'

it('提交导出后展示任务进度，并在完成后提供下载', async () => {
  const submit = vi
    .fn()
    .mockResolvedValue({ id: 'e1', type: 'EXPORT', status: 'WAITING', rowCount: 0 })
  const tasks = {
    list: vi.fn().mockResolvedValue([]),
    get: vi.fn().mockResolvedValue({
      id: 'e1',
      type: 'EXPORT',
      status: 'SUCCEEDED',
      rowCount: 3,
      finishedAt: '2026-11-07T00:00:00Z',
    }),
    download: vi.fn().mockResolvedValue({ url: 'https://storage.test/result' }),
  }
  const open = vi.spyOn(window, 'open').mockImplementation(() => null)
  const wrapper = await mountWithShell(ExportButton, {
    props: { submit, tasks },
    attachTo: document.body,
  })
  await wrapper.get('[data-testid="export-submit"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(tasks.download).toHaveBeenCalledWith('e1'))
  await wrapper.get('[data-testid="export-history"]').trigger('click')
  await vi.waitFor(() => expect(document.body.textContent).toContain('导出 · 成功 · 3 行'))
  expect(open).toHaveBeenCalledWith('https://storage.test/result', '_blank', 'noopener')
  wrapper.unmount()
  open.mockRestore()
})

it('导入按票据直传并提交对象 ID，失败后显示可下载的错误报告', async () => {
  const { ImportDialog } = await import('./ImportDialog')
  const upload = vi.fn().mockResolvedValue({ ok: true })
  vi.stubGlobal('fetch', upload)
  const submit = vi
    .fn()
    .mockResolvedValue({ id: 'i1', type: 'IMPORT', status: 'WAITING', rowCount: 0 })
  const tasks = {
    list: vi.fn().mockResolvedValue([]),
    get: vi.fn().mockResolvedValue({
      id: 'i1',
      type: 'IMPORT',
      status: 'FAILED',
      rowCount: 2,
      failureReason: '校验未通过',
      resultAvailable: true,
      finishedAt: '2026-11-07T00:00:00Z',
    }),
    download: vi.fn().mockResolvedValue({ url: 'https://storage.test/errors' }),
  }
  const template = vi.fn().mockResolvedValue(undefined)
  const wrapper = await mountWithShell(ImportDialog, {
    props: {
      modelValue: true,
      membershipId: 'm',
      confirm: vi.fn(),
      tasks,
      submit,
      downloadTemplate: template,
      prepareUpload: vi.fn().mockResolvedValue({
        objectId: 'source',
        url: 'https://storage.test/upload',
        headers: { 'Content-Type': 'application/octet-stream' },
      }),
    },
    attachTo: document.body,
  })
  await flushPromises()
  const buttons = [...document.body.querySelectorAll('button')]
  buttons.find((button) => button.textContent?.includes('下载模板'))!.click()
  await flushPromises()
  expect(template).toHaveBeenCalledOnce()
  const input = document.body.querySelector('input[type=file]') as HTMLInputElement
  const file = new File(['data'], 'orders.xlsx')
  Object.defineProperty(input, 'files', { value: [file] })
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
  ;(document.body.querySelector('[data-testid=import-submit]') as HTMLButtonElement).click()
  await vi.waitFor(() => expect(submit).toHaveBeenCalledWith('source'))
  expect(upload).toHaveBeenCalledWith('https://storage.test/upload', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: file,
  })
  await vi.waitFor(() => expect(document.body.textContent).toContain('校验未通过'))
  expect(document.body.textContent).toContain('下载错误报告')
  expect(wrapper.emitted('completed')).toHaveLength(1)
  wrapper.unmount()
  vi.unstubAllGlobals()
})

it('校验通过先展示待写入行数，提交人确认后才完成导入', async () => {
  const { ImportDialog } = await import('./ImportDialog')
  const pending = {
    id: 'confirm-me',
    type: 'IMPORT',
    status: 'AWAITING_CONFIRM',
    rowCount: 2,
    membershipId: 'm',
    heartbeatAt: new Date().toISOString(),
  }
  let confirmed = false
  const confirm = vi.fn(async () => {
    confirmed = true
    return { ...pending, status: 'WAITING' }
  })
  const wrapper = await mountWithShell(ImportDialog, {
    props: {
      modelValue: true,
      membershipId: 'm',
      confirm,
      downloadTemplate: async () => {},
      prepareUpload: async () => ({
        objectId: 'source',
        url: 'https://storage.test/upload',
        headers: {},
      }),
      submit: async () => ({ ...pending, status: 'WAITING' }),
      tasks: {
        list: async () => [pending],
        get: async () =>
          confirmed
            ? { ...pending, status: 'SUCCEEDED', finishedAt: new Date().toISOString() }
            : pending,
        download: async () => ({ url: 'https://storage.test/result' }),
      },
    },
    attachTo: document.body,
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
  const input = document.body.querySelector('input[type=file]') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [new File(['data'], 'orders.xlsx')] })
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
  ;(document.body.querySelector('[data-testid=import-submit]') as HTMLButtonElement).click()
  await vi.waitFor(() => expect(document.body.textContent).toContain('将写入 2 行'))
  await vi.waitFor(() => expect(input.disabled).toBe(false))
  expect(confirm).not.toHaveBeenCalled()
  expect(wrapper.emitted('completed')).toBeUndefined()
  ;(
    [...document.body.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('确认写入'),
    ) as HTMLButtonElement
  ).click()
  await vi.waitFor(() => expect(wrapper.emitted('completed')).toHaveLength(1))
  expect(confirm).toHaveBeenCalledWith('confirm-me')
  wrapper.unmount()
  vi.unstubAllGlobals()
})

it('他人任务不显示确认入口，待确认超时与服务端已过期都明确提示', async () => {
  const { IoTaskList } = await import('./IoTaskList')
  const pending = {
    type: 'IMPORT',
    status: 'AWAITING_CONFIRM',
    rowCount: 12345,
    heartbeatAt: new Date().toISOString(),
  }
  const wrapper = await mountWithShell(IoTaskList, {
    props: {
      membershipId: 'm',
      allowConfirm: true,
      tasks: [
        { ...pending, id: 'other', membershipId: 'other' },
        {
          ...pending,
          id: 'late',
          membershipId: 'm',
          heartbeatAt: new Date(Date.now() - 25 * 60 * 60 * 1000).toISOString(),
        },
        {
          ...pending,
          id: 'expired',
          membershipId: 'm',
          status: 'FAILED',
          errorCode: 'io.confirmationExpired',
        },
      ],
    },
  })
  expect(wrapper.findAll('button')).toHaveLength(0)
  expect(wrapper.text().match(/确认已过期，请重新上传/g)).toHaveLength(2)
  wrapper.unmount()
})

it('重新打开导入对话框时刷新已过期的最近任务', async () => {
  const { ImportDialog } = await import('./ImportDialog')
  const pending = {
    id: 'i1',
    type: 'IMPORT' as const,
    status: 'AWAITING_CONFIRM' as const,
    rowCount: 1,
    membershipId: 'm',
    heartbeatAt: new Date().toISOString(),
  }
  let expired = false
  const wrapper = await mountWithShell(ImportDialog, {
    props: {
      modelValue: true,
      membershipId: 'm',
      confirm: vi.fn(),
      downloadTemplate: vi.fn(),
      prepareUpload: vi.fn(),
      submit: vi.fn(),
      tasks: {
        list: async () => [
          expired
            ? { ...pending, status: 'FAILED' as const, errorCode: 'io.confirmationExpired' }
            : pending,
        ],
        get: vi.fn(),
        download: vi.fn(),
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  expect(document.body.textContent).toContain('将写入 1 行')
  await wrapper.setProps({ modelValue: false })
  expired = true
  await wrapper.setProps({ modelValue: true })
  await vi.waitFor(() => expect(document.body.textContent).toContain('确认已过期，请重新上传'))
  wrapper.unmount()
})
