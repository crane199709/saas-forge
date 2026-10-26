package io.saasforge.sdk.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 从 query 的 {@code page}、{@code size}、{@code sort} 解析并校验 {@link PageQuery}，非法值返回 400 而不是静默修正。 */
class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public PageQuery resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        List<InvalidPageQueryException.Violation> violations = new ArrayList<>();
        int page = parseInt(request.getParameter("page"), 1, 1, Integer.MAX_VALUE);
        if (page < 0) {
            violations.add(new InvalidPageQueryException.Violation("page", "validation.page"));
        }
        int size = parseInt(request.getParameter("size"), PageQuery.DEFAULT_SIZE, 1, PageQuery.MAX_SIZE);
        if (size < 0) {
            violations.add(new InvalidPageQueryException.Violation("size", "validation.size"));
        }
        List<PageQuery.Sort> sorts = parseSorts(request.getParameterValues("sort"), allowedFields(parameter));
        if (sorts == null) {
            violations.add(new InvalidPageQueryException.Violation("sort", "validation.sort"));
        }
        if (!violations.isEmpty()) {
            throw new InvalidPageQueryException(violations);
        }
        return new PageQuery(page, size, sorts);
    }

    /** 返回 -1 表示非法；未传时取默认值。 */
    private static int parseInt(String raw, int defaultValue, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < min || value > max ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 返回 null 表示存在非法排序条件。 */
    private static List<PageQuery.Sort> parseSorts(String[] values, Set<String> allowed) {
        List<PageQuery.Sort> sorts = new ArrayList<>();
        if (values == null) {
            return sorts;
        }
        for (String value : values) {
            String[] parts = value.split(",", -1);
            String field = parts[0].trim();
            if (parts.length > 2 || !allowed.contains(field)) {
                return null;
            }
            PageQuery.Direction direction = PageQuery.Direction.ASC;
            if (parts.length == 2) {
                String raw = parts[1].trim().toLowerCase(Locale.ROOT);
                if (raw.equals("desc")) {
                    direction = PageQuery.Direction.DESC;
                } else if (!raw.equals("asc")) {
                    return null;
                }
            }
            sorts.add(new PageQuery.Sort(field, direction));
        }
        return sorts;
    }

    private static Set<String> allowedFields(MethodParameter parameter) {
        SortableFields sortable = parameter.getParameterAnnotation(SortableFields.class);
        return sortable == null ? Set.of() : Set.of(sortable.value());
    }
}
