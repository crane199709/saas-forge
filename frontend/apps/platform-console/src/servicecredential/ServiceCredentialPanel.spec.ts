import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import { ok, signedIn } from '../test-console'
import ServiceCredentialPanel from './ServiceCredentialPanel.vue'
import { createServiceCredentialApi } from './service-credential-api'

const row = {
  id: 'c1',
  service: 'orders-service',
  status: 'active',
  fingerprint: 'a1234567',
  createdAt: '2026-11-12T03:00:00Z',
  lastUsedAt: null,
}
const token = 'a-one-time-credential'
function mountPanel() {
  signedIn()
  return mount(ServiceCredentialPanel, {
    props: { api: createServiceCredentialApi(createPlatformClient(localStorage)) },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}
function button(name: string, root: ParentNode = document) {
  return Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!
}
enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
it('登记后只在当前对话框交付可复制片段，关闭后停用并更新列表', async () => {
  let created = false
  let disabled = false
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      if (String(_url).endsWith('/disable')) {
        disabled = true
        return ok(null)
      }
      expect(JSON.parse(init.body as string)).toEqual({ service: 'orders-service' })
      created = true
      return ok({ credential: row, token, manifestReported: false })
    }
    return ok({
      service: 'forge-iam',
      available: true,
      credentials: created ? [{ ...row, status: disabled ? 'disabled' : 'active' }] : [],
    })
  })
  const wrapper = mountPanel()
  await flushPromises()
  button('登记凭证').click()
  await flushPromises()
  const input = document.querySelector<HTMLInputElement>('input')!
  input.value = 'orders-service'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  button('登记').click()
  await flushPromises()
  expect(document.querySelector('[data-testid="service-credential-token"]')?.textContent).toBe(
    'FORGE_MANIFEST_TOKEN=' + token,
  )
  expect(document.body.textContent).toContain('尚未上报')
  button('复制片段').click()
  await flushPromises()
  expect(writeText).toHaveBeenCalledWith('FORGE_MANIFEST_TOKEN=' + token)
  button('已保存，关闭').click()
  await flushPromises()
  expect(document.body.textContent).not.toContain(token)
  button('停用', wrapper.element).click()
  await flushPromises()
  button('确认停用').click()
  await flushPromises()
  expect(wrapper.text()).toContain('已停用')
  expect(localStorage.getItem('forge.platform.session')).not.toContain(token)
})
