# 存储用途实现地图

## Notes

规格见 [spec.md](spec.md)。验收边界为 StoragePurpose 声明、ObjectStorage 公共 API、IO 与附件业务流程；后端使用真实集成测试，前端拒绝提示由浏览器验证。

## Decisions-so-far

- 01 已完成：类型化用途、统一文件校验、持久化、下载用途及期限、存量回填方案；3 项 SDK 测试与 29 项对象存储集成测试通过。兼容入口仅为中间版本保留，02 必须移除；服务正式 V14 接入后移除临时测试 V14、保留旧格式 V13.1 夹具验收真实回填。详见 [01 工单 Answer](issues/01-sdk-purpose.md#answer)。

- 02 已完成：IO/附件调用方使用明确用途，异步导入前核对用途以保留错误来源对象；删除全部无用途兼容入口和全局下载 TTL，正式 V14 以旧格式夹具验证回填，脚手架保持按需接入。定向 demo 40 项、SDK 4 项及 Spotless 通过；新增 4 个浏览器拒绝场景交由最终集成验收。详见 [02 工单 Answer](issues/02-migrate-callers.md#answer)。

- 最终验收完成：后端 99 项、Playwright 4 项、脚手架生成、前端构建/类型/格式和 Spotless 均通过；Standards/Spec 两轴均无发现。见 [验收记录](acceptance.md)。

## Fog

- 无未决事项。历史 unspecified 对象仍需按可信业务关联显式归类，未知用途保持拒绝。
