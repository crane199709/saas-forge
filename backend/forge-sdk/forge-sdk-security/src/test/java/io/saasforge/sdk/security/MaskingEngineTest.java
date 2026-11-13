package io.saasforge.sdk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.api.ForgeException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class MaskingEngineTest {
    private final MaskingEngine engine = new MaskingEngine();

    @Test
    void masksMiddleKeepingTheFirstAndLastCharacter() {
        assertThat(engine.apply(new MaskingRule("mask_middle", null, null), "abcdef"))
                .isEqualTo("a****f");
    }

    @Test
    void keepsOnlyTheLastFourCharacters() {
        assertThat(engine.apply(new MaskingRule("mask_tail4", null, null), "1234567890"))
                .isEqualTo("******7890");
    }

    @Test
    void keepsTheConfiguredNumberOfLeadingCharacters() {
        assertThat(engine.apply(new MaskingRule("keepFirst", 2, null), "abcdef"))
                .isEqualTo("ab****");
    }

    @Test
    void replacesTheEntireValueWithTheConfiguredText() {
        assertThat(engine.apply(new MaskingRule("replace", null, "[hidden]"), "secret"))
                .isEqualTo("[hidden]");
    }

    @Test
    void masksTheEmailLocalPartAndPreservesItsDomain() {
        assertThat(engine.apply(new MaskingRule("email", null, null), "alice@example.com"))
                .isEqualTo("a****@example.com");
    }

    @Test
    void masksPhoneNumbersKeepingTheFirstThreeAndLastFourDigits() {
        assertThat(engine.apply(new MaskingRule("phone", null, null), "13812345678"))
                .isEqualTo("138****5678");
    }

    @Test
    void masksIdentityCardsKeepingTheFirstSixAndLastFourCharacters() {
        assertThat(engine.apply(new MaskingRule("idcard", null, null), "110101199001011234"))
                .isEqualTo("110101********1234");
    }

    @Test
    void masksBankCardsKeepingTheFirstAndLastFourCharacters() {
        assertThat(engine.apply(new MaskingRule("bankcard", null, null), "6222021234567890"))
                .isEqualTo("6222********7890");
    }

    @Test
    void masksNamesKeepingTheFirstCharacter() {
        assertThat(engine.apply(new MaskingRule("name", null, null), "张小明")).isEqualTo("张**");
    }

    @Test
    void rejectsUnknownStrategiesAtValidationAndExecution() {
        MaskingRule rule = new MaskingRule("regex:.*", null, null);
        assertThatThrownBy(() -> engine.validate(rule))
                .isInstanceOf(ForgeException.class)
                .hasMessage("field-masking.unknownStrategy");
        assertThatThrownBy(() -> engine.apply(rule, "private-value"))
                .isInstanceOf(ForgeException.class)
                .hasMessage("field-masking.unknownStrategy");
    }

    @ParameterizedTest
    @MethodSource("invalidRules")
    void rejectsMissingAndUnexpectedParameters(MaskingRule rule) {
        assertThatThrownBy(() -> engine.validate(rule))
                .isInstanceOf(ForgeException.class)
                .hasMessage("field-masking.invalidRule");
        assertThatThrownBy(() -> engine.apply(rule, null))
                .isInstanceOf(ForgeException.class)
                .hasMessage("field-masking.invalidRule");
    }

    static Stream<MaskingRule> invalidRules() {
        return Stream.of(
                null,
                new MaskingRule(null, null, null),
                new MaskingRule("", null, null),
                new MaskingRule("keepFirst", null, null),
                new MaskingRule("keepFirst", -1, null),
                new MaskingRule("keepFirst", 1, "expression"),
                new MaskingRule("replace", null, null),
                new MaskingRule("replace", null, ""),
                new MaskingRule("replace", null, "  "),
                new MaskingRule("replace", 1, "hidden"),
                new MaskingRule("phone", 1, null),
                new MaskingRule("phone", null, "hidden"));
    }

    @ParameterizedTest
    @MethodSource("validRules")
    void preservesNullAndEmptyValuesAfterValidatingTheRule(MaskingRule rule) {
        assertThat(engine.apply(rule, null)).isNull();
        assertThat(engine.apply(rule, "")).isEmpty();
    }

    static Stream<MaskingRule> validRules() {
        return Stream.of(
                new MaskingRule("mask_middle", null, null),
                new MaskingRule("mask_tail4", null, null),
                new MaskingRule("keepFirst", 1, null),
                new MaskingRule("replace", null, "[hidden]"),
                new MaskingRule("email", null, null),
                new MaskingRule("phone", null, null),
                new MaskingRule("idcard", null, null),
                new MaskingRule("bankcard", null, null),
                new MaskingRule("name", null, null));
    }

    @ParameterizedTest
    @CsvSource({
        "mask_middle,a,*",
        "mask_middle,ab,**",
        "mask_tail4,1234,****",
        "phone,1234567,*******",
        "idcard,1234567890,**********",
        "bankcard,12345678,********",
        "name,张,*",
        "email,a@example.com,*@example.com",
        "email,invalid,*******",
        "email,@example.com,************",
        "email,a@@b,****",
        "email,a@,**"
    })
    void fullyMasksShortOrMalformedValuesInsteadOfReturningOriginalText(
            String strategy, String input, String expected) {
        assertThat(engine.apply(new MaskingRule(strategy, null, null), input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("validRules")
    void refusesAnUnchangedNonEmptyResult(MaskingRule rule) {
        String value = "replace".equals(rule.strategy()) ? "[hidden]" : "*";
        assertThatThrownBy(() -> engine.apply(rule, value))
                .isInstanceOf(ForgeException.class)
                .hasMessage("field-masking.unchanged")
                .hasNoCause();
    }

    @Test
    void refusesKeepingAnEntireShortValueWithoutLeakingItsText() {
        assertThatThrownBy(() -> engine.apply(new MaskingRule("keepFirst", 99, null), "private-value"))
                .isInstanceOf(ForgeException.class)
                .hasMessage("field-masking.unchanged")
                .hasNoCause()
                .satisfies(error -> assertThat(((ForgeException) error).args()).isEmpty());
    }

    @Test
    void countsUnicodeCodePointsWithoutSplittingSurrogatePairs() {
        assertThat(engine.apply(new MaskingRule("name", null, null), "𠮷田太郎")).isEqualTo("𠮷***");
        assertThat(engine.apply(new MaskingRule("mask_middle", null, null), "😀小明🐱"))
                .isEqualTo("😀**🐱");
        assertThat(engine.apply(new MaskingRule("keepFirst", 1, null), "😀小明")).isEqualTo("😀**");
    }

    @Test
    void previewsTheSameExecutionAndReturnsOnlySafeFailureReasons() {
        assertThat(engine.preview(new MaskingRule("phone", null, null), "13812345678"))
                .isEqualTo(new MaskingPreview(true, "138****5678", null, null));
        MaskingPreview unchanged = engine.preview(new MaskingRule("keepFirst", 99, null), "private-value");
        assertThat(unchanged.success()).isFalse();
        assertThat(unchanged.value()).isNull();
        assertThat(unchanged.errorCode()).isEqualTo("field-masking.unchanged");
        assertThat(unchanged.errorMessage()).isEqualTo("Masking did not change the value");
        assertThat(unchanged.toString()).doesNotContain("private-value");
        MaskingPreview invalid = engine.preview(new MaskingRule("replace", null, ""), "private-value");
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.value()).isNull();
        assertThat(invalid.errorCode()).isEqualTo("field-masking.invalidRule");
        assertThat(invalid.errorMessage()).isEqualTo("Invalid masking rule parameters");
    }
}
