package io.saasforge.demo.order.controller;

import io.saasforge.demo.order.service.OrderIoService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.io.ImportRollbackPreview;
import io.saasforge.sdk.io.ImportRollbackResult;
import io.saasforge.sdk.io.IoTask;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.storage.UploadTicket;
import io.saasforge.sdk.web.PageQuery;
import io.saasforge.sdk.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

    @Operation(summary = "下载覆盖导入模板，订单ID为空时新增")
    @GetMapping("/import/overwrite/template")
    @RequiresPermission("order:import")
    ApiResponse<TemplateResponse> overwriteTemplate() throws Exception {
        try (var output = new ByteArrayOutputStream()) {
            service.overwriteTemplate(output);
            return ApiResponse.ok(
                    new TemplateResponse("订单覆盖导入模板.xlsx", Base64.getEncoder().encodeToString(output.toByteArray())));
        }
    }

    @Operation(summary = "提交订单覆盖导入，已有订单确认前不改写")
    @PostMapping("/import/overwrite")
    @RequiresPermission("order:import")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<IoTask> importOverwrite(@RequestBody ImportRequest request) {
        return ApiResponse.created(service.importOverwrite(request.objectId()));
    }

    @Operation(summary = "确认覆盖导入，写入前保存被覆盖字段旧值")
    @PostMapping("/import/overwrite/{taskId}/confirm")
    @RequiresPermission("order:import")
    ApiResponse<IoTask> confirmOverwrite(@PathVariable UUID taskId) {
        return ApiResponse.ok(service.confirmOverwrite(taskId));
    }

    @Operation(summary = "只读预览覆盖导入的回滚冲突")
    @GetMapping("/import/{taskId}/rollback-preview")
    @RequiresPermission("order:importRollback")
    ApiResponse<ImportRollbackPreview> previewRollback(@PathVariable UUID taskId) {
        return ApiResponse.ok(service.previewRollback(taskId));
    }

    @Operation(summary = "恢复覆盖导入的旧字段，可显式跳过冲突")
    @PostMapping("/import/{taskId}/rollback")
    @RequiresPermission("order:importRollback")
    ApiResponse<ImportRollbackResult> rollback(@PathVariable UUID taskId, @Valid @RequestBody RollbackRequest request) {
        return ApiResponse.ok(service.rollback(taskId, request.skipConflicts()));
    }

    record RollbackRequest(boolean skipConflicts) {}

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
