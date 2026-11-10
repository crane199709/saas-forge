import { useI18n } from 'vue-i18n'

// 组件文案随组件打包，经 local scope 使用 Shell 已安装的 i18n 的当前语言，不另建全局实例。
const messages = {
  'zh-CN': {
    io: {
      import: '导入',
      export: '导出',
      template: '下载模板',
      file: '选择 Excel 文件',
      recent: '最近任务',
      history: '导出记录',
      empty: '暂无任务',
      download: '下载结果',
      errorReport: '下载错误报告',
      uploading: '正在上传',
      failed: '处理失败，请稍后重试',
      xlsxOnly: '仅支持 .xlsx 文件',
      confirm: '确认写入',
      expired: '确认已过期，请重新上传',
      prospectiveRows: '将写入 {count} 行',
      AWAITING_CONFIRM: '待确认',
      WAITING: '等待中',
      RUNNING: '执行中',
      SUCCEEDED: '成功',
      FAILED: '失败',
      IMPORT: '导入',
      EXPORT: '导出',
      rows: '{count} 行',
    },
    navigationEditor: {
      addGroup: '新建分组',
      renameTitle: '重命名分组',
      groupName: '分组名称',
      rename: '重命名',
      remove: '删除',
      reset: '恢复默认',
      save: '保存',
      cancel: '取消',
      confirm: '确定',
    },
    permissionTree: {
      menus: '菜单与操作',
      others: '功能权限',
      ungrouped: '未分组',
      alwaysVisible: '始终可见',
      shared: '与「{menus}」共用权限，会一起显示',
    },
    traceId: '追踪 ID',
    copyTraceId: '复制',
    total: '共 {total} 条',
    resource: {
      view: '记录视图',
      active: '未删除',
      deleted: '已删除',
      restore: '恢复',
    },
    mfa: {
      title: '两步验证',
      codeHint: '账号已开启两步验证，每次登录都需输入验证器应用上的 6 位动态码。',
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
