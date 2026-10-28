/**
 * ABAC 策略表达式树、SQL 编译器与内存求值器（ADR-0006）。
 *
 * <p>入口：{@link io.saasforge.sdk.abac.PolicyParser} 加载并校验 JSON 策略，
 * {@link io.saasforge.sdk.abac.PolicySqlCompiler} 编译为参数化谓词，{@link io.saasforge.sdk.abac.PolicyEvaluator} 校验单个对象，
 * {@link io.saasforge.sdk.abac.DataScope} 提供常用预设，{@link io.saasforge.sdk.abac.DataResourceDefinition} 由清单中的 Data Resource
 * 建立属性目录与列映射。本包不依赖 Spring 与数据库；数据权限拦截器在 {@code data} 包中。
 */
package io.saasforge.sdk.abac;
