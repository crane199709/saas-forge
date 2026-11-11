package io.saasforge.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
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
        ForgeArchitectureRules.persistence(externalJdbcClasses()).check(classes);
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

    /**
     * 允许依赖 JDBC 的类全限定名（含其内部类），默认为空。
     *
     * <p>只用于代 Tenant 连接不属于平台的外部数据库：这类库没有租户列，MyBatis-Plus 拦截器不适用。
     * 服务自己的库仍须经 MyBatis-Plus，覆盖时在旁注释写明每个类连接的是哪类外部库。
     */
    protected Set<String> externalJdbcClasses() {
        return Set.of();
    }
}
