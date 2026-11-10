import { defineComponent, h, type PropType } from 'vue'
import type { PageMeta } from '@crane199709/sdk'
import { ElButton, ElPagination } from 'element-plus'
import type { IoTask } from './io-tasks'
import { useUiI18n } from './i18n'
export const IoTaskList = defineComponent({
  name: 'IoTaskList',
  props: {
    /** 当前服务的最近任务。 */
    tasks: { type: Array as PropType<IoTask[]>, required: true },
    /** 服务端分页元数据，保留全部方向的任务页。 */
    page: Object as PropType<PageMeta>,
    /** 当前页正在加载。 */
    loading: Boolean,
    /** 当前会话的 Membership，用于隐藏他人任务的确认入口。 */
    membershipId: String,
    /** 调用方提供确认接口时才允许确认。 */
    allowConfirm: Boolean,
    /** 正在执行其他提交或确认。 */
    busy: Boolean,
  },
  emits: {
    download: (_id: string) => true,
    confirm: (_id: string) => true,
    'page-change': (_number: number) => true,
  },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    return () =>
      h('section', { 'aria-label': t('io.recent') }, [
        h('h4', t('io.recent')),
        props.tasks.length
          ? h(
              'ul',
              {
                style: {
                  listStyle: 'none',
                  margin: '0',
                  padding: '0',
                  display: 'grid',
                  gap: 'var(--forge-space-4)',
                },
              },
              props.tasks.map((task) => {
                const expired =
                  task.errorCode === 'io.confirmationExpired' ||
                  (task.status === 'AWAITING_CONFIRM' &&
                    !!task.heartbeatAt &&
                    Date.parse(task.heartbeatAt) + 24 * 60 * 60 * 1000 <= Date.now())
                return h('li', { key: task.id }, [
                  h(
                    'div',
                    {
                      style: {
                        display: 'flex',
                        flexWrap: 'wrap',
                        alignItems: 'center',
                        gap: 'var(--forge-space-3)',
                      },
                    },
                    [
                      h(
                        'span',
                        `${t('io.' + task.type)} · ${t(expired ? 'io.expired' : 'io.' + task.status)} · ${t(task.status === 'AWAITING_CONFIRM' && !expired ? 'io.prospectiveRows' : 'io.rows', { count: task.rowCount })}`,
                      ),
                      !expired &&
                      !!task.heartbeatAt &&
                      Number.isFinite(Date.parse(task.heartbeatAt)) &&
                      props.allowConfirm &&
                      task.type === 'IMPORT' &&
                      task.status === 'AWAITING_CONFIRM' &&
                      task.membershipId === props.membershipId
                        ? h(
                            ElButton,
                            {
                              link: true,
                              type: 'primary',
                              disabled: props.busy,
                              onClick: () => emit('confirm', task.id),
                            },
                            () => t('io.confirm'),
                          )
                        : null,
                      task.finishedAt && task.resultAvailable !== false
                        ? h(
                            ElButton,
                            { link: true, onClick: () => emit('download', task.id) },
                            () => t(task.type === 'IMPORT' ? 'io.errorReport' : 'io.download'),
                          )
                        : null,
                    ],
                  ),
                  !expired && task.failureReason
                    ? h(
                        'p',
                        {
                          role: 'status',
                          style: { margin: 'var(--forge-space-2) 0 0', overflowWrap: 'anywhere' },
                        },
                        task.failureReason,
                      )
                    : null,
                ])
              }),
            )
          : h('p', t('io.empty')),
        props.page && props.page.totalPages > 1
          ? h(
              'footer',
              {
                style: {
                  display: 'flex',
                  justifyContent: 'flex-end',
                  marginTop: 'var(--forge-space-4)',
                },
              },
              [
                h(ElPagination, {
                  currentPage: props.page.number,
                  pageSize: props.page.size,
                  total: props.page.totalElements,
                  disabled: props.loading,
                  layout: 'prev, pager, next',
                  'onUpdate:currentPage': (number: number) => emit('page-change', number),
                }),
              ],
            )
          : null,
      ])
  },
})
