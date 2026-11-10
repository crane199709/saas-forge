import type { Page } from '@crane199709/sdk'
import {
  ElButton,
  ElButtonGroup,
  ElPagination,
  ElTable,
  ElTableColumn,
  vLoading,
} from 'element-plus'
import { defineComponent, h, withDirectives, type SlotsType, type VNode } from 'vue'
import { useUiI18n } from './i18n'

/** ListPage 的属性。 */
export interface ListPageProps<T> {
  /** `@crane199709/sdk` 分页接口的结果；首次加载完成前为 `null`。 */
  page: Page<T> | null
  /** 加载中：表格显示加载遮罩，分页不可操作。 */
  loading?: boolean
  /** 没有数据时表格中的提示。 */
  emptyText?: string
  /** 受控的已删除视图；不传时隐藏视图切换并保持原有列表行为。 */
  deleted?: boolean
}

/**
 * 列表页骨架：`search` 插槽放搜索与操作区，默认插槽放 `el-table-column`，底部展示总条数与翻页。
 * 页码从 1 开始，与 `meta.page.number` 一致；翻页时触发 `page-change`，由使用方按新页码重新请求。
 * 传入 `deleted` 后显示视图切换；`deleted-change(boolean)` 由使用方更新视图、重置页码并请求数据。
 * 已删除视图追加恢复列，触发 `restore(row)`；使用方负责权限、确认、请求及结果提示。
 */
export const ListPage = defineComponent(
  <T>(
    props: ListPageProps<T>,
    {
      slots,
      emit,
    }: {
      slots: { search?: () => VNode[]; default?: () => VNode[] }
      emit: {
        (event: 'page-change', number: number): void
        (event: 'deleted-change', deleted: boolean): void
        (event: 'restore', row: T): void
      }
    },
  ) => {
    const { t } = useUiI18n()
    return () => {
      const meta = props.page?.page
      return h('section', { class: 'list-page', 'aria-busy': props.loading ?? false }, [
        props.deleted !== undefined || slots.search
          ? h(
              'div',
              {
                class: 'list-page-toolbar',
                style: {
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 'var(--forge-space-2)',
                  marginBottom: 'var(--forge-space-3)',
                },
              },
              [
                props.deleted !== undefined
                  ? h(ElButtonGroup, { role: 'group', 'aria-label': t('resource.view') }, () =>
                      [false, true].map((deleted) =>
                        h(
                          ElButton,
                          {
                            plain: true,
                            'aria-pressed': props.deleted === deleted,
                            type: props.deleted === deleted ? 'primary' : 'default',
                            disabled: props.loading ?? false,
                            onClick: () => {
                              if (!props.loading && props.deleted !== deleted)
                                emit('deleted-change', deleted)
                            },
                          },
                          () => t(deleted ? 'resource.deleted' : 'resource.active'),
                        ),
                      ),
                    )
                  : null,
                slots.search ? h('div', { class: 'list-page-search' }, slots.search()) : null,
              ],
            )
          : null,
        withDirectives(
          h(
            ElTable,
            // el-table 的行类型是任意对象，条目类型只约束使用方传入的分页结果。
            { data: (props.page?.items ?? []) as object[], emptyText: props.emptyText },
            {
              default: () => [
                ...(slots.default?.() ?? []),
                props.deleted
                  ? h(
                      ElTableColumn,
                      { label: t('resource.restore'), width: 100, fixed: 'right' },
                      {
                        default: ({ row }: { row: T }) =>
                          h(
                            ElButton,
                            {
                              link: true,
                              type: 'primary',
                              disabled: props.loading ?? false,
                              onClick: () => {
                                if (!props.loading) emit('restore', row)
                              },
                            },
                            () => t('resource.restore'),
                          ),
                      },
                    )
                  : null,
              ],
            },
          ),
          [[vLoading, props.loading ?? false]],
        ),
        meta
          ? h(
              'footer',
              {
                class: 'list-page-footer',
                style: {
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'flex-end',
                  gap: 'var(--forge-space-4)',
                  marginTop: 'var(--forge-space-4)',
                },
              },
              [
                h(
                  'span',
                  { 'data-testid': 'list-page-total' },
                  t('total', { total: meta.totalElements }),
                ),
                h(ElPagination, {
                  currentPage: meta.number,
                  pageSize: meta.size,
                  total: meta.totalElements,
                  disabled: props.loading ?? false,
                  hideOnSinglePage: true,
                  layout: 'prev, pager, next',
                  'onUpdate:currentPage': (number: number) => emit('page-change', number),
                }),
              ],
            )
          : null,
      ])
    }
  },
  {
    name: 'ListPage',
    props: ['page', 'loading', 'emptyText', 'deleted'],
    emits: ['page-change', 'deleted-change', 'restore'],
    slots: Object as SlotsType<{ search?: () => VNode[]; default?: () => VNode[] }>,
  },
)
