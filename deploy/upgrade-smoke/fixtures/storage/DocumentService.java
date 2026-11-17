package com.example.retained.document.service;

import com.example.retained.document.mapper.DocumentMapper;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.storage.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 样例已有业务：附件先经存储确认，再在同一业务事务建立引用。 */
@Service
public class DocumentService {
    private final ObjectStorage storage;
    private final DocumentMapper mapper;

    public DocumentService(ObjectStorage storage, DocumentMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    public UploadTicket upload(String filename, String contentType, long size) {
        return storage.prepareUpload(filename, contentType, size);
    }

    public StoredObject complete(UUID id) { return storage.completeUpload(id); }
    public StoredObject object(UUID id) { return storage.get(id); }
    public DownloadTicket download(UUID id) { return storage.prepareDownload(id); }
    public StorageUsage usage() { return storage.usage(); }

    @Transactional
    public Document create(UUID objectId, String title) {
        storage.get(objectId);
        UUID id = UuidV7.generate();
        mapper.insert(id, objectId, title);
        return get(id);
    }

    public Document get(UUID id) {
        Document document = mapper.find(id);
        if (document == null) { throw new ForgeException(DocumentErrorCode.NOT_FOUND); }
        return document;
    }

    public DownloadTicket downloadDocument(UUID id) { return download(get(id).objectId()); }

    @Transactional
    public void delete(UUID id) { get(id); mapper.delete(id); }

    public record Document(UUID id, UUID objectId, String title) {}
}
