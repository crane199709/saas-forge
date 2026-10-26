/**
 * ABAC 策略表达式树、SQL 编译器与内存求值器（ADR-0006）。
 *
 * <p>入口：{@link io.saasforge.sdk.abac.PolicyParser} 加载并校验 JSON 策略，
 * {@link io.saasforge.sdk.abac.PolicySqlCompiler} 编译为参数化谓词，{@link io.saasforge.sdk.abac.PolicyEvaluator} 校验单个对象，
 * {@link io.saasforge.sdk.abac.DataScope} 提供常用预设。本包不依赖 Spring 与数据库；数据权限拦截器在后续工单中实现。
 */
package io.saasforge.sdk.abac;
