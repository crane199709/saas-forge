# Remote Delivery Context

Remote Delivery 拥有业务 Remote 的注册、审核和启用事实，决定哪些受控业务模块可被 Tenant Shell 发现。

## Language

**Remote Manifest**:
由 CI 注册、标识一个业务模块版本及其受控来源的不可变声明；来源可访问并不代表声明已获审核或启用。
_Avoid_: Service registration, arbitrary script URL

**Manifest Review**:
Platform Administrator 对一个 Remote Manifest 作出的批准或拒绝决定。
_Avoid_: Automatic approval, CI approval

**Manifest Enablement**:
Platform Administrator 将已批准 Remote Manifest 纳入 Shell 可发现集合的决定。
_Avoid_: Source availability, registration success
