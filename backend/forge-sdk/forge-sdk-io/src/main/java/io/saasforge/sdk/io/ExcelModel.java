package io.saasforge.sdk.io;

import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.referencedata.DictionaryValue;
import io.saasforge.sdk.referencedata.ReferenceData;
import io.saasforge.sdk.referencedata.TenantDictionaryItem;
import io.saasforge.sdk.security.EffectiveFieldPolicy;
import io.saasforge.sdk.security.MaskingEngine;
import io.saasforge.sdk.security.MaskingRule;
import io.saasforge.sdk.security.SensitiveField;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

final class ExcelModel<T> {
    private final Class<T> type;
    private final Constructor<T> constructor;
    final List<Column> columns;

    ExcelModel(Class<T> type, ReferenceData reference) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Excel model must be a record");
        }
        this.type = type;
        var components = type.getRecordComponents();
        try {
            constructor = type.getDeclaredConstructor(
                    Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException(error);
        }
        var list = new ArrayList<Column>();
        for (int index = 0; index < components.length; index++) {
            var component = components[index];
            var annotation = component.getAnnotation(ExcelColumn.class);
            if (annotation == null) {
                throw new IllegalArgumentException("Every record component needs ExcelColumn: " + component.getName());
            }
            var dictionary = component.getAnnotation(DictionaryValue.class);
            list.add(new Column(
                    index,
                    component,
                    annotation,
                    dictionary == null ? List.of() : List.copyOf(reference.items(dictionary.value()))));
        }
        columns = list.stream()
                .sorted(Comparator.comparingInt(c -> c.annotation.order()))
                .toList();
        if (columns.isEmpty()
                || columns.size() > 16383
                || columns.stream().map(c -> c.annotation.order()).distinct().count() != columns.size()
                || columns.stream().map(c -> c.annotation.value()).distinct().count() != columns.size()) {
            throw new IllegalArgumentException("Excel columns must have unique names and order");
        }
    }

    void header(Sheet sheet, boolean required) {
        var header = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            header.createCell(i).setCellValue(column.header(required));
            sheet.setColumnWidth(i, Math.min(255, Math.max(1, column.annotation.width())) * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    List<MaskingRule> exportRules(Map<String, EffectiveFieldPolicy> snapshot) {
        var engine = new MaskingEngine();
        var rules = new ArrayList<MaskingRule>();
        for (var column : columns) {
            var annotation = column.component.getAnnotation(SensitiveField.class);
            String path = annotation == null ? column.component.getName() : annotation.path();
            var field = snapshot.get(path);
            if (annotation != null && field == null) {
                throw new ForgeException(IoErrorCode.MASKING_FAILED);
            }
            if (field != null) {
                if (!field.exportAllowed()
                        || "L4".equals(field.grade())
                        || (annotation != null && "L4".equals(annotation.grade()))) {
                    throw new ForgeException(IoErrorCode.MASKING_FAILED);
                }
                engine.validate(field.rule());
            }
            rules.add(field == null ? null : field.rule());
        }
        return Collections.unmodifiableList(rules);
    }

    void write(Row row, T value, List<MaskingRule> rules) {
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            try {
                Object raw = column.component.getAccessor().invoke(value);
                String text = raw == null ? "" : raw.toString();
                if (!column.items.isEmpty() && raw != null) {
                    String code = text;
                    text = column.items.stream()
                            .filter(item -> item.code().equals(code))
                            .map(TenantDictionaryItem::name)
                            .findFirst()
                            .orElse(text);
                }
                if (rules.get(i) != null) {
                    text = new MaskingEngine().apply(rules.get(i), text);
                }
                row.createCell(i).setCellValue(text);
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new ForgeException(IoErrorCode.MASKING_FAILED);
            }
        }
    }

    Class<T> type() {
        return type;
    }

    T parse(List<String> values, List<String> errors) {
        Object[] args = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            String value = values.get(i).trim();
            if (value.isEmpty()) {
                if (column.annotation.required()) {
                    errors.add(column.annotation.value() + "：必填");
                }
                args[column.index] = null;
                continue;
            }
            if (column.component.getAnnotation(DictionaryValue.class) != null) {
                String name = value;
                var matches = column.items.stream()
                        .filter(TenantDictionaryItem::selectable)
                        .filter(item -> item.name().equals(name))
                        .toList();
                if (matches.size() != 1) {
                    if (matches.isEmpty()) {
                        String examples = column.items.stream()
                                .filter(TenantDictionaryItem::selectable)
                                .map(TenantDictionaryItem::name)
                                .distinct()
                                .limit(5)
                                .collect(Collectors.joining("、"));
                        errors.add(column.annotation.value()
                                + (examples.isEmpty()
                                        ? "：当前字典没有可选项，请先维护字典选项后重新导入。"
                                        : "：不是可选项名称。请从下拉列表选择，例如：" + examples + "。请填写名称，不要填写字典编码或选项编码。"));
                    } else {
                        errors.add(column.annotation.value() + "：名称重复，无法唯一匹配。请先修改字典中的重名选项，再从下拉列表选择。");
                    }
                    continue;
                }
                value = matches.get(0).code();
            }
            try {
                args[column.index] = convert(value, column.component.getType());
            } catch (IllegalArgumentException | ArithmeticException | DateTimeException error) {
                errors.add(column.annotation.value() + "：格式不正确");
            }
        }
        if (!errors.isEmpty()) {
            return null;
        }
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            errors.add("数据格式不正确");
            return null;
        }
    }

    private Object convert(String value, Class<?> type) {
        if (type == String.class) {
            return value;
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(value);
        }
        if (type == Long.class || type == long.class) {
            return new BigDecimal(value).longValueExact();
        }
        if (type == Integer.class || type == int.class) {
            return new BigDecimal(value).intValueExact();
        }
        if (type == Boolean.class || type == boolean.class) {
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
                return false;
            }
            throw new IllegalArgumentException();
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(value);
        }
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.parse(value);
        }
        if (type == UUID.class) {
            return UUID.fromString(value);
        }
        throw new IllegalArgumentException("Unsupported Excel type " + type);
    }

    record Column(int index, RecordComponent component, ExcelColumn annotation, List<TenantDictionaryItem> items) {
        String header(boolean required) {
            return annotation.value() + (required && annotation.required() ? " *" : "");
        }
    }
}
