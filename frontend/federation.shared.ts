import tenantPackage from './apps/tenant-app/package.json' with { type: 'json' }
import sdkPackage from './packages/sdk/package.json' with { type: 'json' }
import uiPackage from './packages/ui/package.json' with { type: 'json' }

/** Shell 提供的共享依赖版本，键为包名；Shell 据此判断 Sub-App 在 App Registry 中声明的最低版本是否满足。 */
export function sharedVersions(): Record<string, string> {
  return {
    vue: tenantPackage.dependencies.vue,
    'vue-router': tenantPackage.dependencies['vue-router'],
    pinia: tenantPackage.dependencies.pinia,
    'element-plus': tenantPackage.dependencies['element-plus'],
    'vue-i18n': tenantPackage.dependencies['vue-i18n'],
    '@crane199709/ui': uiPackage.version,
    '@crane199709/sdk': sdkPackage.version,
  }
}

// 两端按同一组精确版本验收；Sub-App 只消费 Shell 提供的实例，避免回退为第二份依赖。
export function sharedDependencies(host: boolean) {
  return Object.fromEntries(
    Object.entries(sharedVersions()).map(([name, version]) => [
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
