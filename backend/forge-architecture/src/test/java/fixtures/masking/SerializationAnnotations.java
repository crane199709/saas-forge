package fixtures.masking;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public class SerializationAnnotations {
    public record Custom(
            @JsonSerialize(using = ToStringSerializer.class) String phone) {}

    public record Formatting(
            @JsonSerialize(typing = JsonSerialize.Typing.STATIC)
            String title) {}
}
