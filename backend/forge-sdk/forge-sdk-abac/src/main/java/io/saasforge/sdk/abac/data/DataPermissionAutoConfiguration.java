package io.saasforge.sdk.abac.data;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import io.saasforge.sdk.abac.DataResourceDefinition;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import io.saasforge.sdk.security.MembershipGrantStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 按清单文件中声明的 Data Resource 启用数据权限：提供 {@link DataPermission}，并注册注入数据权限条件的 MyBatis-Plus 拦截器。
 *
 * <p>只在同时引入 MyBatis-Plus、forge-sdk-security 与 forge-sdk-manifest 的业务服务中生效；只用策略引擎的模块（如 iam）不受影响。
 * 没有 Redis 明细来源时成员请求访问数据资源以 500 失败，与 {@code @RequiresPermission} 一致；系统操作不受影响。
 */
@AutoConfiguration(afterName = "io.saasforge.sdk.security.MembershipGrantsAutoConfiguration")
@ConditionalOnClass(
        name = {
            "com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor",
            "io.saasforge.sdk.security.MembershipGrantStore",
            "io.saasforge.sdk.manifest.ResourceManifestCollector"
        })
public final class DataPermissionAutoConfiguration {
    /**
     * 读取 {@value ResourceManifestCollector#MANIFEST_LOCATION} 中的数据资源；清单不合法时启动失败。
     *
     * @param grants Membership 明细读取器；服务未配置 Redis 时不存在
     * @param context 取类加载器读取清单文件
     * @return 当前服务的数据权限
     */
    @Bean
    @ConditionalOnMissingBean
    public DataPermission dataPermission(ObjectProvider<MembershipGrantStore> grants, ApplicationContext context) {
        var resources = ResourceManifestCollector.dataResources(context.getClassLoader()).stream()
                .map(DataResourceDefinition::of)
                .toList();
        return new DataPermission(resources, grants.getIfAvailable());
    }

    /**
     * 独立于租户拦截器的插件：MyBatis-Plus 自动配置会收集所有拦截器 Bean，两者对同一语句的改写互不干扰。
     *
     * @param permission 当前服务的数据权限
     * @return 只含数据权限拦截的插件
     */
    @Bean
    public MybatisPlusInterceptor dataPermissionMybatisPlusInterceptor(DataPermission permission) {
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInnerInterceptor(permission));
        return interceptor;
    }
}
