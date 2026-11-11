# 04: 生成的后端工程不带 Maven Wrapper

**Status:** needs-triage

**现象：** 生成工程没有 `mvnw`，其 `AGENTS.md` 只能写「工程未带 Maven Wrapper 时，把下文的 `./mvnw` 换成本机的 `mvn`」，构建依赖本机 Maven 版本，与平台仓库的 `./mvnw` 习惯不一致。

**期望：** 模板带 Maven Wrapper（`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`，版本与平台仓库一致，脚本保留可执行权限），`AGENTS.md` 去掉换用说明。
