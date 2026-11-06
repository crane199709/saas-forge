package io.saasforge.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** 模块测试继承此类即可执行共享规则；只导入生产类。 */
public abstract class ForgeArchitectureTest {
    /** 校验当前模块及其可见 SDK 依赖。 */
    @Test
    public final void architecture() {
        String root = getClass().getPackageName();
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(root, "io.saasforge.sdk");
        ForgeArchitectureRules.persistence().check(classes);
        ForgeArchitectureRules.scheduling().check(classes);
        ForgeArchitectureRules.sdkDependencies().check(classes);
        ForgeArchitectureRules.sdkCycles().check(classes);
        ForgeArchitectureRules.iamMappers().check(classes);
        ForgeArchitectureRules.apiResponses().check(classes);
        if (!root.startsWith("io.saasforge.sdk.")) {
            ForgeArchitectureRules.serviceStructure(root).check(classes);
            ForgeArchitectureRules.featureCycles(root).check(classes);
        }
    }
}
