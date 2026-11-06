import type { MfaFactor } from '@crane199709/sdk'
import { ElButton, ElForm, ElFormItem, ElInput } from 'element-plus'
import { defineComponent, h, ref, withModifiers } from 'vue'
import { useUiI18n } from './i18n'

/** 登录第二步：动态码或恢复码二选一。提交与错误处理交给父组件，与 First Factor 走同一套进入逻辑。 */
export const MfaLoginForm = defineComponent({
  name: 'MfaLoginForm',
  props: {
    /** 提交进行中。 */
    busy: { type: Boolean, required: true },
  },
  emits: {
    /** 提交动态码或恢复码。 */
    submit: (_factor: MfaFactor) => true,
    /** 放弃，返回输入 First Factor。 */
    cancel: () => true,
  },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    const useRecovery = ref(false)
    const code = ref('')
    const recoveryCode = ref('')

    function submit() {
      emit(
        'submit',
        useRecovery.value
          ? { recoveryCode: recoveryCode.value.trim() }
          : { code: code.value.trim() },
      )
    }

    function field() {
      if (useRecovery.value) {
        return h(ElFormItem, { label: t('mfa.recoveryCode'), for: 'recovery-code' }, () =>
          h(ElInput, {
            id: 'recovery-code',
            modelValue: recoveryCode.value,
            'onUpdate:modelValue': (value: string) => (recoveryCode.value = value),
            name: 'recovery-code',
            autocomplete: 'off',
            required: true,
          }),
        )
      }
      return h(ElFormItem, { label: t('mfa.code'), for: 'mfa-code' }, () =>
        h(ElInput, {
          id: 'mfa-code',
          modelValue: code.value,
          'onUpdate:modelValue': (value: string) => (code.value = value),
          name: 'mfa-code',
          autocomplete: 'one-time-code',
          inputmode: 'numeric',
          maxlength: 6,
          required: true,
        }),
      )
    }

    return () =>
      h('section', { 'aria-label': t('mfa.title') }, [
        h('h3', t('mfa.title')),
        h('p', useRecovery.value ? t('mfa.recoveryHint') : t('mfa.codeHint')),
        h(ElForm, { labelPosition: 'top', onSubmit: withModifiers(submit, ['prevent']) }, () => [
          field(),
          h('div', { style: { display: 'flex', gap: '12px', alignItems: 'center' } }, [
            h(ElButton, { nativeType: 'submit', type: 'primary', loading: props.busy }, () =>
              t('mfa.verify'),
            ),
            h(
              ElButton,
              {
                link: true,
                'data-testid': 'use-recovery-code',
                onClick: () => (useRecovery.value = !useRecovery.value),
              },
              () => (useRecovery.value ? t('mfa.useCode') : t('mfa.useRecoveryCode')),
            ),
            h(ElButton, { link: true, onClick: () => emit('cancel') }, () => t('mfa.backToLogin')),
          ]),
        ]),
      ])
  },
})
