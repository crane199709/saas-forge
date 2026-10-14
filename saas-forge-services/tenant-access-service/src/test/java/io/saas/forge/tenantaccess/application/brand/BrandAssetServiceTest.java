package io.saas.forge.tenantaccess.application.brand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class BrandAssetServiceTest {
    private final TenantContextSnapshot context = new TenantContextSnapshot(
            UUID.fromString("019535d9-0001-7000-8000-000000000001"),
            UUID.fromString("019535d9-0001-7000-8000-000000000002"),
            UUID.fromString("019535d9-0001-7000-8000-000000000003"));
    private final PermissionAuthority authority = mock(PermissionAuthority.class);
    private final BrandAssetRepository assets = mock(BrandAssetRepository.class);
    private final BrandObjectStore objects = mock(BrandObjectStore.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final UuidV7Generator ids = new UuidV7Generator(
            Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    private BrandAssetService service() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new BrandAssetService(authority, assets, objects,
                new BrandAssetEventFactory(new ObjectMapper(), ids, Clock.systemUTC(),
                        "saas.forge.dev.tenant-access-service.events"), outbox, ids,
                new TransactionTemplate(manager));
    }

    @Test
    void authorizedUploadCanBeReadThroughItsPublicReferenceWithoutSavingProfile() throws Exception {
        when(authority.allowed(context, "system:brand:manage")).thenReturn(true);
        byte[] png = png();
        BrandAsset asset = service().upload(context, BrandPng.Kind.FAVICON, png);
        String key = context.tenantId() + "/" + asset.id() + ".png";
        verify(objects).put(key, png);
        verify(assets).insert(asset, key, png.length);
        verify(outbox).append(any());
        assertEquals("/brands/" + asset.id(), asset.url());
        when(assets.publicKey(asset.id())).thenReturn(Optional.of(key));
        when(objects.get(key)).thenReturn(png);
        assertArrayEquals(png, service().publicRead(asset.id()));
    }

    @Test
    void missingPermissionDoesNotWriteObjectOrFact() throws Exception {
        assertThrows(BrandAssetService.Forbidden.class,
                () -> service().upload(context, BrandPng.Kind.FAVICON, png()));
        verifyNoInteractions(objects, assets, outbox);
    }

    @Test
    void failedDatabaseWriteRemovesTheUploadedObject() throws Exception {
        when(authority.allowed(context, "system:brand:manage")).thenReturn(true);
        doThrow(new IllegalStateException("database unavailable")).when(assets).insert(any(), any(), anyInt());
        assertThrows(IllegalStateException.class,
                () -> service().upload(context, BrandPng.Kind.FAVICON, png()));
        verify(objects).put(any(), any());
        verify(objects).delete(any());
        verifyNoInteractions(outbox);
    }

    private static byte[] png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
}
