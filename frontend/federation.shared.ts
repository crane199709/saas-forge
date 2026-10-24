import tenantPackage from './apps/tenant-app/package.json' with { type: 'json' }
import sdkPackage from './packages/sdk/package.json' with { type: 'json' }
import uiPackage from './packages/ui/package.json' with { type: 'json' }

// 两端按同一组精确版本验收；Sub-App 只消费 Shell 提供的实例，避免回退为第二份依赖。
export function sharedDependencies(host: boolean) {
  const versions = {
    vue: tenantPackage.dependencies.vue,
    'vue-router': tenantPackage.dependencies['vue-router'],
    pinia: tenantPackage.dependencies.pinia,
    'element-plus': tenantPackage.dependencies['element-plus'],
    'vue-i18n': tenantPackage.dependencies['vue-i18n'],
    '@forge/ui': uiPackage.version,
    '@forge/sdk': sdkPackage.version,
  }
  return Object.fromEntries(
    Object.entries(versions).map(([name, version]) => [
      name,
      {
        singleton: true,
        requiredVersion: version,
        version,
        ...(host ? {} : { import: false as const }),
      },
    ]),
  )
}
