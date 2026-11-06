import { ElAlert, ElButton } from 'element-plus'
import { defineComponent, h, ref, type PropType } from 'vue'
import { useUiI18n } from './i18n'

/**
 * 展示只给一次的恢复码，提供复制与下载。服务端只存哈希，这组恢复码离开此页后再也取不回，因此不做任何缓存；
 * 用户确认已保存后发出 `done`。
 */
export const RecoveryCodeList = defineComponent({
  name: 'RecoveryCodeList',
  props: {
    /** 要展示的恢复码。 */
    codes: { type: Array as PropType<string[]>, required: true },
  },
  emits: {
    /** 用户确认已保存。 */
    done: () => true,
  },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    const copied = ref(false)

    async function copy() {
      await navigator.clipboard.writeText(props.codes.join('\n'))
      copied.value = true
    }

    function download() {
      const url = URL.createObjectURL(
        new Blob([props.codes.join('\n') + '\n'], { type: 'text/plain;charset=utf-8' }),
      )
      const link = document.createElement('a')
      link.href = url
      link.download = t('recoveryCodes.fileName')
      link.click()
      URL.revokeObjectURL(url)
    }

    return () =>
      h('section', { 'aria-label': t('recoveryCodes.title') }, [
        h('h3', t('recoveryCodes.title')),
        h(ElAlert, { type: 'warning', closable: false, title: t('recoveryCodes.hint') }),
        h(
          'ol',
          {
            'data-testid': 'recovery-codes',
            style: {
              display: 'grid',
              gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
              gap: '8px',
              paddingLeft: '24px',
            },
          },
          props.codes.map((code) => h('li', { key: code }, h('code', code))),
        ),
        h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: '8px' } }, [
          h(ElButton, { 'data-testid': 'recovery-codes-copy', onClick: copy }, () =>
            copied.value ? t('recoveryCodes.copied') : t('recoveryCodes.copy'),
          ),
          h(ElButton, { 'data-testid': 'recovery-codes-download', onClick: download }, () =>
            t('recoveryCodes.download'),
          ),
          h(
            ElButton,
            { type: 'primary', 'data-testid': 'recovery-codes-done', onClick: () => emit('done') },
            () => t('recoveryCodes.done'),
          ),
        ]),
      ])
  },
})
