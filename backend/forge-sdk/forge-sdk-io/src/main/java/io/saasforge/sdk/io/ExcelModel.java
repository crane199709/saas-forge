package io.saasforge.sdk.io;

import io.saasforge.sdk.referencedata.DictionaryValue;
import io.saasforge.sdk.referencedata.ReferenceData;
import io.saasforge.sdk.referencedata.TenantDictionaryItem;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

final class ExcelModel<T> {
    private final Class<T> type;
    private final Constructor<T> constructor;
    final List<Column> columns;

    ExcelModel(Class<T> type, ReferenceData reference) {
        if (!type.isRecord()) throw new IllegalArgumentException("Excel model must be a record");
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
            if (annotation == null)
                throw new IllegalArgumentException("Every record component needs ExcelColumn: " + component.getName());
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
                || columns.stream().map(c -> c.annotation.value()).distinct().count() != columns.size())
            throw new IllegalArgumentException("Excel columns must have unique names and order");
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

    void write(Row row, T value) {
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
                row.createCell(i).setCellValue(text);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException(error);
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
                if (column.annotation.required()) errors.add(column.annotation.value() + "：必填");
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
                    errors.add(column.annotation.value() + (matches.isEmpty() ? "：不是可选项名称" : "：名称重复，请先修改字典名称"));
                    continue;
                }
                value = matches.get(0).code();
            }
            try {
                args[column.index] = convert(value, column.component.getType());
            } catch (IllegalArgumentException error) {
                errors.add(column.annotation.value() + "：格式不正确");
            }
        }
        if (!errors.isEmpty()) return null;
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException error) {
            errors.add("数据格式不正确");
            return null;
        }
    }

    private Object convert(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == BigDecimal.class) return new BigDecimal(value);
        if (type == Long.class || type == long.class) return new BigDecimal(value).longValueExact();
        if (type == Integer.class || type == int.class) return new BigDecimal(value).intValueExact();
        if (type == Boolean.class || type == boolean.class) {
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
            throw new IllegalArgumentException();
        }
        if (type == LocalDate.class) return LocalDate.parse(value);
        if (type == OffsetDateTime.class) return OffsetDateTime.parse(value);
        if (type == UUID.class) return UUID.fromString(value);
        throw new IllegalArgumentException("Unsupported Excel type " + type);
    }

    record Column(int index, RecordComponent component, ExcelColumn annotation, List<TenantDictionaryItem> items) {
        String header(boolean required) {
            return annotation.value() + (required && annotation.required() ? " *" : "");
        }
    }
}
