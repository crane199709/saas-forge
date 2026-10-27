package io.saasforge.sdk.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明调用该接口所需的 Permission Code。当前 Membership 的权限中没有该编码时返回 403 {@code auth.forbidden}。
 *
 * <p>标在类上时对类中全部方法生效，方法上的声明优先。forge-sdk-manifest 扫描本注解收集服务的 Permission Code，
 * 名称与分组在清单文件中补充。Platform Realm 服务暂不支持，标注的接口一律拒绝。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {
    /**
     * 所需的 Permission Code。
     *
     * @return 权限码，如 {@code order:approve}
     */
    String value();
}
