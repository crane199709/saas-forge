package io.saasforge.sdk.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ser.FilterProvider;
import tools.jackson.databind.ser.std.SimpleBeanPropertyFilter;
import tools.jackson.databind.ser.std.SimpleFilterProvider;

/** 在公开 MVC HTTP 转换边界验证既有 JSON 隐藏与声明类型契约。 */
class FieldMaskingSerializationHttpTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc http;

    @BeforeEach
    void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("masking", Map.of("forge.field-masking.enabled", "true")));
        context.register(ConfigurationUnderTest.class);
        context.refresh();
        http = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new))
                .build();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @ParameterizedTest
    @CsvSource({
        "/view,'{\"name\":\"visible\"}'",
        "/filter,'{\"name\":\"visible\"}'",
        "/type,'[{\"kind\":\"dog\",\"name\":\"fido\"}]'"
    })
    void enabledMaskingPreservesTheSelectedConvertersSerializationContext(String path, String expected)
            throws Exception {
        var response =
                http.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(expected);
    }

    @Test
    void preservesMediaSpecificMapperWriterCustomizationAndConverterIdentity() throws Exception {
        var response = http.perform(MockMvcRequestBuilders.get("/mapped").accept("application/vnd.masking-test+json"))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/vnd.masking-test+json");
        assertThat(response.getContentAsString()).isEqualTo("{\"selected\":{\"visible_name\":\"converter\"}}");
    }

    @Test
    void binaryAndStreamingResponsesKeepTheirExistingOutput() throws Exception {
        assertThat(http.perform(MockMvcRequestBuilders.get("/binary"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray())
                .containsExactly(1, 2, 3);
        var streaming = http.perform(MockMvcRequestBuilders.get("/stream")).andReturn();
        assertThat(http.perform(MockMvcRequestBuilders.asyncDispatch(streaming))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray())
                .containsExactly(4, 5, 6);
    }

    @Test
    void deferredJsonResponsesStillCompleteThroughTheSelectedConverter() throws Exception {
        var deferred = http.perform(MockMvcRequestBuilders.get("/deferred")).andReturn();
        var response = http.perform(MockMvcRequestBuilders.asyncDispatch(deferred))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"name\":\"visible\"}");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({FieldMaskingJacksonAutoConfiguration.class, Controller.class, Filters.class})
    static class ConfigurationUnderTest implements WebMvcConfigurer {
        @Bean
        JsonMapper mapper() {
            return JsonMapper.builder()
                    .filterProvider(new SimpleFilterProvider().setFailOnUnknownId(false))
                    .build();
        }

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new ByteArrayHttpMessageConverter());
            var converter = new ContextualConverter(mapper());
            converter.registerMappersForType(
                    MappedSample.class,
                    mappings -> mappings.put(
                            MediaType.parseMediaType("application/vnd.masking-test+json"),
                            JsonMapper.builder()
                                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                                    .build()));
            converters.add(converter);
        }
    }

    static final class ContextualConverter extends JacksonJsonHttpMessageConverter {
        ContextualConverter(JsonMapper mapper) {
            super(mapper);
        }

        @Override
        protected ObjectWriter customizeWriter(ObjectWriter writer, JavaType type, MediaType contentType) {
            return contentType.getSubtype().equals("vnd.masking-test+json") ? writer.withRootName("selected") : writer;
        }
    }

    public record MappedSample(String visibleName) {}

    static class PublicView {}

    static class InternalView extends PublicView {}

    public record Sample(
            @JsonView(PublicView.class) String name,
            @JsonView(InternalView.class) String privateNote) {}

    @JsonFilter("privacy")
    public record FilteredSample(String name, String privateNote) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = Dog.class, name = "dog"))
    interface Animal {}

    public record Dog(String name) implements Animal {}

    @RestController
    static class Controller {
        @GetMapping("/mapped")
        public MappedSample mapped() {
            return new MappedSample("converter");
        }

        @GetMapping(value = "/binary", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public byte[] binary() {
            return new byte[] {1, 2, 3};
        }

        @GetMapping(value = "/stream", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public StreamingResponseBody stream() {
            return output -> output.write(new byte[] {4, 5, 6});
        }

        @GetMapping("/deferred")
        @JsonView(PublicView.class)
        public Callable<Sample> deferred() {
            return () -> new Sample("visible", "hidden");
        }

        @GetMapping("/view")
        @JsonView(PublicView.class)
        public Sample view() {
            return new Sample("visible", "hidden");
        }

        @GetMapping("/filter")
        public FilteredSample filter() {
            return new FilteredSample("visible", "hidden");
        }

        @GetMapping("/type")
        public List<Animal> type() {
            return List.of(new Dog("fido"));
        }
    }

    @RestControllerAdvice
    static class Filters implements ResponseBodyAdvice<Object> {
        @Override
        public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
            return converter == ContextualConverter.class
                    && method.getMethod().getName().equals("filter");
        }

        @Override
        public Object beforeBodyWrite(
                Object body,
                MethodParameter method,
                MediaType type,
                Class<? extends HttpMessageConverter<?>> converter,
                ServerHttpRequest request,
                ServerHttpResponse response) {
            return body;
        }

        @Override
        public Map<String, Object> determineWriteHints(
                Object body,
                MethodParameter method,
                MediaType type,
                Class<? extends HttpMessageConverter<?>> converter) {
            return Map.of(
                    FilterProvider.class.getName(),
                    new SimpleFilterProvider()
                            .addFilter("privacy", SimpleBeanPropertyFilter.filterOutAllExcept("name")));
        }
    }
}
