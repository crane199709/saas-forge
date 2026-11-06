import { useI18n } from 'vue-i18n'

// 组件文案随组件打包，经 local scope 使用 Shell 已安装的 i18n 的当前语言，不另建全局实例。
const messages = {
  'zh-CN': {
    traceId: '追踪 ID',
    copyTraceId: '复制',
    total: '共 {total} 条',
    mfa: {
      title: '两步验证',
      codeHint: '请输入验证器应用上的 6 位动态码。',
      code: '动态码',
      recoveryCode: '恢复码',
      recoveryHint: '无法使用验证器时，可输入一个未用过的恢复码，每个恢复码只能使用一次。',
      useRecoveryCode: '改用恢复码',
      useCode: '改用动态码',
      verify: '验证',
      backToLogin: '返回登录',
    },
    recoveryCodes: {
      title: '恢复码',
      hint: '恢复码只显示这一次，请立即复制或下载并妥善保存。无法使用验证器时，可用其中任意一个完成验证，每个只能用一次。',
      copy: '复制',
      copied: '已复制',
      download: '下载',
      done: '我已保存，继续',
      fileName: 'saas-forge-recovery-codes.txt',
    },
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
