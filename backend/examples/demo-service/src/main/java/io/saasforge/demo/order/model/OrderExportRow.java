package io.saasforge.demo.order.model;

import io.saasforge.sdk.io.ExcelColumn;
import io.saasforge.sdk.referencedata.DictionaryValue;
import java.math.BigDecimal;

public record OrderExportRow(
        @ExcelColumn(value = "订单编号", order = 0) String number,
        @ExcelColumn(value = "标题", order = 1) String title,
        @ExcelColumn(value = "金额", order = 2) BigDecimal amount,

        @ExcelColumn(value = "客户来源", order = 3) @DictionaryValue("customer-source")
        String customerSource) {}
