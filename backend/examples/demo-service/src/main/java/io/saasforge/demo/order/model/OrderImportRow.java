package io.saasforge.demo.order.model;

import io.saasforge.sdk.io.ExcelColumn;
import io.saasforge.sdk.referencedata.DictionaryValue;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record OrderImportRow(
        @ExcelColumn(value = "标题", order = 0, required = true) @NotBlank @Size(max = 200)
        String title,

        @ExcelColumn(value = "金额", order = 1, required = true)
        @NotNull
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2)
        BigDecimal amount,

        @ExcelColumn(value = "客户来源", order = 2) @DictionaryValue("customer-source")
        String customerSource) {}
