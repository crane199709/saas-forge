package io.saasforge.platform.subapp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

/** App Registry 中登记的 Sub-App。各环境入口地址与最低共享依赖版本在关联表中，经 {@code SubAppMapper} 读写。 */
@TableName("sub_app")
public class SubApp extends AuditableEntity {
    private UUID id;
    private String code;
    private String name;
    private String version;
    private Long revision;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }
}
