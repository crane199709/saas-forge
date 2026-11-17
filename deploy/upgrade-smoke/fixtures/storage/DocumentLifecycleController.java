package com.example.retained.document.controller;

import com.example.retained.document.service.DocumentService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.storage.StorageObjectLifecycle;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅供独立样例业务消费 SDK 的生命周期入口，不执行数据库或工程升级。 */
@RestController
@RequestMapping("/documents/objects")
class DocumentLifecycleController {
    private final DocumentService service;

    DocumentLifecycleController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/{id}/lifecycle")
    @RequiresPermission("order:read")
    ApiResponse<StorageObjectLifecycle> lifecycle(@PathVariable UUID id) {
        return ApiResponse.ok(service.lifecycle(id));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("order:delete")
    ApiResponse<Void> expire(@PathVariable UUID id) {
        service.expire(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/restore")
    @RequiresPermission("order:update")
    ApiResponse<StorageObjectLifecycle> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }
}
