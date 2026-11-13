package io.saasforge.sdk.io;

import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.referencedata.ReferenceData;
import io.saasforge.sdk.referencedata.TenantDictionaryItem;
import io.saasforge.sdk.security.EffectiveFieldPolicy;
import io.saasforge.sdk.security.FieldMaskingPolicyProvider;
import io.saasforge.sdk.security.MaskingRule;
import io.saasforge.sdk.security.TenantContext;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Excel 导入导出门面，record 为列模型，数据行经回调流式传递。 */
public final class ExcelIo {
    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String ERROR_REASON_HEADER = "错误原因";
    private final IoTasks tasks;
    private final ObjectStorage storage;
    private final ReferenceData reference;
    private final Validator validator;
    private final IoProperties properties;
    private final TransactionTemplate transaction;
    private final FieldMaskingPolicyProvider masking;

    ExcelIo(
            IoTasks tasks,
            ObjectStorage storage,
            ReferenceData reference,
            Validator validator,
            IoProperties properties,
            TransactionTemplate transaction,
            FieldMaskingPolicyProvider masking) {
        this.tasks = tasks;
        this.storage = storage;
        this.reference = reference;
        this.validator = validator;
        this.properties = properties;
        this.transaction = transaction;
        this.masking = masking;
    }
    /**
     * 按提交时的数据权限异步导出。业务方须在 read 中沿用列表的查询条件，逐页或游标调用 consumer，不能整体加载全部数据。
     *
     * <p>敏感列用 {@code @SensitiveField} 指定登记路径；每任务一份策略快照，零行也预检规则与 L4 限制。
     * 失败任务无结果文件，导入、模板与错误报告不受影响。
     *
     * @param model 导出 record 类型
     * @param filename 结果文件名，以 .xlsx 结尾
     * @param querySummary 不含敏感原文的查询条件摘要，写入审计
     * @param read 流式数据来源
     * @return 等待中的任务
     */
    public <T> IoTask export(Class<T> model, String filename, String querySummary, RowSource<T> read) {
        requireXlsx(filename);
        var columns = new ExcelModel<T>(model, reference);
        return tasks.submit("EXPORT", null, querySummary, progress -> {
            // 任务只读取一次不可变快照；空导出也在创建文件前检查所有列。
            final List<MaskingRule> rules;
            try {
                var snapshot = masking == null
                        ? Map.<String, EffectiveFieldPolicy>of()
                        : Map.copyOf(masking.policies(TenantContext.current().tenantId()));
                rules = columns.exportRules(snapshot);
            } catch (RuntimeException error) {
                // 不保留外部客户端或反射异常的 cause，避免任务日志带出原文。
                throw new ForgeException(IoErrorCode.MASKING_FAILED);
            }
            Path output = Files.createTempFile("forge-export-", ".xlsx");
            try (var workbook = new StreamingWorkbook()) {
                workbook.setCompressTempFiles(true);
                var sheet = workbook.createSheet("数据");
                columns.header(sheet, false);
                read.read(value -> {
                    if (progress.next() > properties.exportLimit()) {
                        throw new ForgeException(IoErrorCode.ROW_LIMIT);
                    }
                    columns.write(sheet.createRow((int) progress.rows()), value, rules);
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
            addDictionaryValidation(workbook, sheet, columns);
            workbook.write(output);
        }
    }

    // 模板与错误报告共用同一份当前租户选项，下载报告后可继续使用下拉修正数据。
    private void addDictionaryValidation(StreamingWorkbook workbook, Sheet sheet, ExcelModel<?> columns) {
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
            for (int column = 0; column < lists.size(); column++) {
                if (row < lists.get(column).size()) {
                    choice.createCell(column).setCellValue(lists.get(column).get(row));
                }
            }
        }
        for (int column = 0; column < lists.size(); column++) {
            var names = lists.get(column);
            if (names.isEmpty()) {
                continue;
            }
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
        workbook.setSheetHidden(workbook.getSheetIndex(choices), true);
    }

    private static void formatReportRow(Sheet sheet, Row row, CellStyle style) {
        int lines = 1;
        for (var cell : row) {
            cell.setCellStyle(style);
            int width = Math.max(1, sheet.getColumnWidth(cell.getColumnIndex()) / 256 - 2);
            int cellLines = 0;
            for (String line : cell.getStringCellValue().split("\\R", -1)) {
                // 中文与全角字符按双宽估算，为不自动计算行高的查看器预留显示空间。
                int characters = line.codePoints()
                        .map(codePoint -> codePoint >= 0x2E80 ? 2 : 1)
                        .sum();
                cellLines += Math.max(1, (characters + width - 1) / width);
            }
            lines = Math.max(lines, cellLines);
        }
        row.setHeightInPoints(Math.min(409.0f, sheet.getDefaultRowHeightInPoints() * lines));
    }

    /**
     * 异步校验导入文件，全部行通过后等待提交人确认；临时转换结果不会跨请求保留。
     * 业务校验不得写数据；通过后须调用 confirmImport 重新校验并写入，源对象保留到任务完成、失败或过期。
     * 错误报告保留字典下拉，可修改后直接重传；末尾“错误原因”列的旧说明不参与业务校验。
     *
     * @param model 导入 record 类型
     * @param source 已确认上传且用途为 {@link IoStoragePurposes#SOURCE} 的 xlsx 对象 ID
     * @param validate 业务校验，返回该行的错误说明
     * @return 等待中的任务
     */
    public <T> IoTask importFile(Class<T> model, UUID source, RowValidator<T> validate) {
        // 任务结束会删除源对象，必须在登记任务之前拒绝其他用途。
        requireXlsx(storage.get(IoStoragePurposes.SOURCE, source).filename());
        var columns = new ExcelModel<T>(model, reference);
        return tasks.submit(
                "IMPORT", source, null, progress -> processImport(columns, source, validate, null, progress));
    }

    /**
     * 提交人确认导入；从持久源文件重新校验全部行，通过后才在单一事务中写入。
     * 确认请求可交给任意实例，业务方须重新传入模型、只读校验器与事务内写入器。
     *
     * @param taskId 待确认的导入任务
     * @param model 与提交时相同的公开 record 类型
     * @param validate 确认时的只读业务校验
     * @param writer 全部行通过重新校验后逐行写入
     * @return 已排队的任务；重复确认或过期任务拒绝执行
     */
    public <T> IoTask confirmImport(UUID taskId, Class<T> model, RowValidator<T> validate, Consumer<T> writer) {
        Objects.requireNonNull(writer, "writer");
        return tasks.confirmImport(taskId, source -> {
            requireXlsx(storage.get(IoStoragePurposes.SOURCE, source).filename());
            var columns = new ExcelModel<T>(model, reference);
            return progress -> processImport(columns, source, validate, writer, progress);
        });
    }

    private <T> UUID processImport(
            ExcelModel<T> columns, UUID source, RowValidator<T> validate, Consumer<T> writer, IoTasks.Progress progress)
            throws Exception {
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
            addDictionaryValidation(workbook, sheet, columns);
            sheet.getRow(0).createCell(columns.columns.size()).setCellValue(ERROR_REASON_HEADER);
            sheet.setColumnWidth(columns.columns.size(), 60 * 256);
            var reportStyle = workbook.createCellStyle();
            reportStyle.setWrapText(true);
            reportStyle.setVerticalAlignment(VerticalAlignment.TOP);
            formatReportRow(sheet, sheet.getRow(0), reportStyle);
            var hasErrors = new AtomicBoolean();
            var headerSeen = new AtomicBoolean();
            var hasPreviousErrors = new AtomicBoolean();
            try (var spool = Files.newBufferedWriter(valid)) {
                XlsxRows.read(original, columns.columns.size() + 1, (number, cells) -> {
                    var values = cells.subList(0, columns.columns.size());
                    String previousError = cells.get(columns.columns.size());
                    if (!headerSeen.getAndSet(true)) {
                        for (int i = 0; i < values.size(); i++) {
                            if (!values.get(i).equals(columns.columns.get(i).header(true))
                                    && !values.get(i)
                                            .equals(columns.columns.get(i).header(false))) {
                                throw new ForgeException(IoErrorCode.INVALID_FILE);
                            }
                        }
                        if (!previousError.isBlank() && !ERROR_REASON_HEADER.equals(previousError)) {
                            throw new ForgeException(IoErrorCode.INVALID_FILE);
                        }
                        hasPreviousErrors.set(ERROR_REASON_HEADER.equals(previousError));
                        return;
                    }
                    // 只忽略明确标为错误说明的末列，避免修正报告重传时把旧错误当作业务数据。
                    if (!hasPreviousErrors.get() && !previousError.isBlank()) {
                        throw new ForgeException(IoErrorCode.INVALID_FILE);
                    }
                    if (values.stream().allMatch(String::isBlank)) {
                        return;
                    }
                    if (progress.next() > properties.importLimit()) {
                        throw new ForgeException(IoErrorCode.ROW_LIMIT);
                    }
                    var errors = new ArrayList<String>();
                    T value = columns.parse(values, errors);
                    if (value != null) {
                        validator.validate(value).stream()
                                .sorted(Comparator.comparing(
                                        v -> v.getPropertyPath().toString()))
                                .forEach(v -> errors.add(v.getPropertyPath() + "：" + v.getMessage()));
                        if (errors.isEmpty()) {
                            errors.addAll(validate.validate(value));
                        }
                    }
                    var row = sheet.createRow((int) progress.rows());
                    for (int i = 0; i < values.size(); i++) {
                        row.createCell(i).setCellValue(values.get(i));
                    }
                    row.createCell(values.size()).setCellValue(String.join("\n", errors));
                    formatReportRow(sheet, row, reportStyle);
                    if (!errors.isEmpty()) {
                        hasErrors.set(true);
                    } else {
                        try {
                            spool.write(json.writeValueAsString(value));
                            spool.newLine();
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    }
                });
            }
            if (!headerSeen.get()) {
                throw new ForgeException(IoErrorCode.INVALID_FILE);
            }
            if (hasErrors.get()) {
                try (var output = Files.newOutputStream(report)) {
                    workbook.write(output);
                }
                progress.fail(IoErrorCode.VALIDATION_FAILED.code());
                return store("错误报告.xlsx", report).id();
            }
            if (writer == null) {
                progress.awaitConfirmation();
                return null;
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
    }
    /** 只读的业务校验扩展；通过时返回空列表。 */
    @FunctionalInterface
    public interface RowValidator<T> {
        /** 返回面向填写者的错误原因，不执行业务写入。 */
        List<String> validate(T row);
    }

    StoredObject store(String filename, Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return storage.write(IoStoragePurposes.RESULT, filename, CONTENT_TYPE, Files.size(path), input);
        }
    }

    static void requireXlsx(String filename) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ForgeException(IoErrorCode.INVALID_FILE);
        }
    }
    /** 按行提供数据；数据源负责关闭数据库游标等资源。 */
    @FunctionalInterface
    public interface RowSource<T> {
        /** 把每行传给 consumer；不得保存所有行后一次发送。 */
        void read(Consumer<T> consumer) throws Exception;
    }
}
