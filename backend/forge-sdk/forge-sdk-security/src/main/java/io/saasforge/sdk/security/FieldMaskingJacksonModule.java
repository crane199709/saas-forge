package io.saasforge.sdk.security;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/** 仅处理带敏感字段声明的属性；普通 Jackson 遍历同时覆盖嵌套对象与集合。 */
final class FieldMaskingJacksonModule extends SimpleModule {
    private static final Object SNAPSHOT = new Object();

    FieldMaskingJacksonModule(ObjectProvider<FieldMaskingPolicyProvider> provider) {
        setSerializerModifier(new ValueSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(
                    SerializationConfig config, BeanDescription.Supplier bean, List<BeanPropertyWriter> properties) {
                for (var property : properties) {
                    var field = property.getAnnotation(SensitiveField.class);
                    if (field != null) {
                        var serializer = new SensitiveSerializer(field, provider);
                        property.assignSerializer(serializer);
                        property.assignNullSerializer(serializer);
                    }
                }
                return properties;
            }
        });
    }

    private static final class SensitiveSerializer extends ValueSerializer<Object> {
        private final SensitiveField field;
        private final ObjectProvider<FieldMaskingPolicyProvider> provider;
        private final MaskingEngine engine = new MaskingEngine();

        SensitiveSerializer(SensitiveField field, ObjectProvider<FieldMaskingPolicyProvider> provider) {
            this.field = field;
            this.provider = provider;
        }

        @Override
        public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
            @SuppressWarnings("unchecked")
            var snapshot = (Map<String, EffectiveFieldPolicy>) context.getAttribute(SNAPSHOT);
            if (snapshot == null) {
                snapshot = Map.copyOf(
                        provider.getObject().policies(TenantContext.current().tenantId()));
                context.setAttribute(SNAPSHOT, snapshot);
            }
            var policy = snapshot.get(field.path());
            if (policy == null
                    || !field.grade().equals(policy.grade())
                    || (value != null && !(value instanceof String))) {
                throw new IllegalStateException("Online Sensitive Field policy is invalid");
            }
            String masked = engine.apply(policy.rule(), (String) value);
            if (masked == null) generator.writeNull();
            else generator.writeString(masked);
        }
    }
}
