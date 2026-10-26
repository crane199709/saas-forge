package io.saasforge.sdk.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.CurrentTenant;
import java.util.List;
import java.util.UUID;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.Test;

class CurrentTenantLineHandlerTest {
    final CurrentTenantLineHandler handler = new CurrentTenantLineHandler(List.of("Dictionary"));

    @Test
    void globalTablesMatchIgnoringSchemaQuotesAndCase() {
        assertThat(handler.ignoreTable("dictionary")).isTrue();
        assertThat(handler.ignoreTable("demo.\"DICTIONARY\"")).isTrue();
        assertThat(handler.ignoreTable("orders")).isFalse();
    }

    @Test
    void tenantIdComesFromCurrentTenantAndIsRequired() {
        assertThatThrownBy(handler::getTenantId).isInstanceOf(IllegalStateException.class);

        UUID tenantId = UUID.fromString("019a0110-0000-7000-8000-00000000000a");
        CurrentTenant.run(
                tenantId,
                () -> assertThat(((StringValue) handler.getTenantId()).getValue())
                        .isEqualTo(tenantId.toString()));
    }
}
