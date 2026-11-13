package io.saasforge.demo.order.service;

import io.saasforge.demo.order.model.OrderExportRow;
import io.saasforge.demo.order.model.OrderImportRow;
import io.saasforge.sdk.io.ExcelIo;
import io.saasforge.sdk.io.IoStoragePurposes;
import io.saasforge.sdk.io.IoTask;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.UploadTicket;
import io.saasforge.sdk.web.PageQuery;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderIoService {
    private final OrderService orders;
    private final ExcelIo excel;
    private final ObjectStorage storage;

    public OrderIoService(OrderService orders, ExcelIo excel, ObjectStorage storage) {
        this.orders = orders;
        this.excel = excel;
        this.storage = storage;
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
