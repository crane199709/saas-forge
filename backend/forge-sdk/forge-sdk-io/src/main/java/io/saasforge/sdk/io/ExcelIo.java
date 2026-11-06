package io.saasforge.sdk.io;

import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.referencedata.ReferenceData;
import io.saasforge.sdk.referencedata.TenantDictionaryItem;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.StoredObject;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Excel 导入导出门面，record 为列模型，数据行经回调流式传递。 */
public final class ExcelIo {
    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final IoTasks tasks;
    private final ObjectStorage storage;
    private final ReferenceData reference;
    private final Validator validator;
    private final IoProperties properties;
    private final TransactionTemplate transaction;

    ExcelIo(
            IoTasks tasks,
            ObjectStorage storage,
            ReferenceData reference,
            Validator validator,
            IoProperties properties,
            TransactionTemplate transaction) {
        this.tasks = tasks;
        this.storage = storage;
        this.reference = reference;
        this.validator = validator;
        this.properties = properties;
        this.transaction = transaction;
    }
    /**
     * 按提交时的数据权限异步导出。业务方须在 read 中沿用列表的查询条件，逐页或游标调用 consumer，不能整体加载全部数据。
     *
     * @param model 导出 record 类型
     * @param filename 结果文件名，以 .xlsx 结尾
     * @param querySummary 查询条件摘要，写入审计
     * @param read 流式数据来源
     * @return 等待中的任务
     */
    public <T> IoTask export(Class<T> model, String filename, String querySummary, RowSource<T> read) {
        requireXlsx(filename);
        var columns = new ExcelModel<T>(model, reference);
        return tasks.submit("EXPORT", null, querySummary, progress -> {
            Path output = Files.createTempFile("forge-export-", ".xlsx");
            try (var workbook = new StreamingWorkbook()) {
                workbook.setCompressTempFiles(true);
                var sheet = workbook.createSheet("数据");
                columns.header(sheet, false);
                read.read(value -> {
                    if (progress.next() > properties.exportLimit()) throw new ForgeException(IoErrorCode.ROW_LIMIT);
                    columns.write(sheet.createRow((int) progress.rows()), value);
                });
                try (var stream = Files.newOutputStream(output)) {
                    workbook.write(stream);
                }
                return store(filename, output).id();
            } finally {
                Files.deleteIfExists(output);
            }
        });
    }
    /**
     * 生成导入模板；字典选项放入隐藏工作表，避免 Excel 内联下拉 255 字符限制。调用方负责关闭输出流。
     *
     * @param model 导入 record 类型
     * @param output xlsx 输出流
     */
    public <T> void template(Class<T> model, OutputStream output) throws Exception {
        var columns = new ExcelModel<T>(model, reference);
        try (var workbook = new StreamingWorkbook()) {
            workbook.setCompressTempFiles(true);
            var sheet = workbook.createSheet("数据");
            columns.header(sheet, true);
            var choices = workbook.createSheet("选项");
            var lists = columns.columns.stream()
                    .map(column -> column.items().stream()
                            .filter(TenantDictionaryItem::selectable)
                            .map(TenantDictionaryItem::name)
                            .distinct()
                            .toList())
                    .toList();
            int max = lists.stream().mapToInt(List::size).max().orElse(0);
            for (int row = 0; row < max; row++) {
                var choice = choices.createRow(row);
                for (int column = 0; column < lists.size(); column++)
                    if (row < lists.get(column).size())
                        choice.createCell(column).setCellValue(lists.get(column).get(row));
            }
            for (int column = 0; column < lists.size(); column++) {
                var names = lists.get(column);
                if (names.isEmpty()) continue;
                String letter = CellReference.convertNumToColString(column);
                var name = workbook.createName();
                name.setNameName("choices_" + column);
                name.setRefersToFormula("'选项'!$" + letter + "$1:$" + letter + "$" + names.size());
                var helper = sheet.getDataValidationHelper();
                var validation = helper.createValidation(
                        helper.createFormulaListConstraint(name.getNameName()),
                        new CellRangeAddressList(1, Math.min(properties.importLimit(), 1048575), column, column));
                validation.setShowErrorBox(true);
                validation.createErrorBox("无效选项", "请选择下拉列表中的名称");
                sheet.addValidationData(validation);
            }
            workbook.setSheetHidden(1, true);
            workbook.write(output);
        }
    }

