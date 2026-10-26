package io.saasforge.sdk.web;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.HandlerMethod;

/**
 * springdoc 不认识由 {@link PageQueryArgumentResolver} 解析的 {@link PageQuery}，会把它当作一个名为 {@code query} 的对象参数；
 * 这里改为文档化实际的 {@code page}、{@code size}、{@code sort} query 参数，并列出可排序字段。
 */
class PageQueryOpenApiCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (PageQuery.class.equals(parameter.getParameterType())) {
                addPagingParameters(operation, parameter);
            }
        }
        return operation;
    }

    private static void addPagingParameters(Operation operation, MethodParameter parameter) {
        List<Parameter> parameters =
                operation.getParameters() == null ? new ArrayList<>() : new ArrayList<>(operation.getParameters());
        parameters.add(new QueryParameter()
                .name("page")
                .description("页码，从 1 开始")
                .schema(new IntegerSchema()._default(1).minimum(BigDecimal.ONE)));
        parameters.add(new QueryParameter()
                .name("size")
                .description("每页条数，超过上限返回 400")
                .schema(new IntegerSchema()
                        ._default(PageQuery.DEFAULT_SIZE)
                        .minimum(BigDecimal.ONE)
                        .maximum(BigDecimal.valueOf(PageQuery.MAX_SIZE))));
        SortableFields sortable = parameter.getParameterAnnotation(SortableFields.class);
        String fields = sortable == null ? "无" : String.join("、", sortable.value());
        parameters.add(new QueryParameter()
                .name("sort")
                .description("排序，格式 字段,asc|desc，可重复；可排序字段：" + fields)
                .schema(new ArraySchema().items(new StringSchema())));
        operation.setParameters(parameters);
    }
}
