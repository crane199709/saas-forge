package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.saasforge.iam.servicecredential.config.ManifestProperties;
import io.saasforge.iam.servicecredential.filter.ServiceTokenFilter;
import io.saasforge.iam.servicecredential.service.ServiceCredentialAuthentication;
import io.saasforge.iam.servicecredential.service.ServiceCredentialFailureAudit;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

/** 多实例即时吊销依赖每次读取权威状态，守护认证入口及其可达的 IAM 协作者。 */
class ServiceCredentialArchitectureTest {
    @Test
    void authenticationPathCannotCacheCredentialsOrAuthenticationResults() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.saasforge.iam");
        var pending = new ArrayDeque<JavaClass>();
        pending.add(classes.get(ServiceTokenFilter.class));
        pending.add(classes.get(ServiceCredentialAuthentication.class));
        Set<String> checked = new HashSet<>();
        while (!pending.isEmpty()) {
            JavaClass type = pending.remove();
            if (!checked.add(type.getName())) {
                continue;
            }
            // 配置只包含已弃用静态引导凭证，失败审计只保存限流键；二者不缓存数据库认证状态。
            if (type.isEquivalentTo(ManifestProperties.class)
                    || type.isEquivalentTo(ServiceCredentialFailureAudit.class)) {
                continue;
            }
            assertThat(type.isAnnotatedWith(Cacheable.class) || type.isMetaAnnotatedWith(Cacheable.class))
                    .as("%s cannot cache authentication", type.getName())
                    .isFalse();
            type.getMethods()
                    .forEach(method -> assertThat(method.isAnnotatedWith(Cacheable.class)
                                    || method.isMetaAnnotatedWith(Cacheable.class))
                            .as("%s cannot cache authentication", method.getFullName())
                            .isFalse());
            type.getFields().forEach(field -> {
                assertThat(field.getRawType().isAssignableTo(Map.class)
                                || field.getRawType().isAssignableTo(Collection.class))
                        .as("%s cannot hold local credential caches", field.getFullName())
                        .isFalse();
            });
            type.getConstructorCallsFromSelf()
                    .forEach(call -> assertThat(call.getTargetOwner().isAssignableTo(Map.class)
                                    || call.getTargetOwner().isAssignableTo(Collection.class))
                            .as("%s cannot construct local credential caches", type.getName())
                            .isFalse());
            type.getDirectDependenciesFromSelf().forEach(dependency -> {
                String name = dependency.getTargetClass().getName();
                for (String prefix : new String[] {
                    "org.springframework.cache.",
                    "org.springframework.data.redis.",
                    "com.github.benmanes.caffeine.",
                    "com.google.common.cache."
                }) {
                    assertThat(name).doesNotStartWith(prefix);
                }
                if (name.startsWith("io.saasforge.iam.")) {
                    pending.add(dependency.getTargetClass());
                }
            });
        }
        assertThat(checked).contains(ServiceCredentialAuthentication.class.getName());
    }
}