    /**
     * 异步导入：先校验全部行并将转换后的 record 写入磁盘，再在单一事务中逐行调用 writer。
     * 业务校验不得写数据；writer 抛异常会回滚所有行，源对象在任务结束后删除。
     *
     * @param model 导入 record 类型
     * @param source 已确认上传的 xlsx 对象 ID
     * @param validate 业务校验，返回该行的错误说明
     * @param writer 事务内的逐行写入
     * @return 等待中的任务
     */
    public <T> IoTask importFile(Class<T> model, UUID source, RowValidator<T> validate, Consumer<T> writer) {
        requireXlsx(storage.get(source).filename());
        var columns = new ExcelModel<T>(model, reference);
        return tasks.submit("IMPORT", source, null, progress -> {
            Path original = Files.createTempFile("forge-import-", ".xlsx");
            Path valid = Files.createTempFile("forge-valid-", ".jsonl");
            Path report = Files.createTempFile("forge-errors-", ".xlsx");
            var json = JsonMapper.builder().build();
            try (var workbook = new StreamingWorkbook()) {
                workbook.setCompressTempFiles(true);
                try (var input = storage.open(source)) {
                    Files.copy(input, original, StandardCopyOption.REPLACE_EXISTING);
                }
                var sheet = workbook.createSheet("错误报告");
                columns.header(sheet, true);
                sheet.getRow(0).createCell(columns.columns.size()).setCellValue("错误原因");
                var hasErrors = new AtomicBoolean();
                var headerSeen = new AtomicBoolean();
                try (var spool = Files.newBufferedWriter(valid)) {
                    XlsxRows.read(original, columns.columns.size(), (number, values) -> {
                        if (!headerSeen.getAndSet(true)) {
                            for (int i = 0; i < values.size(); i++)
                                if (!values.get(i).equals(columns.columns.get(i).header(true))
                                        && !values.get(i)
                                                .equals(columns.columns.get(i).header(false)))
                                    throw new ForgeException(IoErrorCode.INVALID_FILE);
                            return;
                        }
                        if (values.stream().allMatch(String::isBlank)) return;
                        if (progress.next() > properties.importLimit()) throw new ForgeException(IoErrorCode.ROW_LIMIT);
                        var errors = new ArrayList<String>();
                        T value = columns.parse(values, errors);
                        if (value != null) {
                            validator.validate(value).stream()
                                    .sorted(Comparator.comparing(
                                            v -> v.getPropertyPath().toString()))
                                    .forEach(v -> errors.add(v.getPropertyPath() + "：" + v.getMessage()));
                            if (errors.isEmpty()) errors.addAll(validate.validate(value));
                        }
                        var row = sheet.createRow((int) progress.rows());
                        for (int i = 0; i < values.size(); i++)
                            row.createCell(i).setCellValue(values.get(i));
                        row.createCell(values.size()).setCellValue(String.join("；", errors));
                        if (!errors.isEmpty()) hasErrors.set(true);
                        else
                            try {
                                spool.write(json.writeValueAsString(value));
                                spool.newLine();
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            }
                    });
                }
                if (!headerSeen.get()) throw new ForgeException(IoErrorCode.INVALID_FILE);
                if (hasErrors.get()) {
                    try (var output = Files.newOutputStream(report)) {
                        workbook.write(output);
                    }
                    progress.fail(IoErrorCode.VALIDATION_FAILED.code());
                    return store("错误报告.xlsx", report).id();
                }
                transaction.executeWithoutResult(status -> {
                    try (var rows = Files.lines(valid)) {
                        rows.forEach(line -> writer.accept(json.readValue(line, columns.type())));
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
                return null;
            } finally {
                Files.deleteIfExists(original);
                Files.deleteIfExists(valid);
                Files.deleteIfExists(report);
            }
        });
    }
    /** 只读的业务校验扩展；通过时返回空列表。 */
    @FunctionalInterface
    public interface RowValidator<T> {
        /** 返回面向填写者的错误原因，不执行业务写入。 */
        List<String> validate(T row);
    }

    StoredObject store(String filename, Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return storage.write(filename, CONTENT_TYPE, Files.size(path), input);
        }
    }

    static void requireXlsx(String filename) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new ForgeException(IoErrorCode.INVALID_FILE);
    }
    /** 按行提供数据；数据源负责关闭数据库游标等资源。 */
    @FunctionalInterface
    public interface RowSource<T> {
        /** 把每行传给 consumer；不得保存所有行后一次发送。 */
        void read(Consumer<T> consumer) throws Exception;
    }
}
