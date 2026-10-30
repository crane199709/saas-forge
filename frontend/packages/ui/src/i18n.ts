import { useI18n } from 'vue-i18n'

// 组件文案随组件打包，经 local scope 使用 Shell 已安装的 i18n 的当前语言，不另建全局实例。
const messages = {
  'zh-CN': {
    traceId: '追踪 ID',
    copyTraceId: '复制',
    total: '共 {total} 条',
    result: {
      '403': { title: '403', subTitle: '没有访问该页面的权限' },
      '404': { title: '404', subTitle: '页面不存在' },
      'no-tenant': {
        title: '暂无可进入的租户',
        subTitle: '此身份目前没有有效的成员关系，请联系租户管理员邀请加入。',
      },
      'no-permission': {
        title: '暂无可用权限',
        subTitle: '当前账号没有任何权限，请联系管理员分配角色',
      },
    },
  },
}

export function useUiI18n() {
  return useI18n({ useScope: 'local', messages })
}
