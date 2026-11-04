package io.saasforge.sdk.numbering;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Business Number 的日期段，同时决定计数周期：按年、按月或按天重新从 1 计数。 */
public enum DateSegment {
    /** {@code yyyy}，每年重置。 */
    YEAR("yyyy"),
    /** {@code yyyyMM}，每月重置。 */
    MONTH("yyyyMM"),
    /** {@code yyyyMMdd}，每天重置。 */
    DAY("yyyyMMdd");

    private final DateTimeFormatter formatter;

    DateSegment(String pattern) {
        this.formatter = DateTimeFormatter.ofPattern(pattern);
    }

    /** 按 Business Time Zone 取 {@code instant} 所在的业务日期；结果既是编号中的日期段，也是计数周期。 */
    String format(Instant instant, ZoneId businessZone) {
        return formatter.format(instant.atZone(businessZone));
    }
}
