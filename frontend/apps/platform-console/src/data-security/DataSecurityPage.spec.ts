import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { fakeFetch, grants, mountConsole, ok, signedIn } from '../test-console'

enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
const rule = {
  code: 'phone',
  name: '手机号脱敏',
  rule: { strategy: 'phone', keepFirst: null, replacement: null },
}
const grades = ['L1', 'L2', 'L3', 'L4'].map((grade) => ({
  grade,
  name: ({ L1: '公开', L2: '内部', L3: '敏感', L4: '极敏感' } as Record<string, string>)[grade],
  defaultRule: 'phone',
  exportAllowed: grade !== 'L4',
}))
function readBackend(permissions = ['platform:data-security:read']) {
  return fakeFetch({
    '/iam/platform/access': [grants(...permissions)],
    '/iam/platform/data-security/rules': [[rule]],
    '/iam/platform/data-security/grades': [grades],
  })
}
it('只读运营人员可进入数据安全规则和分级视图，没有配置入口', async () => {
  signedIn()
  vi.stubGlobal('fetch', readBackend())
  const { wrapper, router } = await mountConsole('/data-security')
  expect(router.currentRoute.value.path).toBe('/data-security')
  expect(wrapper.get('.shell-menu').text()).toContain('数据安全')
  expect(wrapper.text()).toContain('手机号脱敏')
  expect(wrapper.find('[data-testid="rule-create"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="rule-edit"]').exists()).toBe(false)
  await wrapper.get('#tab-grades').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('L4 极敏感')
  expect(wrapper.text()).toContain('禁止导出')
  expect(wrapper.find('[data-testid="grade-edit"]').exists()).toBe(false)
})

async function choose(
  wrapper: Awaited<ReturnType<typeof mountConsole>>['wrapper'],
  id: string,
  label: string,
) {
  await wrapper.get(`[data-testid="${id}"]`).trigger('click')
  await flushPromises()
  const option = Array.from(
    document.querySelectorAll<HTMLElement>('.el-select-dropdown__item'),
  ).find((item) => item.textContent?.trim() === label)
  expect(option).toBeDefined()
  option!.click()
  await flushPromises()
}
it('管理人员用固定策略新增规则，可编辑并保留失败草稿', async () => {
  signedIn()
  let saved = rule
  let attempt = 0
  const read = readBackend(['platform:data-security:read', 'platform:data-security:manage'])
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    if (init?.method === 'POST' || init?.method === 'PUT') {
      attempt++
      if (attempt === 1)
        return Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'field-masking.invalidRule',
              userTip: '规则配置不合法',
              httpStatus: 400,
              traceId: 'save-rule',
            }),
            { status: 400 },
          ),
        )
      const body = JSON.parse(init.body as string)
      saved = { ...saved, ...body }
      return Promise.resolve(ok(saved))
    }
    return read(url)
  })
  const { wrapper } = await mountConsole('/data-security')
  await wrapper.get('[data-testid="rule-create"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="rule-code"]').setValue('first_two')
  await wrapper.get('input[data-testid="rule-name"]').setValue('保留前两位')
  await choose(wrapper, 'rule-strategy', '保留前几位')
  await wrapper.get('[data-testid="rule-keep-first"] input').setValue('2')
  await wrapper.get('[data-testid="rule-save"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('规则配置不合法')
  expect((wrapper.get('input[data-testid="rule-name"]').element as HTMLInputElement).value).toBe(
    '保留前两位',
  )
  await wrapper.get('[data-testid="rule-save"]').trigger('click')
  await flushPromises()
  expect(saved).toMatchObject({
    code: 'first_two',
    name: '保留前两位',
    rule: { strategy: 'keepFirst', keepFirst: 2, replacement: null },
  })
  expect(wrapper.text()).toContain('保留前两位')
  await wrapper.findAll('[data-testid="rule-edit"]').at(-1)!.trigger('click')
  await flushPromises()
  expect(wrapper.get('input[data-testid="rule-code"]').attributes('disabled')).toBeDefined()
  await wrapper.get('input[data-testid="rule-name"]').setValue('客户简称')
  await wrapper.get('[data-testid="rule-save"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('客户简称')
})

