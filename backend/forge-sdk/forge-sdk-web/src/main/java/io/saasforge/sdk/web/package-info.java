/**
 * Servlet 服务的响应信封支持（ADR-0015）：引入本模块后，Controller 返回 {@link io.saasforge.sdk.core.api.ApiResponse}
 * 即得到带 traceId 的统一信封，{@link io.saasforge.sdk.core.api.ForgeException} 与框架异常映射为失败信封，{@link
 * io.saasforge.sdk.web.PageQuery} 参数按统一约定解析分页与排序。
 */
package io.saasforge.sdk.web;
