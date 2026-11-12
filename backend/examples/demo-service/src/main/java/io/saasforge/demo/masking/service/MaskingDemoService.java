package io.saasforge.demo.masking.service;

import io.saasforge.demo.masking.model.MaskedProfile;
import io.saasforge.demo.masking.model.RawProfile;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.security.RequiresPermission;
import java.util.List;
import org.springframework.stereotype.Service;

/** 固定虚构样例演示敏感字段的登记及独立原文操作，不读写真实客户数据。 */
@Service
public class MaskingDemoService {
    public List<MaskedProfile> profiles() {
        return List.of(new MaskedProfile("13812345678", "110101199001011234", "公开说明"));
    }

    @RequiresPermission("masking:viewRaw")
    @AuditLog(action = "profile.viewRaw", resourceType = "profile", resourceId = "'demo'")
    public RawProfile viewRaw() {
        var profile = profiles().get(0);
        return new RawProfile(profile.phone(), profile.idCard());
    }
}
