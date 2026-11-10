import type { CatalogMenu, CatalogPermissionTree } from './plan-api'

const permission = (code: string, name: string, sharedWith: string[] = []) => ({
  code,
  name,
  sharedWith,
})
const page = (
  code: string,
  name: string,
  app: string,
  read: ReturnType<typeof permission> | null,
  actions: ReturnType<typeof permission>[] = [],
): CatalogMenu => ({ code, name, icon: null, app, permission: read, actions, children: [] })
const group = (code: string, name: string, children: CatalogMenu[]): CatalogMenu => ({
  code,
  name,
  icon: null,
  app: null,
  permission: null,
  actions: [],
  children,
})

/** 测试用目录：demo 的清单，以及与 demo 共用操作、带两层分组的 crm。 */
export const catalogTree: CatalogPermissionTree = {
  menus: [
    group('demo', '示例应用', [
      page(
        'demo.overview',
        '订单概览',
        'demo',
        permission('order:read', '查看订单', ['demo.attachments']),
        [
          permission('order:create', '创建订单'),
          permission('order:update', '修改订单'),
          permission('order:delete', '删除订单'),
          permission('order:import', '导入订单'),
          permission('order:export', '导出订单', ['crm.customers']),
        ],
      ),
      page(
        'demo.attachments',
        '订单附件',
        'demo',
        permission('order:read', '查看订单', ['demo.overview']),
      ),
    ]),
    group('crm', '客户', [
      group('crm.nested', '客户档案', [
        page('crm.customers', '客户列表', 'crm', permission('customer:read', '查看客户'), [
          permission('order:export', '导出订单', ['demo.overview']),
        ]),
      ]),
      page('crm.help', '帮助', 'crm', null),
    ]),
  ],
  others: [{ code: 'report:sync', name: '同步报表', service: 'report-service', group: '报表' }],
}
