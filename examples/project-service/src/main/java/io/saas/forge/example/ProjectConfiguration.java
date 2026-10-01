package io.saas.forge.example;

import io.saas.forge.contracts.route.HttpRouteCatalog;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProjectConfiguration {
    @Bean
    org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictTextFields() {
        return builder -> builder.withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Integer, tools.jackson.databind.cfg.CoercionAction.Fail);
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionAction.Fail);
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Boolean, tools.jackson.databind.cfg.CoercionAction.Fail);
        });
    }

    /** Example 自有契约不加入底座 Gateway Catalog；契约测试核对正式入口。 */
    @Bean
    HttpRouteCatalog projectRoutes() {
        return new HttpRouteCatalog(1, List.of(
                new HttpRouteCatalog.Route("createProject", HttpRouteCatalog.HttpMethod.POST, "/api/v1/projects",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("listProjects", HttpRouteCatalog.HttpMethod.GET, "/api/v1/projects",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("updateProject", HttpRouteCatalog.HttpMethod.PUT, "/api/v1/projects/{projectId}",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("deleteProject", HttpRouteCatalog.HttpMethod.DELETE, "/api/v1/projects/{projectId}",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("getProject", HttpRouteCatalog.HttpMethod.GET, "/api/v1/projects/{projectId}",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("createTask", HttpRouteCatalog.HttpMethod.POST, "/api/v1/projects/{projectId}/tasks",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("listTasks", HttpRouteCatalog.HttpMethod.GET, "/api/v1/projects/{projectId}/tasks",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("updateTask", HttpRouteCatalog.HttpMethod.PUT, "/api/v1/projects/{projectId}/tasks/{taskId}",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("deleteTask", HttpRouteCatalog.HttpMethod.DELETE, "/api/v1/projects/{projectId}/tasks/{taskId}",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                new HttpRouteCatalog.Route("getTask", HttpRouteCatalog.HttpMethod.GET, "/api/v1/projects/{projectId}/tasks/{taskId}",
                        "project-service", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of())));
    }
}
