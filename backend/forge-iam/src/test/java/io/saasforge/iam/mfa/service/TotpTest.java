package io.saasforge.iam.mfa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TotpTest {
    // RFC 6238 附录 B 的 SHA-1 测试密钥。
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final long NEVER_USED = -1;

    @Test
    void codesMatchTheRfc6238Sha1VectorsTruncatedToSixDigits() {
        // 附录 B 给出 8 位码 94287082、07081804、89005924，6 位取其末 6 位。
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109))))
                .isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890))))
                .isEqualTo("005924");
    }

    @Test
    void acceptsOneStepOfClockDriftInEitherDirectionAndNoMore() {
        long step = 50_000_000;
        Instant firstInstantOfStep = Instant.ofEpochSecond(step * 30);
        Instant lastInstantOfStep = firstInstantOfStep.plusSeconds(30).minusMillis(1);
        for (Instant now : new Instant[] {firstInstantOfStep, lastInstantOfStep}) {
            assertThat(match(step - 1, now)).hasValue(step - 1);
            assertThat(match(step, now)).hasValue(step);
            assertThat(match(step + 1, now)).hasValue(step + 1);
            assertThat(match(step - 2, now)).isEmpty();
            assertThat(match(step + 2, now)).isEmpty();
        }
        // 跨过步长边界的那一毫秒，可接受的窗口随之移动。
        assertThat(match(step + 1, firstInstantOfStep.minusMillis(1))).isEmpty();
        assertThat(match(step - 1, lastInstantOfStep.plusMillis(1))).isEmpty();
    }

    @Test
    void aCodeAtOrBeforeTheLastUsedStepIsRejectedAsReplay() {
        long step = 50_000_000;
        Instant now = Instant.ofEpochSecond(step * 30 + 10);
        String code = Totp.code(RFC_SECRET, step);
        assertThat(Totp.match(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, step))
                .isEmpty();
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, step))
                .hasValue(step + 1);
    }

    @Test
    void malformedCodesNeverMatch() {
        Instant now = Instant.ofEpochSecond(59);
        assertThat(Totp.match(RFC_SECRET, "28708", now, NEVER_USED)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "2870821", now, NEVER_USED)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "28708a", now, NEVER_USED)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, null, now, NEVER_USED)).isEmpty();
    }

    @Test
    void otpauthUriCarriesTheBase32SecretAndStandardParameters() {
        String uri = Totp.otpauthUri("SaaS Forge", "demo user", RFC_SECRET);
        assertThat(uri)
                .isEqualTo("otpauth://totp/SaaS%20Forge:demo%20user?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                        + "&issuer=SaaS%20Forge&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    void generatedSecretsAreTwentyRandomBytes() {
        byte[] first = Totp.newSecret();
        assertThat(first).hasSize(20);
        assertThat(Totp.newSecret()).isNotEqualTo(first);
    }

    private static OptionalLong match(long codeStep, Instant now) {
        return Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, codeStep), now, NEVER_USED);
    }
}
