import type { NavigationLayout } from '@crane199709/sdk'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElTree,
  type AllowDropFunction,
} from 'element-plus'
import { defineComponent, h, ref, watch, type PropType } from 'vue'
import { useUiI18n } from './i18n'
import type { NavigationEntry } from './navigation'
import {
  addGroup,
  canDrop,
  editorTree,
  removeGroup,
  toLayout,
  type EditorNode,
  type EditorTree,
} from './navigation-editor'
import { PageToolbar } from './PageToolbar'
import { overlaySize } from './theme'

// 图标取自 Element Plus Icons（MIT）的 Folder 与 Document，本包不依赖图标库。
const folderPath =
  'M128 192v640h768V320H485.76L357.504 192H128zm-32-64h287.872l128.384 128H928a32 32 0 0 1 32 32v576a32 32 0 0 1-32 32H96a32 32 0 0 1-32-32V160a32 32 0 0 1 32-32z'
const documentPath =
  'M832 384H576V128H192v768h640V384zm-26.496-64L640 154.496V320h165.504zM160 64h480l256 256v608a32 32 0 0 1-32 32H160a32 32 0 0 1-32-32V96a32 32 0 0 1 32-32z'

const icon = (path: string) =>
  h(ElIcon, { style: { color: 'var(--el-text-color-secondary)' } }, () =>
    h('svg', { viewBox: '0 0 1024 1024', xmlns: 'http://www.w3.org/2000/svg' }, [
      h('path', { fill: 'currentColor', d: path }),
    ]),
  )

const maxNameLength = 32

/**
 * Navigation Layout 编辑器：工具条（新建分组、恢复默认、保存）与可拖拽的树。
 * 可排序、跨分组移动，新建、重命名、删除自定义分组；清单分组不能改名或删除；页面不接收子节点，最多三层。
 * 布局中目录外的引用不显示，保存时保留。恢复默认的确认与接口调用由页面负责。
 */
export const NavigationLayoutEditor = defineComponent({
  name: 'NavigationLayoutEditor',
  props: {
    /** 可排列的全部节点（管理页目录），按默认顺序；带 `children` 的是清单分组。 */
    catalog: { type: Array as PropType<NavigationEntry[]>, required: true },
    /** 已保存的布局；null 表示按目录顺序。变化时丢弃未保存的修改，按新布局显示。 */
    layout: { type: Object as PropType<NavigationLayout | null>, default: null },
    /** 保存或恢复默认进行中，期间不能再次提交。 */
    busy: Boolean,
  },
  emits: {
    /** 点击「保存」，参数为整棵布局。 */
    save: (layout: NavigationLayout) => !!layout,
    /** 点击「恢复默认」。 */
    reset: () => true,
  },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    const tree = ref<EditorTree>({ nodes: [], retained: [] })
    watch(
      () => [props.catalog, props.layout] as const,
      ([catalog, layout]) => {
        tree.value = editorTree(catalog, layout)
      },
      { immediate: true },
    )

    // 新建与重命名共用一个弹窗：editing 为 null 时新建。
    const dialogOpen = ref(false)
    const editing = ref<EditorNode | null>(null)
    const name = ref('')

    function openDialog(group: EditorNode | null) {
      editing.value = group
      name.value = group?.title ?? ''
      dialogOpen.value = true
    }

    function confirm() {
      const value = name.value.trim()
      if (!value) return
      if (editing.value) editing.value.title = value
      else addGroup(tree.value, value)
      dialogOpen.value = false
    }

    // 树的数据即 EditorNode，el-tree 的节点类型不带数据的具体类型。
    const allowDrop: AllowDropFunction = (dragging, target, type) =>
      canDrop(dragging.data as EditorNode, target.data as EditorNode, target.level, type)

    const row = ({ data }: { data: EditorNode }) =>
      h(
        'div',
        {
          'data-testid': `navigation-node-${data.id}`,
          style: {
            display: 'flex',
            flex: 1,
            alignItems: 'center',
            gap: 'var(--forge-space-2)',
            minWidth: 0,
            paddingRight: 'var(--forge-space-2)',
          },
        },
        [
          icon(data.children ? folderPath : documentPath),
          h(
            'span',
            {
              style: {
                flex: 1,
                minWidth: 0,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              },
            },
            data.title,
          ),
          ...(data.kind === 'group'
            ? [
                h(
                  ElButton,
                  {
                    link: true,
                    type: 'primary',
                    'data-testid': `navigation-rename-${data.ref}`,
                    onClick: (event: Event) => {
                      event.stopPropagation()
                      openDialog(data)
                    },
                  },
                  () => t('navigationEditor.rename'),
                ),
                h(
                  ElButton,
                  {
                    link: true,
                    type: 'danger',
                    'data-testid': `navigation-remove-${data.ref}`,
                    onClick: (event: Event) => {
                      event.stopPropagation()
                      removeGroup(tree.value, data)
                    },
                  },
                  () => t('navigationEditor.remove'),
                ),
              ]
            : []),
        ],
      )

    return () =>
      h('div', { class: 'navigation-layout-editor' }, [
        h(PageToolbar, null, {
          default: () =>
            h(
              ElButton,
              { 'data-testid': 'navigation-add-group', onClick: () => openDialog(null) },
              () => t('navigationEditor.addGroup'),
            ),
          actions: () => [
            h(
              ElButton,
              {
                plain: true,
                disabled: props.busy,
                'data-testid': 'navigation-reset',
                onClick: () => emit('reset'),
              },
              () => t('navigationEditor.reset'),
            ),
            h(
              ElButton,
              {
                type: 'primary',
                disabled: props.busy,
                'data-testid': 'navigation-save',
                onClick: () => emit('save', toLayout(tree.value)),
              },
              () => t('navigationEditor.save'),
            ),
          ],
        }),
        h(
          ElTree,
          {
            data: tree.value.nodes,
            nodeKey: 'id',
            props: { label: 'title', children: 'children' },
            draggable: true,
            defaultExpandAll: true,
            expandOnClickNode: false,
            allowDrop,
          },
          { default: row },
        ),
        h(
          ElDialog,
          {
            modelValue: dialogOpen.value,
            'onUpdate:modelValue': (value: boolean) => (dialogOpen.value = value),
            width: overlaySize.dialogSm,
            title: editing.value
              ? t('navigationEditor.renameTitle')
              : t('navigationEditor.addGroup'),
          },
          {
            default: () =>
              h(
                ElForm,
                {
                  labelPosition: 'top',
                  onSubmit: (event: Event) => (event.preventDefault(), confirm()),
                },
                () =>
                  h(ElFormItem, { label: t('navigationEditor.groupName') }, () =>
                    h(ElInput, {
                      modelValue: name.value,
                      'onUpdate:modelValue': (value: string) => (name.value = value),
                      maxlength: maxNameLength,
                      showWordLimit: true,
                      'data-testid': 'navigation-group-name',
                    }),
                  ),
              ),
            footer: () => [
              h(ElButton, { onClick: () => (dialogOpen.value = false) }, () =>
                t('navigationEditor.cancel'),
              ),
              h(
                ElButton,
                {
                  type: 'primary',
                  disabled: !name.value.trim(),
                  'data-testid': 'navigation-group-confirm',
                  onClick: confirm,
                },
                () => t('navigationEditor.confirm'),
              ),
            ],
          },
        ),
      ])
  },
})
