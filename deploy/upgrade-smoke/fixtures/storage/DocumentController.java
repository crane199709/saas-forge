package com.example.retained.document.controller;
import com.example.retained.document.service.DocumentService;
import com.example.retained.document.service.DocumentService.Document;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.storage.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/documents")
class DocumentController {
    private final DocumentService service;
    DocumentController(DocumentService service) { this.service = service; }
    @PostMapping("/upload") @RequiresPermission("order:create") @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UploadTicket> upload(@RequestBody Upload request) { return ApiResponse.created(service.upload(request.filename(), request.contentType(), request.size())); }
    @PostMapping("/objects/{id}/complete") @RequiresPermission("order:create")
    ApiResponse<StoredObject> complete(@PathVariable UUID id) { return ApiResponse.ok(service.complete(id)); }
    @GetMapping("/objects/{id}") @RequiresPermission("order:read")
    ApiResponse<StoredObject> object(@PathVariable UUID id) { return ApiResponse.ok(service.object(id)); }
    @GetMapping("/objects/{id}/download") @RequiresPermission("order:read")
    ApiResponse<DownloadTicket> download(@PathVariable UUID id) { return ApiResponse.ok(service.download(id)); }
    @GetMapping("/usage") @RequiresPermission("order:read")
    ApiResponse<StorageUsage> usage() { return ApiResponse.ok(service.usage()); }
    @PostMapping @RequiresPermission("order:create") @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Document> create(@RequestBody DocumentRequest request) { return ApiResponse.created(service.create(request.objectId(), request.title())); }
    @GetMapping("/{id}") @RequiresPermission("order:read")
    ApiResponse<Document> get(@PathVariable UUID id) { return ApiResponse.ok(service.get(id)); }
    @GetMapping("/{id}/download") @RequiresPermission("order:read")
    ApiResponse<DownloadTicket> documentDownload(@PathVariable UUID id) { return ApiResponse.ok(service.downloadDocument(id)); }
    @DeleteMapping("/{id}") @RequiresPermission("order:delete")
    ApiResponse<Void> delete(@PathVariable UUID id) { service.delete(id); return ApiResponse.ok(); }
    record Upload(String filename, String contentType, long size) {}
    record DocumentRequest(UUID objectId, String title) {}
}