it('管理员只修改分级默认规则，L4导出限制保持可见', async () => {
  signedIn()
  const email = { ...rule, code: 'email', name: '邮箱脱敏' }
  const read = fakeFetch({
    '/iam/platform/access': [
      grants('platform:data-security:read', 'platform:data-security:manage'),
    ],
    '/iam/platform/data-security/rules': [[rule, email]],
    '/iam/platform/data-security/grades': [grades],
  })
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      expect(url).toContain('/iam/platform/data-security/grades/L4')
      expect(JSON.parse(init.body as string)).toEqual({ defaultRule: 'email' })
      return Promise.resolve(ok({ ...grades[3], defaultRule: 'email' }))
    }
    return read(url)
  })
  const { wrapper } = await mountConsole('/data-security')
  await wrapper.get('#tab-grades').trigger('click')
  await flushPromises()
  await wrapper.findAll('[data-testid="grade-edit"]')[3]!.trigger('click')
  await flushPromises()
  await choose(wrapper, 'grade-rule', '邮箱脱敏（email）')
  expect(wrapper.find('[role="switch"]').exists()).toBe(false)
  await wrapper.get('[data-testid="grade-save"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('邮箱脱敏')
  expect(wrapper.text()).toContain('禁止导出')
})

it('删除前说明影响，被引用规则拒绝删除后保留记录并可重试', async () => {
  signedIn()
  const read = readBackend(['platform:data-security:read', 'platform:data-security:manage'])
  let attempts = 0
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') {
      attempts++
      return Promise.resolve(
        attempts === 1
          ? new Response(
              JSON.stringify({
                code: 'field-masking.ruleInUse',
                httpStatus: 409,
                userTip: '规则已被字段或分级引用',
                traceId: 'in-use',
              }),
              { status: 409 },
            )
          : ok(null),
      )
    }
    return read(url)
  })
  const { wrapper } = await mountConsole('/data-security')
  await wrapper.get('[data-testid="rule-delete"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('删除后所有租户将无法再选择此规则')
  expect(attempts).toBe(0)
  await wrapper.get('[data-testid="rule-delete-confirm"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('规则已被字段或分级引用')
  expect(wrapper.text()).toContain('手机号脱敏')
  await wrapper.get('[data-testid="rule-delete-confirm"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('暂无脱敏规则')
})

it('只读人员预览规则，业务错误显示中文且不回显原文', async () => {
  signedIn()
  let attempts = 0
  const read = readBackend()
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    if (url.endsWith('/preview')) {
      expect(JSON.parse(init?.body as string)).toEqual({ ruleCode: 'phone', value: '13812345678' })
      attempts++
      return Promise.resolve(
        ok(
          attempts === 1
            ? {
                success: false,
                value: '13812345678',
                errorCode: 'field-masking.invalidRule',
                errorMessage: 'Invalid input 13812345678',
              }
            : { success: true, value: '138****5678', errorCode: null, errorMessage: null },
        ),
      )
    }
    return read(url)
  })
  const { wrapper } = await mountConsole('/data-security')
  await wrapper.get('[data-testid="rule-preview"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="preview-value"]').setValue('13812345678')
  await wrapper.get('[data-testid="preview-run"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="preview-error"]').text()).toContain('规则无效，请选择其他规则')
  expect(wrapper.get('[data-testid="preview-error"]').text()).not.toContain('13812345678')
  await wrapper.get('[data-testid="preview-run"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="preview-result"]').text()).toBe('138****5678')
})

it('加载失败可重试，成功空列表与失败区分', async () => {
  signedIn()
  let attempts = 0
  const read = readBackend()
  vi.stubGlobal('fetch', (url: string) => {
    if (url.endsWith('/data-security/rules')) {
      attempts++
      return Promise.resolve(
        attempts === 1
          ? new Response(
              JSON.stringify({
                code: 'unavailable',
                userTip: '规则暂不可用',
                httpStatus: 503,
                traceId: 'retry',
              }),
              { status: 503 },
            )
          : ok([]),
      )
    }
    return read(url)
  })
  const { wrapper } = await mountConsole('/data-security')
  expect(wrapper.text()).toContain('规则暂不可用')
  expect(wrapper.text()).not.toContain('暂无脱敏规则')
  await wrapper.get('[data-testid="data-security-reload"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('暂无脱敏规则')
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
})
it('没有read权限时直接进入数据安全返回403', async () => {
  signedIn()
  vi.stubGlobal('fetch', readBackend(['platform:data-security:manage']))
  const { wrapper, router } = await mountConsole('/data-security')
  expect(router.currentRoute.value.path).toBe('/403')
  expect(wrapper.find('.shell-menu').exists()).toBe(false)
})
