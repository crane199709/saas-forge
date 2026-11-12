package io.saasforge.sdk.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.Dictionary;
import io.saasforge.sdk.core.ResourceManifest.DictionaryItem;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Parameter;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ResourceManifestCollectorTest {
    static final String FIXTURES = "io.saasforge.sdk.manifest.fixture";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void scannedCodesAreMergedWithNamesGroupsAndMenusFromTheManifestFile() {
        ResourceManifest manifest = collect("manifests/valid.json");

        assertThat(manifest.service()).isEqualTo("demo-service");
        assertThat(manifest.version()).isEqualTo("1.0.0");
        // 扫描到的编码与清单文件中只给前端按钮用的编码合并，按编码排序；文件未命名的编码以编码本身作名称。
        assertThat(manifest.permissions())
                .containsExactly(
                        new Permission("order:approve", "order:approve", null),
                        new Permission("order:export", "导出订单", "订单"),
                        new Permission("order:read", "查看订单", "订单"));
        assertThat(manifest.menus())
                .containsExactly(new MenuNode(
                        "orders",
                        "订单",
                        "list",
                        null,
                        null,
                        null,
                        List.of(new MenuNode(
                                "orders.overview", "订单概览", null, "demo", "overview", "order:read", null))));
        assertThat(manifest.roles()).containsExactly(new RoleTemplate("order-viewer", "订单查看员", List.of("order:read")));
        assertThat(manifest.dataResources())
                .containsExactly(new DataResource(
                        "order",
                        "订单",
                        "orders",
                        List.of(
                                new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"),
                                new DataAttribute("departmentPath", "归属部门", "ltree", "department_path"))));
        // 数据权限拦截器只需要数据资源，不必扫描权限码
        assertThat(ResourceManifestCollector.dataResources(
                        ResourceManifestCollectorTest.class.getClassLoader(), "manifests/valid.json"))
                .isEqualTo(manifest.dataResources());
    }

    @Test
    void sensitiveFieldsAreIncludedInThePublicManifestContract() {
        ResourceManifest manifest = collect("manifests/sensitive-fields.json");

        assertThat(JSON.readTree(JSON.writeValueAsString(manifest))
                        .path("sensitiveFields")
                        .toString())
                .isEqualTo("[{\"path\":\"customer.name\",\"grade\":\"L2\"},"
                        + "{\"path\":\"customer.phone\",\"grade\":\"L3\",\"defaultRule\":\"phone\"}]");
        assertThat(JSON.writeValueAsString(collect("manifests/valid.json"))).doesNotContain("sensitiveFields");
    }

    @ParameterizedTest
    @CsvSource({"L1", "L2", "L3", "L4"})
    void allFourDataGradesAreAcceptedByThePublicContract(String grade) {
        ResourceManifest manifest = JSON.readValue("""
                {"service":"demo-service","version":"1","permissions":[],"menus":[],
                 "sensitiveFields":[{"path":"customer.phone","grade":"%s"}]}
                """.formatted(grade), ResourceManifest.class);

        manifest.validate();
        assertThat(manifest.sensitiveFields())
                .containsExactly(new ResourceManifest.SensitiveField("customer.phone", grade, null));
    }

    @ParameterizedTest
    @CsvSource({"L0", "L5", "l3", "confidential"})
    void unknownDataGradesAreRejectedAtThePublicContract(String grade) {
        ResourceManifest manifest = JSON.readValue("""
                {"service":"demo-service","version":"1","permissions":[],"menus":[],
                 "sensitiveFields":[{"path":"customer.phone","grade":"%s"}]}
                """.formatted(grade), ResourceManifest.class);

        assertThatThrownBy(manifest::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grade")
                .hasMessageContaining(grade);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "customer..phone",
                ".phone",
                "customer.",
                "customer[0].phone",
                "customer/phone",
                "1phone",
                "customer.phone;DROP"
            })
    void invalidSensitiveFieldPathsAreRejectedAtThePublicContract(String path) {
        ResourceManifest manifest = JSON.readValue("""
                {"service":"demo-service","version":"1","permissions":[],"menus":[],
                 "sensitiveFields":[{"path":"%s","grade":"L3"}]}
                """.formatted(path), ResourceManifest.class);

        assertThatThrownBy(manifest::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void duplicateSensitiveFieldDeclarationsAreRejectedEvenWhenTheyAgree() {
        assertThatThrownBy(() -> collect("manifests/sensitive-fields-duplicate.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate Sensitive Field path: customer.phone");
    }

    @Test
    void annotatedRecordFieldsAndGettersAreMergedWithFileDeclarationsWithoutInitializingClasses() {
        ResourceManifest manifest = ResourceManifestCollector.collect(
                "demo-service",
                "2",
                "io.saasforge.sdk.sensitivefixture.valid",
                getClass().getClassLoader(),
                "manifests/sensitive-fields-extra.json");

        assertThat(manifest.sensitiveFields())
                .extracting(
                        ResourceManifest.SensitiveField::path,
                        ResourceManifest.SensitiveField::grade,
                        ResourceManifest.SensitiveField::defaultRule)
                .containsExactly(
                        tuple("contact.email", "L3", "email"),
                        tuple("customer.name", "L2", null),
                        tuple("customer.phone", "L3", "phone"),
                        tuple("invoice.receiver", "L1", null));
    }

    @ParameterizedTest
    @CsvSource({"duplicate,Duplicate Sensitive Field path", "invalidpath,path", "invalidgrade,grade"})
    void invalidAnnotatedSensitiveFieldsFailManifestGeneration(String fixture, String reason) {
        assertThatThrownBy(() -> ResourceManifestCollector.collect(
                        "demo-service",
                        "2",
                        "io.saasforge.sdk.sensitivefixture." + fixture,
                        getClass().getClassLoader(),
                        "missing.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void annotationAndManifestFileCannotBothDeclareTheSameSensitiveField() {
        assertThatThrownBy(() -> ResourceManifestCollector.collect(
                        "demo-service",
                        "2",
                        "io.saasforge.sdk.sensitivefixture.valid",
                        getClass().getClassLoader(),
                        "manifests/sensitive-fields.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate Sensitive Field path: customer.");
    }

    @Test
    void pageActionsAreCollectedAndPreservedWhenReplacingChildren() {
        ResourceManifest manifest = collect("manifests/actions.json");
        MenuNode page = manifest.menus().get(0);
        assertThat(page.actions()).containsExactly("order:approve");
        assertThat(page.withChildren(List.of()).actions()).containsExactly("order:approve");
        assertThat(JSON.writeValueAsString(page)).contains("\"actions\":[\"order:approve\"]");
        MenuNode legacy =
                collect("manifests/valid.json").menus().get(0).children().get(0);
        assertThat(legacy.actions()).isEmpty();
        assertThat(JSON.writeValueAsString(legacy))
                .isEqualTo(
                        "{\"code\":\"orders.overview\",\"name\":\"订单概览\",\"app\":\"demo\",\"path\":\"overview\",\"permission\":\"order:read\",\"children\":[]}");
    }

    @ParameterizedTest
    @CsvSource({"action-group,leaf", "action-unknown,order:typo", "action-duplicate,Duplicate", "action-page,permission"
    })
    void invalidPageActionsAreRejected(String fixture, String reason) {
        assertThatThrownBy(() -> collect("manifests/" + fixture + ".json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void dictionariesAndParametersAreCollectedFromTheManifestFile() {
        ResourceManifest manifest = collect("manifests/reference-data.json");

        assertThat(manifest.dictionaries())
                .containsExactly(
                        new Dictionary(
                                "customer-source",
                                "客户来源",
                                true,
                                List.of(new DictionaryItem("online", "线上"), new DictionaryItem("referral", "转介绍"))),
                        new Dictionary("order-status", "订单状态", false, List.of(new DictionaryItem("open", "未完成"))));
        assertThat(manifest.parameters())
                .extracting(Parameter::code, Parameter::type, Parameter::tenantEditable)
                .containsExactly(
                        tuple("order.auto-close", "duration", true),
                        tuple("order.remark", "string", false),
                        tuple("order.max-items", "integer", true),
                        tuple("order.discount", "decimal", false),
                        tuple("order.invoice", "boolean", false),
                        tuple("customer.default-source", "dictionary", true));
        assertThat(JSON.writeValueAsString(manifest))
                .contains(
                        "{\"code\":\"order.auto-close\",\"name\":\"未支付订单自动关闭时长\",\"type\":\"duration\","
                                + "\"default\":\"PT30M\",\"min\":\"PT5M\",\"max\":\"P3D\",\"tenantEditable\":true}",
                        "\"default\":50,\"min\":1,\"max\":200",
                        "\"default\":0.95,\"min\":0,\"max\":1",
                        "\"dictionary\":\"customer-source\",\"default\":\"online\"");
        // 没有声明字典与参数的服务输出与加入本节前相同，已存储的清单按内容比较不变，无需升版本。
        assertThat(JSON.writeValueAsString(collect("manifests/valid.json")))
                .doesNotContain("dictionaries")
                .doesNotContain("parameters");
    }

    // iam 接收上报时执行同一套规则，ResourceManifestTest 用同样的违规清单覆盖接收一侧。
    @ParameterizedTest
    @CsvSource({
        "dictionary-duplicate-code,Duplicate Dictionary code: customer-source",
        "dictionary-invalid-code,Invalid Dictionary code: Customer:Source",
        "dictionary-item-custom-prefix,custom.vip",
        "dictionary-item-duplicate-code,Duplicate item code in Dictionary customer-source: online",
        "dictionary-item-duplicate-name,Duplicate item name in Dictionary customer-source: 线上",
        "parameter-duplicate-code,Duplicate Parameter code: order.p",
        "parameter-unknown-type,Unknown type of Parameter order.p: date",
        "parameter-missing-default,Parameter order.p has no default",
        "parameter-string-too-long,longer than maxLength",
        "parameter-integer-below-min,less than min",
        "parameter-integer-not-integral,is not a valid integer",
        "parameter-decimal-above-max,greater than max",
        "parameter-duration-above-max,greater than max",
        "parameter-duration-invalid,30 minutes",
        "parameter-boolean-not-boolean,is not a valid boolean",
        "parameter-min-above-max,min greater than max",
        "parameter-constraint-not-applicable,maxLength is only for the string type",
        "parameter-dictionary-unknown,undeclared Dictionary: customer-sources",
        "parameter-dictionary-missing,undeclared Dictionary: null",
        "parameter-dictionary-default-not-item,offline",
        "parameter-dictionary-on-other-type,dictionary is only for the dictionary type"
    })
    void invalidDictionariesAndParametersAreRejected(String fixture, String reason) {
        assertThatThrownBy(() -> collect("manifests/" + fixture + ".json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void missingManifestFileYieldsScannedCodesAndNoMenus() {
        ResourceManifest manifest = collect("manifests/absent.json");

        assertThat(manifest.permissions()).extracting(Permission::code).containsExactly("order:approve", "order:read");
        assertThat(manifest.menus()).isEmpty();
    }

    @Test
    void platformRealmIsReadFromTheManifestFileAndOmittedForTenantRealmServices() {
        ResourceManifest platform = collect("manifests/platform.json");
        assertThat(platform.realm()).isEqualTo(ResourceManifest.PLATFORM_REALM);
        assertThat(platform.platform()).isTrue();
        assertThat(JSON.writeValueAsString(platform)).contains("\"realm\":\"platform\"");

        // 已存储的 Tenant Realm 清单不带 realm，新生成的清单按内容比较仍与之相同
        ResourceManifest tenant = collect("manifests/valid.json");
        assertThat(tenant.realm()).isNull();
        assertThat(JSON.writeValueAsString(tenant)).doesNotContain("realm").doesNotContain("platform");
        assertThat(new ResourceManifest("s", "1", List.of(), List.of(), List.of(), List.of(), "tenant").realm())
                .isNull();

        assertThatThrownBy(() -> collect("manifests/unknown-realm.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("realm");
        assertThatThrownBy(() -> collect("manifests/platform-role-template.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("role templates");
    }

    @Test
    void menuReferringToAnUndeclaredPermissionIsRejected() {
        assertThatThrownBy(() -> collect("manifests/unknown-menu-permission.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order:typo");
    }

    @Test
    void menuGroupCannotRequireAPermissionCode() {
        assertThatThrownBy(() -> collect("manifests/permission-group.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Menu group group")
                .hasMessageContaining("order:approve");
    }

    @Test
    void menusAreLimitedToThreeLevels() {
        assertThat(collect("manifests/menu-three-levels.json").menus()).hasSize(1);
        assertThatThrownBy(() -> collect("manifests/menu-four-levels.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("l4");
    }

    @Test
    void roleTemplatesMustGrantDeclaredCodesAndCannotTakeBuiltinRoleCodes() {
        assertThatThrownBy(() -> collect("manifests/unknown-role-permission.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order:typo");
        assertThatThrownBy(() -> collect("manifests/reserved-role-code.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-admin");
    }

    @Test
    void dataAttributesMustHaveAKnownTypeAndAPlainColumnName() {
        assertThatThrownBy(() -> collect("manifests/invalid-data-attribute-type.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("date");
        // 列名会进入 SQL，不能借清单注入
        assertThatThrownBy(() -> collect("manifests/invalid-data-column.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DROP TABLE");
        assertThatThrownBy(() -> ResourceManifestCollector.dataResources(
                        ResourceManifestCollectorTest.class.getClassLoader(), "manifests/invalid-data-column.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ResourceManifest collect(String location) {
        return ResourceManifestCollector.collect(
                "demo-service", "1.0.0", FIXTURES, ResourceManifestCollectorTest.class.getClassLoader(), location);
    }
}
