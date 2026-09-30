package io.saas.forge.example;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record CreateTaskRequest(@NotBlank @CodePointLength(max = 200) @Pattern(regexp = "[^\\x00]*") String title,
                                   @CodePointLength(max = 2000) @Pattern(regexp = "[^\\x00]*") String description) {}
