package io.saasforge.sdk.io;

import io.saasforge.sdk.storage.StoragePurpose;
import java.time.Duration;
import java.util.Set;

/** 导入源文件和生成结果的统一存储规则，业务上传入口须复用 SOURCE。 */
public final class IoStoragePurposes {
    /** 仅接收 xlsx 导入源文件，最多 20 MiB，下载凭证有效期为 5 分钟。 */
    public static final StoragePurpose SOURCE =
            new StoragePurpose("io-source", Set.of("xlsx"), 20L * 1024 * 1024, Duration.ofMinutes(5));

    /** 导出文件与错误报告最多 100 MiB，下载凭证有效期为 5 分钟。 */
    public static final StoragePurpose RESULT =
            new StoragePurpose("io-result", Set.of("xlsx"), 100L * 1024 * 1024, Duration.ofMinutes(5));

    private IoStoragePurposes() {}
}
