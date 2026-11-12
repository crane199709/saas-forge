package fixtures.masking;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class OnlineSerializer extends ValueSerializer<String> {
    @Override
    public void serialize(String value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(value);
    }
}
