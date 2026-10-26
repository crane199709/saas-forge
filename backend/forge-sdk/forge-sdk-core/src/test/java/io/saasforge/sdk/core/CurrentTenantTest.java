package io.saasforge.sdk.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CurrentTenantTest {
    static final UUID TENANT_A = UUID.fromString("019a0110-0000-7000-8000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-00000000000b");

    @Test
    void requireFailsWithoutBinding() {
        assertThat(CurrentTenant.find()).isEmpty();
        assertThatThrownBy(CurrentTenant::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nestedBindingRestoresOuterTenantAndClearsAtTheEnd() {
        CurrentTenant.run(TENANT_A, () -> {
            CurrentTenant.run(
                    TENANT_B, () -> assertThat(CurrentTenant.require()).isEqualTo(TENANT_B));
            assertThat(CurrentTenant.require()).isEqualTo(TENANT_A);
        });
        assertThat(CurrentTenant.find()).isEmpty();
    }

    @Test
    void otherThreadsGetTenantOnlyWhenCarried() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var carried = new AtomicReference<UUID>();
            CurrentTenant.run(
                    TENANT_A,
                    () -> CompletableFuture.runAsync(
                                    CurrentTenant.wrap(() -> carried.set(CurrentTenant.require())), executor)
                            .join());
            assertThat(carried).hasValue(TENANT_A);

            var notCarried = CompletableFuture.runAsync(CurrentTenant::require, executor);
            assertThatThrownBy(notCarried::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            executor.shutdownNow();
        }
    }
}
