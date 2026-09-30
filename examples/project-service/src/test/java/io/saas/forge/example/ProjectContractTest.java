package io.saas.forge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

class ProjectContractTest {
    @Test void formalContractResolvesCommonSchemasAndMatchesStarterRoutes() {
        var options = new ParseOptions();
        options.setResolve(true);
        var parsed = new OpenAPIV3Parser().readLocation("openapi.yaml", null, options);
        assertThat(parsed.getMessages()).isEmpty();
        var api = parsed.getOpenAPI();
        assertThat(api.getPaths()).containsOnlyKeys("/api/v1/projects", "/api/v1/projects/{projectId}",
                "/api/v1/projects/{projectId}/tasks", "/api/v1/projects/{projectId}/tasks/{taskId}");
        var routes = new ProjectConfiguration().projectRoutes().routes();
        assertThat(routes).hasSize(7);
        for (var route : routes) {
            var operation = api.getPaths().get(route.path()).readOperationsMap().entrySet().stream()
                    .filter(entry -> entry.getKey().name().equals(route.method().name())).findFirst().orElseThrow().getValue();
            assertThat(operation.getOperationId()).isEqualTo(route.operationId());
            assertThat(operation.getExtensions().get("x-service-id")).isEqualTo(route.serviceId());
            assertThat(operation.getExtensions().get("x-credential-requirement")).isEqualTo(route.credentialRequirement().name());
        }
        var list = api.getPaths().get("/api/v1/projects").getGet();
        var limit = list.getParameters().stream().filter(parameter -> parameter.getName().equals("limit")).findFirst().orElseThrow().getSchema();
        assertThat(limit.getDefault()).isEqualTo(50);
        assertThat(limit.getMaximum().intValueExact()).isEqualTo(100);
        assertThat(list.getDescription()).contains("id ASC", "Tenant", "limit");
        assertThat(api.getComponents().getSchemas().get("ProjectPage").getRequired())
                .containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        var update = api.getPaths().get("/api/v1/projects/{projectId}").getPut();
        assertThat(update.getResponses()).containsKeys("200", "400", "404", "409", "428");
        assertThat(update.getParameters()).anySatisfy(parameter -> {
            assertThat(parameter.getName()).isEqualTo("If-Match");
            assertThat(parameter.getRequired()).isTrue();
            assertThat(parameter.getSchema().getPattern()).isEqualTo("^\"[1-9][0-9]{0,18}\"$");
        });
        io.swagger.v3.oas.models.media.Schema<?> request = api.getComponents().getSchemas().get("CreateProject");
        assertThat(request.getRequired()).containsExactly("name");
        assertThat(request.getAdditionalProperties()).isEqualTo(false);
        assertThat(request.getProperties().keySet()).containsExactlyInAnyOrder("name", "description");
        assertThat(request.getProperties().get("name").getMaxLength()).isEqualTo(200);
        assertThat(request.getProperties().get("description").getMaxLength()).isEqualTo(2000);
        io.swagger.v3.oas.models.media.Schema<?> resource = api.getComponents().getSchemas().get("Project");
        assertThat(resource.getRequired()).containsExactlyInAnyOrder("id", "name", "description", "version", "createdAt", "updatedAt");
        assertThat(resource.getProperties().get("version").getReadOnly()).isTrue();
        io.swagger.v3.oas.models.media.Schema<?> taskRequest = api.getComponents().getSchemas().get("CreateTask");
        assertThat(taskRequest.getRequired()).containsExactly("title");
        assertThat(taskRequest.getAdditionalProperties()).isEqualTo(false);
        assertThat(taskRequest.getProperties().keySet()).containsExactlyInAnyOrder("title", "description");
        assertThat(taskRequest.getProperties().get("title").getMaxLength()).isEqualTo(200);
        assertThat(taskRequest.getProperties().get("description").getMaxLength()).isEqualTo(2000);
        io.swagger.v3.oas.models.media.Schema<?> task = api.getComponents().getSchemas().get("Task");
        assertThat(task.getRequired()).containsExactlyInAnyOrder("id", "projectId", "title", "description", "status", "version", "createdAt", "updatedAt");
        assertThat(task.getProperties().get("status").getEnum()).containsExactly("TODO", "IN_PROGRESS", "DONE");
        assertThat(task.getProperties().get("version").getReadOnly()).isTrue();
        var taskList = api.getPaths().get("/api/v1/projects/{projectId}/tasks").getGet();
        var taskLimit = taskList.getParameters().stream().filter(parameter -> parameter.getName().equals("limit")).findFirst().orElseThrow().getSchema();
        assertThat(taskLimit.getDefault()).hasToString("50");
        assertThat(taskLimit.getMaximum()).isEqualByComparingTo("100");
        io.swagger.v3.oas.models.media.Schema<?> page = api.getComponents().getSchemas().get("TaskPage");
        assertThat(page.getAllOf().get(0).getRequired()).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(api.getInfo().getDescription()).contains("If-Match", "24 hours", "409 RESOURCE_VERSION_CONFLICT");
    }
}
