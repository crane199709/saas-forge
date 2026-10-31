import { startDevHost } from '@crane199709/sub-app-kit/dev-host'
import { routes } from './sub-app'

// 仅用于 pnpm dev：无需启动 Shell App 工程即可运行与调试，修改代码后经 HMR 更新。
await startDevHost({ appCode: 'demo', routes, home: 'overview' })
