package io.saasforge.sdk.manifest.fixture;

import io.saasforge.sdk.security.RequiresPermission;

// 扫描用的样例接口：类上与方法上的声明都要收集。
@RequiresPermission("order:read")
public class OrderEndpoints {
    public void list() {}

    @RequiresPermission("order:approve")
    public void approve() {}

    @RequiresPermission("order:read")
    public void detail() {}
}
