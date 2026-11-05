package io.saasforge.iam.password.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.saasforge.iam.password.config.PasswordProperties;
import io.saasforge.sdk.core.api.ForgeException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy(
            new PasswordProperties(new PasswordProperties.Realm(8), new PasswordProperties.Realm(12)));

    @Test
    void acceptsLongEnoughUncommonPasswordWithoutCharacterClassRules() {
        assertThatCode(() -> policy.checkIdentity("correct horse battery staple", "alice", "alice@example.com"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.checkIdentity("lowercaseonlyphrase", "alice"))
                .doesNotThrowAnyException();
    }

    @Test
    void lengthIsBetweenTheRealmMinimumAndSeventyTwoBytes() {
        assertThat(code(() -> policy.checkIdentity("Ab3$xyz", "alice"))).isEqualTo("password.tooShort");
        assertThat(code(() -> policy.checkIdentity("x".repeat(73), "alice"))).isEqualTo("password.tooLong");
        assertThatCode(() -> policy.checkIdentity("x".repeat(71) + "y", "alice"))
                .doesNotThrowAnyException();
        // BCrypt 只取前 72 字节：多字节字符按字节计长度，25 个汉字为 75 字节。
        assertThat(code(() -> policy.checkIdentity("密".repeat(25), "alice"))).isEqualTo("password.tooLong");
        // Platform Realm 的下限配置为 12。
        assertThat(code(() -> policy.checkOperator("Ab3$xyz-9q1", "ops"))).isEqualTo("password.tooShort");
        assertThatCode(() -> policy.checkOperator("Ab3$xyz-9q1Z", "ops")).doesNotThrowAnyException();
    }

    @Test
    void rejectsPasswordsFromTheBuiltInWeakListIgnoringCase() {
        assertThat(code(() -> policy.checkIdentity("password", "alice"))).isEqualTo("password.tooCommon");
        assertThat(code(() -> policy.checkIdentity("Iloveyou", "alice"))).isEqualTo("password.tooCommon");
        assertThat(code(() -> policy.checkOperator("1QAZ2WSX3EDC", "ops"))).isEqualTo("password.tooCommon");
    }

    @Test
    void rejectsPasswordEqualToAnyLoginIdentifierIgnoringCase() {
        assertThat(code(() -> policy.checkIdentity("Alice-the-great", "alice-the-great", null)))
                .isEqualTo("password.sameAsIdentifier");
        assertThat(code(() -> policy.checkIdentity("ALICE@example.com", "alice", "alice@example.com")))
                .isEqualTo("password.sameAsIdentifier");
        assertThat(code(() -> policy.checkIdentity("13800000000", null, "13800000000")))
                .isEqualTo("password.sameAsIdentifier");
        assertThat(code(() -> policy.checkOperator("east-operator", "east-operator")))
                .isEqualTo("password.sameAsIdentifier");
    }

    @Test
    void minimumLengthOutsideEightToSeventyTwoFailsStartup() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.run(context -> {
            var properties = context.getBean(PasswordProperties.class);
            assertThat(properties.tenant().minLength()).isEqualTo(8);
            assertThat(properties.platform().minLength()).isEqualTo(8);
        });
        for (String value : new String[] {"7", "73"}) {
            runner.withPropertyValues("forge.iam.password.platform.min-length=" + value)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("min-length"));
        }
    }

    private static String code(Runnable check) {
        ForgeException error = catchThrowableOfType(ForgeException.class, check::run);
        assertThat(error).isNotNull();
        assertThat(error.errorCode().httpStatus()).isEqualTo(400);
        return error.errorCode().code();
    }

    @Configuration
    @EnableConfigurationProperties(PasswordProperties.class)
    static class Binding {}
}
