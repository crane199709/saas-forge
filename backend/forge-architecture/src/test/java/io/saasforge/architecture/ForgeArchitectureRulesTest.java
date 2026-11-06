package io.saasforge.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import fixtures.sql.BadSelect;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ForgeArchitectureRulesTest {
    @ParameterizedTest
    @MethodSource("examples")
    void detectsViolationsAndAcceptsAllowedExamples(ArchRule rule, String name, boolean allowed) throws Exception {
        var result = rule.evaluate(new ClassFileImporter().importClasses(Class.forName(name)));
        assertThat(result.hasViolation())
                .as("%s: %s", name, result.getFailureReport())
                .isEqualTo(!allowed);
    }

    @Test
    void rejectsPackageAndFeatureCycles() {
        var importer = new ClassFileImporter();
        assertThat(ForgeArchitectureRules.sdkCycles()
                        .evaluate(importer.importPackages("io.saasforge.sdk.cycletest"))
                        .hasViolation())
                .isTrue();
        assertThat(ForgeArchitectureRules.featureCycles("fixtures.cycle")
                        .evaluate(importer.importPackages("fixtures.cycle"))
                        .hasViolation())
                .isTrue();
    }

    @Test
    void selectFragmentIsRejectedIndependentlyOfStringWrapper() {
        var result =
                ForgeArchitectureRules.persistence().evaluate(new ClassFileImporter().importClasses(BadSelect.class));
        assertThat(result.getFailureReport().toString()).contains("SQL 片段");
    }

    static Stream<Arguments> examples() {
        return Stream.of(
                Arguments.of(ForgeArchitectureRules.scheduling(), "fixtures.scheduling.DirectScheduled", false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$EverySecond",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$RepeatedEverySecond",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$DirectEnable",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$ComposedEnable",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$ComposedScheduled",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$RepeatedScheduled",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$ContainerScheduled",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(),
                        "fixtures.scheduling.SchedulingExamples$ComposedContainerScheduled",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.scheduling(), "fixtures.scheduling.SchedulingExamples$PlainTask", true),
                Arguments.of(ForgeArchitectureRules.scheduling(), "io.saasforge.sdk.fixture.AllowedScheduling", true),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadReference", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadSelectAnnotation", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadInsert", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadUpdate", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadDelete", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadSelectProvider", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadInsertProvider", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadUpdateProvider", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadDeleteProvider", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadQueryWrapper", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadUpdateWrapper", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.Badapply", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.Badlast", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadinSql", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadnotInSql", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.Badexists", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadnotExists", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.Badhaving", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadSetSql", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadSelect", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadConnection", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadStatement", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadPreparedStatement", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadCallableStatement", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadDriverManager", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadJdbcTemplate", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.BadNamedParameterJdbcTemplate", false),
                Arguments.of(ForgeArchitectureRules.persistence(), "fixtures.sql.Allowed", true),
                Arguments.of(ForgeArchitectureRules.persistence(), "io.saasforge.sdk.fixture.Infrastructure", true),
                Arguments.of(
                        ForgeArchitectureRules.iamMappers(), "io.saasforge.iam.fixture.mapper.MissingIgnore", false),
                Arguments.of(
                        ForgeArchitectureRules.iamMappers(), "io.saasforge.iam.fixture.mapper.AllowedMapper", true),
                Arguments.of(
                        ForgeArchitectureRules.serviceStructure("fixtures.service"),
                        "fixtures.service.feature.invalid.BadLayer",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.serviceStructure("fixtures.service"),
                        "fixtures.service.feature.service.AllowedService",
                        true),
                Arguments.of(
                        ForgeArchitectureRules.serviceStructure("fixtures.service"),
                        "fixtures.service.feature.mapper.LocalMapper",
                        true),
                Arguments.of(
                        ForgeArchitectureRules.serviceStructure("fixtures.service"),
                        "fixtures.service.feature.controller.BadController",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.serviceStructure("fixtures.service"),
                        "fixtures.service.other.service.BadCrossMapper",
                        false),
                Arguments.of(
                        ForgeArchitectureRules.serviceStructure("fixtures.service"),
                        "fixtures.service.other.controller.BadCrossService",
                        false),
                Arguments.of(ForgeArchitectureRules.sdkDependencies(), "io.saasforge.sdk.core.fixture.BadCore", false),
                Arguments.of(ForgeArchitectureRules.sdkDependencies(), "io.saasforge.sdk.fixture.BadSdk", false),
                Arguments.of(
                        ForgeArchitectureRules.apiResponses(),
                        "fixtures.service.feature.controller.BadResponse",
                        false));
    }
}
