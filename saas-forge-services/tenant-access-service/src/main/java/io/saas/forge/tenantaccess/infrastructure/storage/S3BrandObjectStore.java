package io.saas.forge.tenantaccess.infrastructure.storage;

import io.saas.forge.tenantaccess.application.brand.BrandObjectStore;
import java.io.IOException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 独立品牌 Bucket；服务身份仅需该 Bucket 的精确对象读写权限。 */
public class S3BrandObjectStore implements BrandObjectStore {
    private static final int MAX_PNG_BYTES = 2 * 1024 * 1024;
    private final S3Client client;
    private final String bucket;

    public S3BrandObjectStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] png) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/png")
                .cacheControl("no-store").build(), RequestBody.fromBytes(png));
    }

    @Override
    public byte[] get(String key) {
        try (var stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                ResponseTransformer.toInputStream())) {
            byte[] png = stream.readNBytes(MAX_PNG_BYTES + 1);
            if (png.length > MAX_PNG_BYTES) throw new IllegalStateException("Stored brand asset exceeds size limit");
            return png;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read stored brand asset", exception);
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
