import type { Page } from '@forge/sdk'
import { ElPagination, ElTable, vLoading } from 'element-plus'
import { defineComponent, h, withDirectives, type SlotsType, type VNode } from 'vue'
import { useUiI18n } from './i18n'

/** ListPage 的属性。 */
export interface ListPageProps<T> {
  /** `@forge/sdk` 分页接口的结果；首次加载完成前为 `null`。 */
  page: Page<T> | null
  /** 加载中：表格显示加载遮罩，分页不可操作。 */
  loading?: boolean
  /** 没有数据时表格中的提示。 */
  emptyText?: string
}

/**
 * 列表页骨架：`search` 插槽放搜索与操作区，默认插槽放 `el-table-column`，底部展示总条数与翻页。
 * 页码从 1 开始，与 `meta.page.number` 一致；翻页时触发 `page-change`，由使用方按新页码重新请求。
 */
export const ListPage = defineComponent(
  <T>(
    props: ListPageProps<T>,
    {
      slots,
      emit,
    }: {
      slots: { search?: () => VNode[]; default?: () => VNode[] }
      emit: (event: 'page-change', number: number) => void
    },
  ) => {
    const { t } = useUiI18n()
    return () => {
      const meta = props.page?.page
      return h('section', { class: 'list-page', 'aria-busy': props.loading ?? false }, [
        slots.search
          ? h('div', { class: 'list-page-search', style: { marginBottom: '16px' } }, slots.search())
          : null,
        withDirectives(
          h(
            ElTable,
            // el-table 的行类型是任意对象，条目类型只约束使用方传入的分页结果。
            { data: (props.page?.items ?? []) as object[], emptyText: props.emptyText },
            slots.default,
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
                  gap: '16px',
                  marginTop: '16px',
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
    props: ['page', 'loading', 'emptyText'],
    emits: ['page-change'],
    slots: Object as SlotsType<{ search?: () => VNode[]; default?: () => VNode[] }>,
  },
)
