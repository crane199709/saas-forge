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
  const wrapper = await mountWithShell(ExportButton, { props: { submit, tasks } })
  await wrapper.get('[data-testid="export-submit"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(tasks.download).toHaveBeenCalledWith('e1'))
  expect(wrapper.text()).toContain('成功')
  expect(wrapper.text()).toContain('3')
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
