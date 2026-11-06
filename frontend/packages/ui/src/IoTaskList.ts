import { defineComponent, h, type PropType } from 'vue'
import { ElButton } from 'element-plus'
import type { IoTask } from './io-tasks'
import { useUiI18n } from './i18n'
export const IoTaskList = defineComponent({
  name: 'IoTaskList',
  props: { tasks: { type: Array as PropType<IoTask[]>, required: true } },
  emits: { download: (_id: string) => true },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    return () =>
      h('section', { 'aria-label': t('io.recent') }, [
        h('h4', t('io.recent')),
        props.tasks.length
          ? h(
              'ul',
              props.tasks.map((task) =>
                h('li', { key: task.id }, [
                  `${t('io.' + task.type)} · ${t('io.' + task.status)} · ${t('io.rows', { count: task.rowCount })}`,
                  task.failureReason
                    ? h('span', { role: 'status' }, ` — ${task.failureReason}`)
                    : null,
                  task.finishedAt && task.resultAvailable !== false
                    ? h(ElButton, { link: true, onClick: () => emit('download', task.id) }, () =>
                        t(task.type === 'IMPORT' ? 'io.errorReport' : 'io.download'),
                      )
                    : null,
                ]),
              ),
            )
          : h('p', t('io.empty')),
      ])
  },
})
