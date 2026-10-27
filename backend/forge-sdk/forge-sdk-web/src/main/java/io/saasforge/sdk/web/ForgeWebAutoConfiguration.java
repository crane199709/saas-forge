package io.saasforge.sdk.web;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Servlet 服务的响应信封：traceId 填充、异常映射与分页参数解析。排在 MVC 与错误页自动配置之前，才能让本模块的 LocaleResolver 与
 * {@code /error} 处理取代 Boot 的默认实现。
 */
@AutoConfiguration(before = {WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ForgeWebAutoConfiguration {

    /**
     * 按 {@code Accept-Language} 解析请求语言，缺省为 zh-CN；Bean Validation 的默认消息与 {@code userTip} 都使用该语言。
     *
     * @return LocaleResolver，服务自行定义同名 Bean 时让位
     */
    @Bean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(ErrorEnvelopes.DEFAULT_LOCALE);
        return resolver;
    }

    /**
     * 按错误码生成 {@code userTip}。
     *
     * @param messageSource 服务的 MessageSource
     * @return 文案解析器
     */
    @Bean
    @ConditionalOnMissingBean
    UserTipResolver userTipResolver(MessageSource messageSource) {
        return new UserTipResolver(messageSource);
    }

    /**
     * 把业务与框架异常映射为失败信封。
     *
     * @param userTips 文案解析器
     * @return 全局异常处理
     */
    @Bean
    ApiExceptionHandler forgeApiExceptionHandler(UserTipResolver userTips) {
        return new ApiExceptionHandler(userTips);
    }

    /**
     * 过滤器层抛出或 {@code sendError} 的错误由容器转发到 {@code /error}，取代 Boot 的默认错误响应，同样输出信封。
     *
     * @param userTips 文案解析器
     * @return 错误页 Controller，应用自定义 ErrorController 时让位
     */
    @Bean
    @ConditionalOnMissingBean(ErrorController.class)
    EnvelopeErrorController forgeErrorController(UserTipResolver userTips) {
        return new EnvelopeErrorController(userTips);
    }

    /**
     * 在 Spring MVC 之外（如安全过滤链）写出失败信封。
     *
     * @param userTips 文案解析器
     * @param tracer Micrometer Tracer，关闭 tracing 时不存在
     * @return 信封写出器，应用自定义同类型 Bean 时让位
     */
    @Bean
    @ConditionalOnMissingBean
    EnvelopeErrorWriter forgeEnvelopeErrorWriter(UserTipResolver userTips, ObjectProvider<Tracer> tracer) {
        return new EnvelopeErrorWriter(userTips, tracer);
    }

    /**
     * 为信封填入 traceId。
     *
     * @param tracer Micrometer Tracer，关闭 tracing 时不存在，信封不输出 traceId
     * @return 响应 Advice
     */
    @Bean
    EnvelopeResponseAdvice forgeEnvelopeResponseAdvice(ObjectProvider<Tracer> tracer) {
        return new EnvelopeResponseAdvice(tracer);
    }

    /**
     * 注册 {@link PageQuery} 参数解析。
     *
     * @return MVC 配置
     */
    @Bean
    WebMvcConfigurer forgePageQueryConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new PageQueryArgumentResolver());
            }
        };
    }

    /** 服务引入 springdoc 时，让文档如实反映分页参数。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springdoc.core.customizers.OperationCustomizer")
    static class OpenApiConfiguration {

        /**
         * 文档化 {@link PageQuery} 对应的 query 参数。
         *
         * @return springdoc 接口定制
         */
        @Bean
        PageQueryOpenApiCustomizer forgePageQueryOpenApiCustomizer() {
            // 不让 springdoc 把 PageQuery 当成名为 query 的对象参数，实际参数由定制器补充
            SpringDocUtils.getConfig().addRequestWrapperToIgnore(PageQuery.class);
            return new PageQueryOpenApiCustomizer();
        }
    }
}
