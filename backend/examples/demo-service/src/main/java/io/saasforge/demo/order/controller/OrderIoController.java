package io.saasforge.demo.order.controller;

import io.saasforge.demo.order.service.OrderIoService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.io.IoTask;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.storage.UploadTicket;
import io.saasforge.sdk.web.PageQuery;
import io.saasforge.sdk.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
class OrderIoController {
    private final OrderIoService service;

    OrderIoController(OrderIoService service) {
        this.service = service;
    }

    @Operation(summary = "下载含客户来源下拉的订单导入模板")
    @GetMapping("/import/template")
    @RequiresPermission("order:import")
    ApiResponse<TemplateResponse> template() throws Exception {
        try (var output = new ByteArrayOutputStream()) {
            service.template(output);
            return ApiResponse.ok(
                    new TemplateResponse("订单导入模板.xlsx", Base64.getEncoder().encodeToString(output.toByteArray())));
        }
    }

    @Operation(summary = "申请订单导入源文件的直传票据")
    @PostMapping("/import/upload")
    @RequiresPermission("order:import")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UploadTicket> upload(@RequestBody UploadRequest request) {
        return ApiResponse.created(service.upload(request.filename(), request.contentType(), request.size()));
    }

    @Operation(summary = "确认上传并提交订单导入")
    @PostMapping("/import")
    @RequiresPermission("order:import")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<IoTask> importFile(@RequestBody ImportRequest request) {
        return ApiResponse.created(service.importFile(request.objectId()));
    }

    @Operation(summary = "重新校验并确认写入订单导入任务")
    @PostMapping("/import/{taskId}/confirm")
    @RequiresPermission("order:import")
    ApiResponse<IoTask> confirmImport(@PathVariable UUID taskId) {
        return ApiResponse.ok(service.confirmImport(taskId));
    }

    record UploadRequest(String filename, String contentType, long size) {}

    record ImportRequest(UUID objectId) {}

    record TemplateResponse(String filename, String base64) {}

    @Operation(summary = "按当前列表排序与数据权限提交订单导出")
    @PostMapping("/export")
    @RequiresPermission("order:export")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<IoTask> export(@SortableFields({"createdAt", "amount"}) PageQuery query) {
        return ApiResponse.created(service.export(query));
    }
}
