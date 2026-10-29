package io.saasforge.iam.role.model;

import java.util.List;
import java.util.Map;

/**
 * Data Policy 对指定成员在样例数据上的命中情况。
 *
 * @param context 求值时成员的主体属性与环境属性，键为全名，便于核对命中原因
 * @param matches 与样例数据逐条对应；按 SQL 三值逻辑，结果为未知时同样不命中
 */
public record PolicyPreview(Map<String, Object> context, List<Boolean> matches) {}
