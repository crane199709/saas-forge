package io.saasforge.sdk.manifest;
/** 上报被拒绝或连接失败；公开消息仅包含可安全展示的分类。 */
public final class ManifestReportException extends IllegalStateException {
    private final ManifestFailure reason;
    /** 创建不携带原响应和内部地址的异常。 */
    public ManifestReportException(ManifestFailure reason) {
        super(reason.name());
        this.reason = reason;
    }
    /** 返回失败分类。 */
    public ManifestFailure reason() {
        return reason;
    }
}
