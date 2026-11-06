import { expect, it, vi } from 'vitest'
import { MfaLoginForm } from './MfaLoginForm'
import { RecoveryCodeList } from './RecoveryCodeList'
import { mountWithShell } from './test-mount'

it('恢复码列表可复制并在确认后发出 done', async () => {
  const writeText = vi.fn(() => Promise.resolve())
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  const wrapper = await mountWithShell(RecoveryCodeList, {
    props: { codes: ['AAAA-BBBB', 'CCCC-DDDD'] },
  })

  expect(wrapper.get('[data-testid="recovery-codes"]').text()).toContain('CCCC-DDDD')
  await wrapper.get('[data-testid="recovery-codes-copy"]').trigger('click')
  expect(writeText).toHaveBeenCalledWith('AAAA-BBBB\nCCCC-DDDD')
  await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
  expect(wrapper.emitted('done')).toHaveLength(1)
})

it('MFA 表单默认提交动态码，可切换为恢复码', async () => {
  const wrapper = await mountWithShell(MfaLoginForm, { props: { busy: false } })

  await wrapper.get('input[name="mfa-code"]').setValue(' 123456 ')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.emitted('submit')?.[0]).toEqual([{ code: '123456' }])

  await wrapper.get('[data-testid="use-recovery-code"]').trigger('click')
  await wrapper.get('input[name="recovery-code"]').setValue('AAAA-BBBB-CCCC-DDDD')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.emitted('submit')?.[1]).toEqual([{ recoveryCode: 'AAAA-BBBB-CCCC-DDDD' }])
})
