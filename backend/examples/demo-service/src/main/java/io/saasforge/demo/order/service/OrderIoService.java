package io.saasforge.demo.order.service;

import io.saasforge.demo.order.model.OrderExportRow;
import io.saasforge.demo.order.model.OrderImportRow;
import io.saasforge.demo.order.model.OrderOverwriteRow;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.io.ExcelIo;
import io.saasforge.sdk.io.ImportBackup;
import io.saasforge.sdk.io.ImportRollbackPreview;
import io.saasforge.sdk.io.ImportRollbackResult;
import io.saasforge.sdk.io.ImportRollbackWriter;
import io.saasforge.sdk.io.ImportRollbacks;
import io.saasforge.sdk.io.ImportSnapshot;
import io.saasforge.sdk.io.ImportWriter;
import io.saasforge.sdk.io.IoErrorCode;
import io.saasforge.sdk.io.IoStoragePurposes;
import io.saasforge.sdk.io.IoTask;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.UploadTicket;
import io.saasforge.sdk.web.PageQuery;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderIoService {
    private final OrderService orders;
    private final ExcelIo excel;
    private final ObjectStorage storage;
    private final ImportRollbacks rollbacks;

    public OrderIoService(OrderService orders, ExcelIo excel, ObjectStorage storage, ImportRollbacks rollbacks) {
        this.orders = orders;
        this.excel = excel;
        this.storage = storage;
        this.rollbacks = rollbacks;
    }

    public void template(OutputStream output) throws Exception {
        excel.template(OrderImportRow.class, output);
    }

    public UploadTicket upload(String filename, String contentType, long size) {
        return storage.prepareUpload(IoStoragePurposes.SOURCE, filename, contentType, size);
    }

    public IoTask importFile(UUID source) {
        storage.completeUpload(source);
        return excel.importFile(OrderImportRow.class, source, this::validate);
    }

    public IoTask confirmImport(UUID taskId) {
        return excel.confirmImport(taskId, OrderImportRow.class, this::validate, this::write);
    }

    public void overwriteTemplate(OutputStream output) throws Exception {
        excel.template(OrderOverwriteRow.class, output);
    }

    public IoTask importOverwrite(UUID source) {
        storage.completeUpload(source);
        return excel.importFile(OrderOverwriteRow.class, source, this::validateOverwrite);
    }

    public IoTask confirmOverwrite(UUID taskId) {
        return excel.confirmImport(taskId, OrderOverwriteRow.class, this::validateOverwrite, new ImportWriter<>() {
            @Override
            public ImportSnapshot beforeWrite(OrderOverwriteRow row) {
                if (row.id() == null) {
                    return null;
                }
                var order = orders.getForImport(row.id());
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("title", order.getTitle());
                values.put("amount", order.getAmount());
                values.put("customerSource", order.getCustomerSource());
                return new ImportSnapshot(row.id().toString(), values);
            }

            @Override
            public String write(OrderOverwriteRow row) {
                if (row.id() == null) {
                    orders.create(row.title(), row.amount(), row.customerSource());
                    return null;
                }
                return orders.overwriteImported(row.id(), row.title(), row.amount(), row.customerSource())
                        .getUpdatedAt()
                        .toString();
            }
        });
    }

    public ImportRollbackPreview previewRollback(UUID taskId) {
        return rollbacks.preview(taskId, OrderOverwriteRow.class, recordId -> {
            try {
                return orders.get(UUID.fromString(recordId)).getUpdatedAt().toString();
            } catch (ForgeException error) {
                if (error.errorCode() == OrderErrorCode.NOT_FOUND) {
                    return null;
                }
                throw error;
            }
        });
    }

    public ImportRollbackResult rollback(UUID taskId, boolean skipConflicts) {
        var result = rollbacks.execute(taskId, OrderOverwriteRow.class, skipConflicts, new ImportRollbackWriter() {
            @Override
            public String readVersion(String recordId) {
                return version(recordId, false);
            }

            @Override
            public String lockAndReadVersion(String recordId) {
                return version(recordId, true);
            }

            @Override
            public void restore(ImportBackup backup) {
                var values = backup.values();
                orders.overwriteImported(
                        UUID.fromString(backup.recordId()),
                        (String) values.get("title"),
                        new BigDecimal(values.get("amount").toString()),
                        (String) values.get("customerSource"));
            }
        });
        if ("BLOCKED".equals(result.status())) {
            throw new ForgeException(IoErrorCode.ROLLBACK_CONFLICTS);
        }
        if ("UNAVAILABLE".equals(result.status())) {
            throw new ForgeException(IoErrorCode.ROLLBACK_UNAVAILABLE);
        }
        return result;
    }

    private String version(String recordId, boolean lock) {
        try {
            UUID id = UUID.fromString(recordId);
            return lock
                    ? orders.lockRollbackVersion(id)
                    : orders.get(id).getUpdatedAt().toString();
        } catch (ForgeException error) {
            if (error.errorCode() == OrderErrorCode.NOT_FOUND) {
                return null;
            }
            throw error;
        }
    }

    private List<String> validateOverwrite(OrderOverwriteRow row) {
        if (row.id() != null) {
            try {
                orders.get(row.id());
            } catch (ForgeException error) {
                if (error.errorCode() == OrderErrorCode.NOT_FOUND) {
                    return List.of("订单不存在或无访问权限");
                }
                throw error;
            }
        }
        return List.of();
    }

    private List<String> validate(OrderImportRow row) {
        return List.of();
    }

    private void write(OrderImportRow row) {
        orders.create(row.title(), row.amount(), row.customerSource());
    }

    public IoTask export(PageQuery query) {
        var sorts = List.copyOf(query.sorts());
        return excel.export(OrderExportRow.class, "订单.xlsx", "sort=" + sorts, consumer -> {
            for (int page = 1; ; page++) {
                var rows = orders.list(new PageQuery(page, 100, sorts));
                for (var row : rows) {
                    consumer.accept(new OrderExportRow(
                            row.getNumber(), row.getTitle(), row.getAmount(), row.getCustomerSource()));
                }
                if (rows.size() < 100) {
                    return;
                }
            }
        });
    }
}
